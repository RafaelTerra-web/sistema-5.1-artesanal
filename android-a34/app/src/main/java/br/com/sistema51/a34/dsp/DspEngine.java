package br.com.sistema51.a34.dsp;

import java.util.Arrays;
import java.util.concurrent.atomic.AtomicLong;

/**
 * Fixed-format DSP. One audio thread owns process(); control threads may submit
 * immutable profiles and request reset. Valid process calls allocate no objects.
 */
public final class DspEngine {
    public static final int SAMPLE_RATE = AudioProfile.SAMPLE_RATE;
    public static final int CHANNEL_COUNT = AudioProfile.CHANNEL_COUNT;
    private static final int RING_LENGTH = AudioProfile.MAX_DELAY_SAMPLES + 1;
    private static final double BUTTERWORTH_Q = Math.sqrt(0.5);

    private final int maxBlockFrames;
    private final double[][] delayRings = new double[CHANNEL_COUNT][RING_LENGTH];
    private final double[] frame = new double[CHANNEL_COUNT];
    private final float[] blockPeaks = new float[CHANNEL_COUNT];
    private final double[] outputGain=new double[CHANNEL_COUNT],targetGain=new double[CHANNEL_COUNT],gainStep=new double[CHANNEL_COUNT];
    private int gainRampRemaining;
    private double generalEqGain=1,generalEqGainTarget=1,generalEqGainStep;
    private int generalEqGainRemaining;
    private double centerToFrontGain,centerToFrontGainTarget,centerToFrontGainStep;
    private int centerToFrontGainRemaining;
    private final Biquad[] eq = new Biquad[AudioProfile.EQ_BAND_COUNT];
    private final Biquad[][] generalEq=new Biquad[CHANNEL_COUNT][AudioProfile.GENERAL_EQ_BAND_COUNT];
    private final Biquad[][] roomEq=new Biquad[CHANNEL_COUNT][RoomCorrection.MAX_FILTERS];
    private final Biquad[] surroundLow = filters(4), surroundHigh = filters(4);
    private final Biquad[] centerLow = filters(2), stereoLow = filters(2);
    private final Biquad[] frontLow=filters(6),frontHigh=filters(6);
    private final Biquad lfeSubsonic=new Biquad();
    private final AtomicLong resetRequests = new AtomicLong();
    private long appliedResetRequest;
    private int ringPosition;
    private volatile CompiledProfile requested;
    private CompiledProfile active;
    private volatile long clippedSamples, nonFiniteInputSamples, framesProcessed;

    public DspEngine(int maxBlockFrames) {
        if (maxBlockFrames < 1 || maxBlockFrames > SAMPLE_RATE) {
            throw new IllegalArgumentException("maxBlockFrames must be 1..48000");
        }
        this.maxBlockFrames = maxBlockFrames;
        for (int b = 0; b < eq.length; b++) eq[b] = new Biquad();
        for(int c=0;c<CHANNEL_COUNT;c++)for(int b=0;b<AudioProfile.GENERAL_EQ_BAND_COUNT;b++)generalEq[c][b]=new Biquad();
        for(int c=0;c<CHANNEL_COUNT;c++)for(int b=0;b<RoomCorrection.MAX_FILTERS;b++)roomEq[c][b]=new Biquad();
        requested = new CompiledProfile(AudioProfile.defaultProfile());
        applyProfile(requested);
    }

    /** Compile on the control thread, then publish in one volatile write. */
    public void setProfile(AudioProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile cannot be null");
        requested = new CompiledProfile(profile,requested);
    }
    /** Latest submitted settings; applied by the next process call. */
    public AudioProfile getProfile() { return requested.profile; }
    /** Audio-thread read after process: settings of that exact processed block. */
    public AudioProfile getAppliedProfile(){return active.profile;}
    public int getMaxBlockFrames() { return maxBlockFrames; }
    public long getClippedSamples() { return clippedSamples; }
    public long getNonFiniteInputSamples() { return nonFiniteInputSamples; }
    public long getFramesProcessed() { return framesProcessed; }
    /** Peak of the most recent block, after mute, trims and final clamp. */
    public float getChannelPeak(int channel) { return blockPeaks[AudioProfile.channelIndex(channel)]; }
    /** Clear histories and cumulative counters at the next audio block boundary. */
    public void reset() { resetRequests.incrementAndGet(); }

