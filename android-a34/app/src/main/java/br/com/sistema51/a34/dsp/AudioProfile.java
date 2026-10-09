package br.com.sistema51.a34.dsp;

/** Immutable, validated settings for the fixed 48 kHz / six-channel DSP. */
public final class AudioProfile {
    public static final int SAMPLE_RATE = 48000;
    public static final int CHANNEL_COUNT = 6;
    public static final int EQ_BAND_COUNT = 9;
    public static final int MAX_DELAY_SAMPLES = SAMPLE_RATE / 4;
    public static final int FL = 0, FR = 1, FC = 2, LFE = 3, SL = 4, SR = 5;

    public enum InputMode { NATIVE_5_1, STEREO_UPMIX }

    private final float masterGain;
    private final boolean muted, bypass;
    private final InputMode inputMode;
    private final float[] channelTrims;
    private final int[] delays;
    private final boolean lfeEqEnabled;
    private final float[] eqFrequencies, eqGains, eqQ;
    private final float lfeHeadroom;
    private final boolean automaticLfeHeadroom;
    private final boolean surroundCrossoverEnabled, centerBassCopyEnabled;
    private final float surroundCutoffHz, surroundBassSend;
    private final float centerBassCutoffHz, centerBassSend;
    private final float effectiveLfeHeadroom;
    private final float upmixCenterGain, upmixSurroundGain, upmixBassGain, upmixDifference, upmixBassCutoffHz;
    private final boolean frontCrossoverEnabled, lfeSubsonicEnabled, swapCenterLfe;
    private final float frontCutoffHz, frontBassSend, lfeSubsonicHz;

    private AudioProfile(Builder b) {
        masterGain = range("masterGain", b.masterGain, 0, 1);
        muted = b.muted;
        bypass = b.bypass;
        if (b.inputMode == null) throw new IllegalArgumentException("inputMode cannot be null");
        inputMode = b.inputMode;
        upmixCenterGain = range("upmixCenterGain", b.upmixCenterGain, 0, 1);
        upmixSurroundGain = range("upmixSurroundGain", b.upmixSurroundGain, 0, 1);
        upmixBassGain = range("upmixBassGain", b.upmixBassGain, 0, 1);
        upmixDifference = range("upmixDifference", b.upmixDifference, 0, 1);
        upmixBassCutoffHz = range("upmixBassCutoffHz", b.upmixBassCutoffHz, 40, 160);
        frontCrossoverEnabled=b.frontCrossoverEnabled;
        frontCutoffHz=range("frontCutoffHz",b.frontCutoffHz,40,160);
        frontBassSend=range("frontBassSend",b.frontBassSend,0,1);
        lfeSubsonicEnabled=b.lfeSubsonicEnabled;
        lfeSubsonicHz=range("lfeSubsonicHz",b.lfeSubsonicHz,10,40);
        swapCenterLfe=b.swapCenterLfe;
        channelTrims = b.channelTrims.clone();
        delays = b.delays.clone();
        for (int c = 0; c < CHANNEL_COUNT; c++) {
            range("channelTrim", channelTrims[c], 0, 4);
            if (delays[c] < 0 || delays[c] > MAX_DELAY_SAMPLES) {
                throw new IllegalArgumentException("delaySamples must be between 0 and 12000 (250 ms)");
            }
        }
        lfeEqEnabled = b.lfeEqEnabled;
        eqFrequencies = b.eqFrequencies.clone();
        eqGains = b.eqGains.clone();
        eqQ = b.eqQ.clone();
        for (int i = 0; i < EQ_BAND_COUNT; i++) {
            range("lfeEqFrequencyHz", eqFrequencies[i], 10, 200);
            range("lfeEqGainDb", eqGains[i], -12, 6);
            range("lfeEqQ", eqQ[i], 0.3f, 10);
        }
        lfeHeadroom = range("lfeHeadroom", b.lfeHeadroom, 0, 1);
        automaticLfeHeadroom = b.automaticLfeHeadroom;
        surroundCrossoverEnabled = b.surroundCrossoverEnabled;
        surroundCutoffHz = range("surroundCutoffHz", b.surroundCutoffHz, 40, 120);
        surroundBassSend = range("surroundBassSend", b.surroundBassSend, 0, 1);
        centerBassCopyEnabled = b.centerBassCopyEnabled;
        centerBassCutoffHz = range("centerBassCutoffHz", b.centerBassCutoffHz, 40, 120);
        centerBassSend = range("centerBassSend", b.centerBassSend, 0, 1);
        if (automaticLfeHeadroom) {
            double positiveDb = 0;
            if (lfeEqEnabled) {
                for (float gain : eqGains) positiveDb += Math.max(0, gain);
            }
            double mixBound = 1 + (surroundCrossoverEnabled ? 2 * surroundBassSend : 0)
                    + (frontCrossoverEnabled ? 3 * frontBassSend : 0)
                    + (centerBassCopyEnabled && !frontCrossoverEnabled ? centerBassSend : 0);
            effectiveLfeHeadroom = (float) (Math.pow(10, -positiveDb / 20) / mixBound);
        } else {
            effectiveLfeHeadroom = lfeHeadroom;
        }
    }

