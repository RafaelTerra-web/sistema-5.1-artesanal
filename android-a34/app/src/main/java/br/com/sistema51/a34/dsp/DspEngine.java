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
    private final Biquad[] eq = new Biquad[AudioProfile.EQ_BAND_COUNT];
    private final Biquad[] surroundLow = filters(4), surroundHigh = filters(4);
    private final Biquad[] centerLow = filters(2), stereoLow = filters(2);
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
        requested = new CompiledProfile(AudioProfile.defaultProfile());
        applyProfile(requested);
    }

    /** Compile on the control thread, then publish in one volatile write. */
    public void setProfile(AudioProfile profile) {
        if (profile == null) throw new IllegalArgumentException("profile cannot be null");
        requested = new CompiledProfile(profile);
    }
    /** Latest submitted settings; applied by the next process call. */
    public AudioProfile getProfile() { return requested.profile; }
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
        for (int f = 0, inputIndex = 0, outputIndex = 0; f < frames;
             f++, inputIndex += inputChannels, outputIndex += CHANNEL_COUNT) {
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
                if (p.isCenterBassCopyEnabled()) {
                    frame[AudioProfile.LFE] += p.getCenterBassSend()
                            * cascade(centerLow, 0, frame[AudioProfile.FC]);
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
                frame[AudioProfile.LFE] = lfe;
            }
            for (int c = 0; c < CHANNEL_COUNT; c++) {
                double value = frame[c] * p.getChannelTrim(c) * master;
                float sample = clampOutput(value);
                output[outputIndex + c] = sample;
                blockPeaks[c] = Math.max(blockPeaks[c], Math.abs(sample));
            }
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
        // Gain, headroom, mute and trims do not destroy delay/filter histories.
        boolean changedTopology = active == null || graphChanged(active.profile, next.profile);
        for (int b = 0; b < eq.length; b++) eq[b].coefficients(next.eqCoefficients[b]);
        for (Biquad filter : surroundLow) filter.coefficients(next.surroundLow);
        for (Biquad filter : surroundHigh) filter.coefficients(next.surroundHigh);
        for (Biquad filter : centerLow) filter.coefficients(next.centerLow);
        for (Biquad filter : stereoLow) filter.coefficients(next.stereoLow);
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
        for (int c = 0; c < CHANNEL_COUNT; c++) if (a.getDelaySamples(c) != b.getDelaySamples(c)) return true;
        for (int i = 0; i < AudioProfile.EQ_BAND_COUNT; i++) {
            if (a.getLfeEqGainDb(i) != b.getLfeEqGainDb(i)
                    || a.getLfeEqFrequencyHz(i) != b.getLfeEqFrequencyHz(i)
                    || a.getLfeEqQ(i) != b.getLfeEqQ(i)) return true;
        }
        return false;
    }
    private void clearHistory() {
        for (double[] ring : delayRings) Arrays.fill(ring, 0);
        for (Biquad filter : eq) filter.reset();
        for (Biquad filter : surroundLow) filter.reset();
        for (Biquad filter : surroundHigh) filter.reset();
        for (Biquad filter : centerLow) filter.reset();
        for (Biquad filter : stereoLow) filter.reset();
        ringPosition = 0;
    }

    private static final class CompiledProfile {
        final AudioProfile profile;
        final double[][] eqCoefficients = new double[AudioProfile.EQ_BAND_COUNT][];
        final double[] surroundLow, surroundHigh, centerLow, stereoLow;
        CompiledProfile(AudioProfile profile) {
            this.profile = profile;
            for (int i = 0; i < eqCoefficients.length; i++) {
                eqCoefficients[i] = Biquad.peak(profile.getLfeEqFrequencyHz(i),
                        profile.getLfeEqQ(i), profile.getLfeEqGainDb(i));
            }
            surroundLow = Biquad.lowPass(profile.getSurroundCutoffHz(), BUTTERWORTH_Q);
            surroundHigh = Biquad.highPass(profile.getSurroundCutoffHz(), BUTTERWORTH_Q);
            centerLow = Biquad.lowPass(profile.getCenterBassCutoffHz(), BUTTERWORTH_Q);
            stereoLow = Biquad.lowPass(profile.getUpmixBassCutoffHz(), BUTTERWORTH_Q);
        }
    }
}