    public void process(float[] input, int inputChannels, float[] output, int frames) {
        CompiledProfile next = requested;
        if (input == null || output == null) throw new IllegalArgumentException("buffers cannot be null");
        if (inputChannels != next.profile.getRequiredInputChannels()) {
            throw new IllegalArgumentException("Input channel count does not match explicit profile input mode");
        }
        if (frames < 0 || frames > maxBlockFrames || frames > input.length / inputChannels
                || frames > output.length / CHANNEL_COUNT) {
            throw new IllegalArgumentException("Invalid block frame count or buffer size");
        }
        if (input == output && inputChannels != CHANNEL_COUNT) {
            throw new IllegalArgumentException("Stereo expansion requires separate input/output buffers");
        }
        if (next != active) applyProfile(next);
        long resetSerial = resetRequests.get();
        if (resetSerial != appliedResetRequest) {
            clearHistory();
            clippedSamples = 0; nonFiniteInputSamples = 0; framesProcessed = 0;
            appliedResetRequest = resetSerial;
        }
        Arrays.fill(blockPeaks, 0);
        AudioProfile p = active.profile;
        double master = p.isMuted() ? 0 : p.getMasterGain();
        int generalEqMask=0;
        int roomMask=0;
        for(int c=0;c<CHANNEL_COUNT;c++)for(Biquad filter:roomEq[c])if(filter.active()){roomMask|=1<<c;break;}
        for(int b=0;b<AudioProfile.GENERAL_EQ_BAND_COUNT;b++)
            for(int c=0;c<CHANNEL_COUNT;c++)if(generalEq[c][b].active()){generalEqMask|=1<<b;break;}
        for (int f = 0, inputIndex = 0, outputIndex = 0; f < frames;
             f++, inputIndex += inputChannels, outputIndex += CHANNEL_COUNT) {
            if(centerToFrontGainRemaining>0){
                centerToFrontGain+=centerToFrontGainStep;
                if(--centerToFrontGainRemaining==0)centerToFrontGain=centerToFrontGainTarget;
            }
            if (inputChannels == CHANNEL_COUNT) {
                for (int c = 0; c < CHANNEL_COUNT; c++) frame[c] = finiteInput(input[inputIndex + c]);
            } else {
                double left = finiteInput(input[inputIndex]), right = finiteInput(input[inputIndex + 1]);
                frame[AudioProfile.FL] = left;
                frame[AudioProfile.FR] = right;
                double mono = 0.5 * left + 0.5 * right;
                double difference = p.getUpmixDifference();
                double surroundScale = p.getUpmixSurroundGain() / (1.0 + difference);
                frame[AudioProfile.FC] = p.getUpmixCenterGain() * mono;
                frame[AudioProfile.LFE] = cascade(stereoLow, 0, p.getUpmixBassGain() * mono);
                frame[AudioProfile.SL] = surroundScale * (left - difference * right);
                frame[AudioProfile.SR] = surroundScale * (right - difference * left);
            }
            if (!p.isBypass()) {
                if (p.isSurroundCrossoverEnabled()) {
                    double left = frame[AudioProfile.SL], right = frame[AudioProfile.SR];
                    double bass = cascade(surroundLow, 0, left) + cascade(surroundLow, 2, right);
                    frame[AudioProfile.LFE] += p.getSurroundBassSend() * bass;
                    frame[AudioProfile.SL] = cascade(surroundHigh, 0, left);
                    frame[AudioProfile.SR] = cascade(surroundHigh, 2, right);
                }
                if(p.isFrontCrossoverEnabled()) {
                    for(int c=0;c<3;c++) {
                        double original=frame[c];
                        frame[AudioProfile.LFE]+=p.getFrontBassSend()*cascade(frontLow,c*2,original);
                        frame[c]=cascade(frontHigh,c*2,original);
                    }
                }
                if (p.isCenterBassCopyEnabled() && !p.isFrontCrossoverEnabled()) {
                    frame[AudioProfile.LFE] += p.getCenterBassSend()
                            * cascade(centerLow, 0, frame[AudioProfile.FC]);
                }
                // Duplicate only the discrete 5.1 center channel. The original center
                // remains untouched; each copy then receives its own front delay/trim.
                if(inputChannels==CHANNEL_COUNT&&centerToFrontGain!=0){
                    double center=frame[AudioProfile.FC]*centerToFrontGain;
                    frame[AudioProfile.FL]+=center;
                    frame[AudioProfile.FR]+=center;
                }
                // Copy bass before delays: copied material follows the LFE delay.
                for (int c = 0; c < CHANNEL_COUNT; c++) {
                    delayRings[c][ringPosition] = frame[c];
                    int readPosition = ringPosition - p.getDelaySamples(c);
                    if (readPosition < 0) readPosition += RING_LENGTH;
                    frame[c] = delayRings[c][readPosition];
                }
                if (++ringPosition == RING_LENGTH) ringPosition = 0;
                double lfe = frame[AudioProfile.LFE] * p.getEffectiveLfeHeadroom();
                if (p.isLfeEqEnabled()) for (Biquad filter : eq) lfe = filter.process(lfe);
                if(p.isLfeSubsonicEnabled())lfe=lfeSubsonic.process(lfe);
                frame[AudioProfile.LFE] = lfe;
                if(generalEqMask!=0||generalEqGain!=1||generalEqGainRemaining>0){
                    if(generalEqGainRemaining>0){generalEqGain+=generalEqGainStep;if(--generalEqGainRemaining==0)generalEqGain=generalEqGainTarget;}
                    for(int c=0;c<CHANNEL_COUNT;c++){
                        double sample=frame[c];
                        for(int b=0;b<AudioProfile.GENERAL_EQ_BAND_COUNT;b++)if((generalEqMask&(1<<b))!=0)sample=generalEq[c][b].process(sample);
                        frame[c]=sample*generalEqGain;
                    }
                }
                if(roomMask!=0)for(int c=0;c<CHANNEL_COUNT;c++)if((roomMask&(1<<c))!=0)
                    for(Biquad filter:roomEq[c])frame[c]=filter.process(frame[c]);
            }
            for (int c = 0; c < CHANNEL_COUNT; c++) {
                if(gainRampRemaining>0)outputGain[c]+=gainStep[c];
                double value = frame[c] * (master==0?0:outputGain[c]);
                float sample = clampOutput(value);
                output[outputIndex + c] = sample;
                blockPeaks[c] = Math.max(blockPeaks[c], Math.abs(sample));
            }
            if(gainRampRemaining>0&&--gainRampRemaining==0)System.arraycopy(targetGain,0,outputGain,0,CHANNEL_COUNT);
        }
        framesProcessed += frames;
    }