    public static AudioProfile defaultProfile() { return new Builder().build(); }
    public static Builder builder() { return new Builder(); }
    public Builder toBuilder() { return new Builder(this); }

    public float getMasterGain() { return masterGain; }
    public boolean isMuted() { return muted; }
    public boolean isBypass() { return bypass; }
    public InputMode getInputMode() { return inputMode; }
    public float getUpmixCenterGain() { return upmixCenterGain; }
    public float getUpmixSurroundGain() { return upmixSurroundGain; }
    public float getUpmixBassGain() { return upmixBassGain; }
    /** 0 duplicates L/R; 1 uses normalized L-R/R-L, cancelling coherent mono. */
    public float getUpmixDifference() { return upmixDifference; }
    public float getUpmixBassCutoffHz() { return upmixBassCutoffHz; }
    public boolean isFrontCrossoverEnabled(){return frontCrossoverEnabled;}
    public float getFrontCutoffHz(){return frontCutoffHz;}
    public float getFrontBassSend(){return frontBassSend;}
    public boolean isLfeSubsonicEnabled(){return lfeSubsonicEnabled;}
    public float getLfeSubsonicHz(){return lfeSubsonicHz;}
    public boolean isSwapCenterLfe(){return swapCenterLfe;}
    public float getChannelTrim(int channel) { return channelTrims[channelIndex(channel)]; }
    public int getDelaySamples(int channel) { return delays[channelIndex(channel)]; }
    public boolean isLfeEqEnabled() { return lfeEqEnabled; }
    public float getLfeEqGainDb(int band) { return eqGains[bandIndex(band)]; }
    public float getLfeEqFrequencyHz(int band) { return eqFrequencies[bandIndex(band)]; }
    public float getLfeEqQ(int band) { return eqQ[bandIndex(band)]; }
    /** Manual gain, ignored while automatic headroom is enabled. */
    public float getLfeHeadroom() { return lfeHeadroom; }
    public boolean isAutomaticLfeHeadroom() { return automaticLfeHeadroom; }
    /** Gain after conservative EQ and bass-sum headroom calculation. */
    public float getEffectiveLfeHeadroom() { return effectiveLfeHeadroom; }
    public boolean isSurroundCrossoverEnabled() { return surroundCrossoverEnabled; }
    public float getSurroundCutoffHz() { return surroundCutoffHz; }
    public float getSurroundBassSend() { return surroundBassSend; }
    public boolean isCenterBassCopyEnabled() { return centerBassCopyEnabled; }
    public float getCenterBassCutoffHz() { return centerBassCutoffHz; }
    public float getCenterBassSend() { return centerBassSend; }
    public int getRequiredInputChannels() { return inputMode == InputMode.NATIVE_5_1 ? 6 : 2; }
    /** Resolve only from trustworthy decoded file/transport metadata, never meters. */
    public AudioProfile forSourceChannels(int channels) {
        if(channels==6)return inputMode==InputMode.NATIVE_5_1?this:toBuilder().inputMode(InputMode.NATIVE_5_1).build();
        if(channels==1||channels==2)return inputMode==InputMode.STEREO_UPMIX?this:toBuilder().inputMode(InputMode.STEREO_UPMIX).build();
        throw new IllegalArgumentException("Fonte precisa declarar 1, 2 ou 6 canais.");
    }

    static int channelIndex(int c) {
        if (c < 0 || c >= CHANNEL_COUNT) throw new IllegalArgumentException("channel must be 0..5");
        return c;
    }
    static int bandIndex(int band) {
        if (band < 0 || band >= EQ_BAND_COUNT) throw new IllegalArgumentException("band must be 0..8");
        return band;
    }
    private static float range(String name, float value, float min, float max) {
        if (Float.isNaN(value) || Float.isInfinite(value) || value < min || value > max) {
            throw new IllegalArgumentException(name + " must be finite and between " + min + " and " + max);
        }
        return value;
    }

    public static final class Builder {
        private float masterGain = 0.04f;
        private boolean muted, bypass;
        private InputMode inputMode = InputMode.NATIVE_5_1;
        // Preserve the earlier matrix for profiles that predate these controls.
        private float upmixCenterGain = 1, upmixSurroundGain = .5f, upmixBassGain = .5f;
        private float upmixDifference = 0, upmixBassCutoffHz = 120;
        private boolean frontCrossoverEnabled=false,lfeSubsonicEnabled=false,swapCenterLfe=false;
        private float frontCutoffHz=90,frontBassSend=1,lfeSubsonicHz=20;
        private final float[] channelTrims = {1, 1, 1, 1, 1, 1};
        private final int[] delays = {3686, 3686, 278, 278, 3408, 3408};
        private boolean lfeEqEnabled = true;
        private final float[] eqFrequencies = {20, 25, 30, 40, 50, 60, 80, 100, 120};
        private final float[] eqGains = {6, 6, 6, 5.5f, 1.5f, -4, 1, 1, -2.5f};
        private final float[] eqQ = {2, 2, 2, 2, 2, 2, 2, 2, 2};
        private float lfeHeadroom = 1f / 3f;
        private boolean automaticLfeHeadroom = true;
        private boolean surroundCrossoverEnabled = true, centerBassCopyEnabled;
        private float surroundCutoffHz = 90, surroundBassSend = 1;
        private float centerBassCutoffHz = 120, centerBassSend = 1;