    /** Convenience only for explicit native 5.1 input. */
    public void process(float[] input, float[] output, int frames) { process(input, CHANNEL_COUNT, output, frames); }

    private double finiteInput(float sample) {
        if (Float.isNaN(sample) || Float.isInfinite(sample)) { nonFiniteInputSamples++; return 0; }
        return sample;
    }
    private float clampOutput(double sample) {
        if (Double.isNaN(sample) || Double.isInfinite(sample)) { clippedSamples++; return 0; }
        if (sample > 1) { clippedSamples++; return 1; }
        if (sample < -1) { clippedSamples++; return -1; }
        return (float) sample;
    }
    private static double cascade(Biquad[] filters, int offset, double x) {
        return filters[offset + 1].process(filters[offset].process(x));
    }
    private static Biquad[] filters(int count) {
        Biquad[] result = new Biquad[count];
        for (int i = 0; i < count; i++) result[i] = new Biquad();
        return result;
    }

    private void applyProfile(CompiledProfile next) {
        boolean ramp=active!=null&&framesProcessed>0&&!active.profile.isMuted()&&!next.profile.isMuted();
        boolean gainChanged=false;
        for(int c=0;c<CHANNEL_COUNT;c++){
            targetGain[c]=next.profile.getChannelTrim(c)*next.profile.getMasterGain();
            gainChanged|=targetGain[c]!=outputGain[c];
        }
        if(ramp&&gainChanged){gainRampRemaining=480;for(int c=0;c<CHANNEL_COUNT;c++)gainStep[c]=(targetGain[c]-outputGain[c])/480;}
        else if(!ramp){gainRampRemaining=0;System.arraycopy(targetGain,0,outputGain,0,CHANNEL_COUNT);}
        centerToFrontGainTarget=next.profile.getCenterToFrontGain();
        if(ramp&&centerToFrontGainTarget!=centerToFrontGain){
            centerToFrontGainRemaining=480;
            centerToFrontGainStep=(centerToFrontGainTarget-centerToFrontGain)/480;
        }else if(!ramp){centerToFrontGainRemaining=0;centerToFrontGain=centerToFrontGainTarget;}
        // Gain, headroom, mute and trims do not destroy delay/filter histories.
        boolean changedTopology = active == null || graphChanged(active.profile, next.profile);
        if(changedTopology||framesProcessed==0){generalEqGainRemaining=0;generalEqGain=generalEqGainTarget=next.generalEqHeadroom;}
        else if(generalEqGainTarget!=next.generalEqHeadroom){generalEqGainTarget=next.generalEqHeadroom;generalEqGainRemaining=480;generalEqGainStep=(generalEqGainTarget-generalEqGain)/480;}
        for (int b = 0; b < eq.length; b++) {if(changedTopology)eq[b].coefficients(next.eqCoefficients[b]);else eq[b].smoothCoefficients(next.eqCoefficients[b]);}
        for(int c=0;c<CHANNEL_COUNT;c++)for(int b=0;b<AudioProfile.GENERAL_EQ_BAND_COUNT;b++){
            if(changedTopology)generalEq[c][b].coefficients(next.generalEqCoefficients[b]);else generalEq[c][b].smoothCoefficients(next.generalEqCoefficients[b]);
        }
        for(int c=0;c<CHANNEL_COUNT;c++)for(int b=0;b<RoomCorrection.MAX_FILTERS;b++){
            if(changedTopology)roomEq[c][b].coefficients(next.roomCoefficients[c][b]);
            else roomEq[c][b].smoothCoefficients(next.roomCoefficients[c][b]);
        }
        for (Biquad filter : surroundLow) filter.coefficients(next.surroundLow);
        for (Biquad filter : surroundHigh) filter.coefficients(next.surroundHigh);
        for (Biquad filter : centerLow) filter.coefficients(next.centerLow);
        for (Biquad filter : stereoLow) filter.coefficients(next.stereoLow);
        for(Biquad filter:frontLow)filter.coefficients(next.frontLow);
        for(Biquad filter:frontHigh)filter.coefficients(next.frontHigh);
        lfeSubsonic.coefficients(next.lfeSubsonic);
        active = next;
        if (changedTopology) clearHistory();
    }
    private static boolean graphChanged(AudioProfile a, AudioProfile b) {
        if (a.getInputMode() != b.getInputMode() || a.isBypass() != b.isBypass()
                || a.isLfeEqEnabled() != b.isLfeEqEnabled()
                || a.isSurroundCrossoverEnabled() != b.isSurroundCrossoverEnabled()
                || a.isCenterBassCopyEnabled() != b.isCenterBassCopyEnabled()
                || a.getSurroundCutoffHz() != b.getSurroundCutoffHz()
                || a.getCenterBassCutoffHz() != b.getCenterBassCutoffHz()
                || a.getUpmixBassCutoffHz() != b.getUpmixBassCutoffHz()) return true;
        if(a.isFrontCrossoverEnabled()!=b.isFrontCrossoverEnabled()||a.getFrontCutoffHz()!=b.getFrontCutoffHz()
                ||a.isLfeSubsonicEnabled()!=b.isLfeSubsonicEnabled()||a.getLfeSubsonicHz()!=b.getLfeSubsonicHz())return true;
        for (int c = 0; c < CHANNEL_COUNT; c++) if (a.getDelaySamples(c) != b.getDelaySamples(c)) return true;
        return false;
    }
    private void clearHistory() {
        for (double[] ring : delayRings) Arrays.fill(ring, 0);
        for (Biquad filter : eq) filter.reset();
        for (Biquad filter : surroundLow) filter.reset();
        for (Biquad filter : surroundHigh) filter.reset();
        for (Biquad filter : centerLow) filter.reset();
        for (Biquad filter : stereoLow) filter.reset();
        for(Biquad filter:frontLow)filter.reset();
        for(Biquad filter:frontHigh)filter.reset();
        for(Biquad[] channel:generalEq)for(Biquad filter:channel)filter.reset();
        for(Biquad[] channel:roomEq)for(Biquad filter:channel)filter.reset();
        lfeSubsonic.reset();
        ringPosition = 0;
    }

    private static final class CompiledProfile {
        final AudioProfile profile;
        final double[][] eqCoefficients = new double[AudioProfile.EQ_BAND_COUNT][];
        final double[][] generalEqCoefficients=new double[AudioProfile.GENERAL_EQ_BAND_COUNT][];
        final boolean[] generalEqBandEnabled=new boolean[AudioProfile.GENERAL_EQ_BAND_COUNT];
        final double[][][] roomCoefficients=new double[CHANNEL_COUNT][RoomCorrection.MAX_FILTERS][];
        final double generalEqHeadroom;
        final double[] surroundLow, surroundHigh, centerLow, stereoLow;
        final double[] frontLow,frontHigh,lfeSubsonic;
        CompiledProfile(AudioProfile profile) { this(profile,null); }
        CompiledProfile(AudioProfile profile,CompiledProfile previous) {
            this.profile = profile;
            RoomCorrection room=profile.getRoomCorrection();
            for(int c=0;c<CHANNEL_COUNT;c++)for(int b=0;b<RoomCorrection.MAX_FILTERS;b++){
                if(previous!=null&&room.equals(previous.profile.getRoomCorrection()))roomCoefficients[c][b]=previous.roomCoefficients[c][b];
                else if(room.isEnabled()&&b<room.count(c)){
                    RoomCorrection.Filter f=room.filter(c,b);roomCoefficients[c][b]=Biquad.peak(f.frequencyHz,f.q,f.gainDb);
                }else roomCoefficients[c][b]=Biquad.peak(100,1,0);
            }
            if(previous!=null&&sameFilterSettings(profile,previous.profile)){
                System.arraycopy(previous.eqCoefficients,0,eqCoefficients,0,eqCoefficients.length);
                System.arraycopy(previous.generalEqCoefficients,0,generalEqCoefficients,0,generalEqCoefficients.length);
                System.arraycopy(previous.generalEqBandEnabled,0,generalEqBandEnabled,0,generalEqBandEnabled.length);
                generalEqHeadroom=previous.generalEqHeadroom;
                surroundLow=previous.surroundLow;surroundHigh=previous.surroundHigh;centerLow=previous.centerLow;stereoLow=previous.stereoLow;
                frontLow=previous.frontLow;frontHigh=previous.frontHigh;lfeSubsonic=previous.lfeSubsonic;
                return;
            }
            for (int i = 0; i < eqCoefficients.length; i++) {
                eqCoefficients[i] = Biquad.peak(profile.getLfeEqFrequencyHz(i),
                        profile.getLfeEqQ(i), profile.getLfeEqGainDb(i));
            }
            boolean anyGeneralEqBand=false;
            for(int i=0;i<generalEqCoefficients.length;i++){
                float gain=profile.isGeneralEqEnabled()?profile.getGeneralEqGainDb(i):0;
                generalEqBandEnabled[i]=profile.isGeneralEqEnabled()&&gain!=0;
                anyGeneralEqBand|=generalEqBandEnabled[i];
                generalEqCoefficients[i]=Biquad.peak(AudioProfile.getGeneralEqFrequencyHz(i),AudioProfile.getGeneralEqQ(),gain);
            }
            double largestOutputGain=0;
            for(int channel=0;channel<AudioProfile.CHANNEL_COUNT;channel++)
                largestOutputGain=Math.max(largestOutputGain,profile.getMasterGain()*profile.getChannelTrim(channel));
            generalEqHeadroom=anyGeneralEqBand
                    ?generalEqHeadroom(generalEqCoefficients,generalEqBandEnabled,largestOutputGain):1;
            surroundLow = Biquad.lowPass(profile.getSurroundCutoffHz(), BUTTERWORTH_Q);
            surroundHigh = Biquad.highPass(profile.getSurroundCutoffHz(), BUTTERWORTH_Q);
            centerLow = Biquad.lowPass(profile.getCenterBassCutoffHz(), BUTTERWORTH_Q);
            stereoLow = Biquad.lowPass(profile.getUpmixBassCutoffHz(), BUTTERWORTH_Q);
            frontLow=Biquad.lowPass(profile.getFrontCutoffHz(),BUTTERWORTH_Q);
            frontHigh=Biquad.highPass(profile.getFrontCutoffHz(),BUTTERWORTH_Q);
            lfeSubsonic=Biquad.highPass(profile.getLfeSubsonicHz(),BUTTERWORTH_Q);
        }