        private Builder() { }
        private Builder(AudioProfile p) {
            masterGain = p.masterGain; muted = p.muted; bypass = p.bypass; inputMode = p.inputMode;
            upmixCenterGain = p.upmixCenterGain; upmixSurroundGain = p.upmixSurroundGain;
            upmixBassGain = p.upmixBassGain; upmixDifference = p.upmixDifference;
            upmixBassCutoffHz = p.upmixBassCutoffHz;
            frontCrossoverEnabled=p.frontCrossoverEnabled;frontCutoffHz=p.frontCutoffHz;frontBassSend=p.frontBassSend;
            lfeSubsonicEnabled=p.lfeSubsonicEnabled;lfeSubsonicHz=p.lfeSubsonicHz;
            swapCenterLfe=p.swapCenterLfe;
            System.arraycopy(p.channelTrims, 0, channelTrims, 0, CHANNEL_COUNT);
            System.arraycopy(p.delays, 0, delays, 0, CHANNEL_COUNT);
            lfeEqEnabled = p.lfeEqEnabled;
            System.arraycopy(p.eqFrequencies, 0, eqFrequencies, 0, EQ_BAND_COUNT);
            System.arraycopy(p.eqGains, 0, eqGains, 0, EQ_BAND_COUNT);
            System.arraycopy(p.eqQ, 0, eqQ, 0, EQ_BAND_COUNT);
            lfeHeadroom = p.lfeHeadroom; automaticLfeHeadroom = p.automaticLfeHeadroom;
            surroundCrossoverEnabled = p.surroundCrossoverEnabled;
            surroundCutoffHz = p.surroundCutoffHz; surroundBassSend = p.surroundBassSend;
            centerBassCopyEnabled = p.centerBassCopyEnabled;
            centerBassCutoffHz = p.centerBassCutoffHz; centerBassSend = p.centerBassSend;
        }

        public Builder masterGain(float value) { masterGain = value; return this; }
        public Builder muted(boolean value) { muted = value; return this; }
        public Builder bypass(boolean value) { bypass = value; return this; }
        public Builder inputMode(InputMode value) { inputMode = value; return this; }
        public Builder upmixCenterGain(float value) { upmixCenterGain = value; return this; }
        public Builder upmixSurroundGain(float value) { upmixSurroundGain = value; return this; }
        public Builder upmixBassGain(float value) { upmixBassGain = value; return this; }
        public Builder upmixDifference(float value) { upmixDifference = value; return this; }
        public Builder upmixBassCutoffHz(float value) { upmixBassCutoffHz = value; return this; }
        public Builder frontCrossoverEnabled(boolean value){frontCrossoverEnabled=value;return this;}
        public Builder frontCutoffHz(float value){frontCutoffHz=value;return this;}
        public Builder frontBassSend(float value){frontBassSend=value;return this;}
        public Builder lfeSubsonicEnabled(boolean value){lfeSubsonicEnabled=value;return this;}
        public Builder lfeSubsonicHz(float value){lfeSubsonicHz=value;return this;}
        public Builder swapCenterLfe(boolean value){swapCenterLfe=value;return this;}
        public Builder channelTrim(int channel, float value) { channelTrims[channelIndex(channel)] = value; return this; }
        public Builder delaySamples(int channel, int value) { delays[channelIndex(channel)] = value; return this; }
        public Builder lfeEqEnabled(boolean value) { lfeEqEnabled = value; return this; }
        public Builder lfeEqGainDb(int band, float value) { eqGains[bandIndex(band)] = value; return this; }
        public Builder lfeEqFrequencyHz(int band, float value) { eqFrequencies[bandIndex(band)] = value; return this; }
        public Builder lfeEqQ(int band, float value) { eqQ[bandIndex(band)] = value; return this; }
        public Builder lfeHeadroom(float value) { lfeHeadroom = value; return this; }
        public Builder automaticLfeHeadroom(boolean value) { automaticLfeHeadroom = value; return this; }
        public Builder surroundCrossoverEnabled(boolean value) { surroundCrossoverEnabled = value; return this; }
        public Builder surroundCutoffHz(float value) { surroundCutoffHz = value; return this; }
        public Builder surroundBassSend(float value) { surroundBassSend = value; return this; }
        public Builder centerBassCopyEnabled(boolean value) { centerBassCopyEnabled = value; return this; }
        public Builder centerBassCutoffHz(float value) { centerBassCutoffHz = value; return this; }
        public Builder centerBassSend(float value) { centerBassSend = value; return this; }
        public AudioProfile build() { return new AudioProfile(this); }
    }
}