        private static boolean sameFilterSettings(AudioProfile a,AudioProfile b){
            if(a.isGeneralEqEnabled()!=b.isGeneralEqEnabled()||a.getSurroundCutoffHz()!=b.getSurroundCutoffHz()
                    ||a.getCenterBassCutoffHz()!=b.getCenterBassCutoffHz()||a.getUpmixBassCutoffHz()!=b.getUpmixBassCutoffHz()
                    ||a.getFrontCutoffHz()!=b.getFrontCutoffHz()||a.getLfeSubsonicHz()!=b.getLfeSubsonicHz()
                    ||a.getMasterGain()!=b.getMasterGain())return false;
            for(int channel=0;channel<AudioProfile.CHANNEL_COUNT;channel++)
                if(a.getChannelTrim(channel)!=b.getChannelTrim(channel))return false;
            for(int i=0;i<AudioProfile.EQ_BAND_COUNT;i++)if(a.getLfeEqFrequencyHz(i)!=b.getLfeEqFrequencyHz(i)
                    ||a.getLfeEqGainDb(i)!=b.getLfeEqGainDb(i)||a.getLfeEqQ(i)!=b.getLfeEqQ(i))return false;
            for(int i=0;i<AudioProfile.GENERAL_EQ_BAND_COUNT;i++)if(a.getGeneralEqGainDb(i)!=b.getGeneralEqGainDb(i))return false;
            return true;
        }

        private static double generalEqHeadroom(double[][] coefficients,boolean[] enabled,double largestOutputGain){
            double peak=1;
            for(int step=0;step<=1024;step++){
                double frequency=20*Math.pow(20000.0/20,step/1024.0),omega=2*Math.PI*frequency/AudioProfile.SAMPLE_RATE;
                double real=1,imaginary=0;
                double cos1=Math.cos(omega),cos2=Math.cos(2*omega),sin1=Math.sin(omega),sin2=Math.sin(2*omega);
                for(int band=0;band<coefficients.length;band++)if(enabled[band]){
                    double[] c=coefficients[band];
                    double nReal=c[0]+c[1]*cos1+c[2]*cos2;
                    double nImaginary=-c[1]*sin1-c[2]*sin2;
                    double dReal=1+c[3]*cos1+c[4]*cos2;
                    double dImaginary=-c[3]*sin1-c[4]*sin2;
                    double denominator=dReal*dReal+dImaginary*dImaginary;
                    double hReal=(nReal*dReal+nImaginary*dImaginary)/denominator;
                    double hImaginary=(nImaginary*dReal-nReal*dImaginary)/denominator;
                    double nextReal=real*hReal-imaginary*hImaginary;
                    imaginary=real*hImaginary+imaginary*hReal;real=nextReal;
                }
                peak=Math.max(peak,Math.hypot(real,imaginary));
            }
            // Keep the user's band boost audible while the configured master and
            // channel trims leave room. Reduce only the excess that could exceed
            // full scale at the largest combined EQ response.
            double predictedPeak=peak*largestOutputGain;
            return predictedPeak<=1||predictedPeak<=0?1:1/predictedPeak;
        }
    }
}
