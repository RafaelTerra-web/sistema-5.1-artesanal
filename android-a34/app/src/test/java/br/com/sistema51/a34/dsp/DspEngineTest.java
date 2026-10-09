package br.com.sistema51.a34.dsp;

import java.util.Arrays;

/** Standalone JVM acceptance tests; no Android runtime or testing framework. */
public final class DspEngineTest {
    private static int checks;

    public static void main(String[] args) {
        defaultsAndValidation();
        exactDelaysAndRingWrap();
        nativeSeparationAndQuietPassages();
        trimsMuteBypassAndInputContracts();
        explicitStereoUpmix();
        eqGainAndChannelIsolation();
        crossoverAmplitudeAndBassTiming();
        centerCopyLeavesCenterFullRange();
        headroomAndFiniteOutput();
        profileChangesAndReset();
        blockPartitionInvariance();
        System.out.println("DSP JVM acceptance: PASS (" + checks + " assertions / 11 scenarios)");
    }

    private static void defaultsAndValidation() {
        AudioProfile p = AudioProfile.defaultProfile();
        close("default master", 0.04, p.getMasterGain(), 1e-8);
        int[] expected = {3686, 3686, 278, 278, 3408, 3408};
        for (int c = 0; c < 6; c++) {
            eq("default delay " + c, expected[c], p.getDelaySamples(c));
            close("default trim " + c, 1, p.getChannelTrim(c), 0);
        }
        truth("auto headroom default", p.isAutomaticLfeHeadroom());
        truth("center copy explicit off", !p.isCenterBassCopyEnabled());
        close("default crossover", 90, p.getSurroundCutoffHz(), 0);
        close("headroom sums positive overlapping bands", Math.pow(10, -27.0 / 20) / 3,
                p.getEffectiveLfeHeadroom(), 1e-9);
        AudioProfile.Builder b = p.toBuilder();
        AudioProfile snapshot = b.channelTrim(0, 0.5f).build();
        b.channelTrim(0, 0.9f).lfeEqGainDb(0, 0);
        close("builder does not mutate snapshots", 0.5, snapshot.getChannelTrim(0), 0);
        close("previous profile still immutable", 1, p.getChannelTrim(0), 0);
        close("EQ snapshot isolated", 6, snapshot.getLfeEqGainDb(0), 0);
        rejects("master NaN", () -> b.masterGain(Float.NaN).build());
        rejects("master infinity", () -> AudioProfile.builder().masterGain(Float.POSITIVE_INFINITY).build());
        rejects("master negative", () -> AudioProfile.builder().masterGain(-0.01f).build());
        rejects("master >1", () -> AudioProfile.builder().masterGain(1.01f).build());
        rejects("trim upper bound", () -> AudioProfile.builder().channelTrim(1, 4.01f).build());
        rejects("trim nonfinite", () -> AudioProfile.builder().channelTrim(1, Float.NaN).build());
        rejects("delay negative", () -> AudioProfile.builder().delaySamples(2, -1).build());
        rejects("delay >250 ms", () -> AudioProfile.builder().delaySamples(0, 12001).build());
        rejects("invalid channel", () -> AudioProfile.builder().delaySamples(6, 0));
        rejects("invalid band", () -> AudioProfile.builder().lfeEqGainDb(9, 0));
        rejects("EQ boost bound", () -> AudioProfile.builder().lfeEqGainDb(1, 6.01f).build());
        rejects("EQ frequency bound", () -> AudioProfile.builder().lfeEqFrequencyHz(1, 201).build());
        rejects("EQ Q bound", () -> AudioProfile.builder().lfeEqQ(1, 0.2f).build());
        rejects("manual headroom bound", () -> AudioProfile.builder().lfeHeadroom(2).build());
        rejects("surround cutoff bound", () -> AudioProfile.builder().surroundCutoffHz(121).build());
        rejects("surround send bound", () -> AudioProfile.builder().surroundBassSend(1.01f).build());
        rejects("center cutoff bound", () -> AudioProfile.builder().centerBassCutoffHz(39).build());
        rejects("center send nonfinite", () -> AudioProfile.builder().centerBassSend(Float.NEGATIVE_INFINITY).build());
        rejects("mode null", () -> AudioProfile.builder().inputMode(null).build());
    }

    private static void exactDelaysAndRingWrap() {
        AudioProfile p = flat().toBuilder().delaySamples(0, 3686).delaySamples(1, 3686)
                .delaySamples(2, 278).delaySamples(3, 278).delaySamples(4, 3408).delaySamples(5, 3408).build();
        float[] input = new float[4096 * 6];
        for (int c = 0; c < 6; c++) input[c] = (c + 1) / 16f;
        float[] out = render(input, 6, p, 37);
        int[] expected = {3686, 3686, 278, 278, 3408, 3408};
        for (int f = 0; f < 4096; f++) for (int c = 0; c < 6; c++) {
            close("impulse delay independently expected", f == expected[c] ? (c + 1) / 16f : 0,
                    out[f * 6 + c], 0);
        }
        p = flat().toBuilder().delaySamples(0, 12000).delaySamples(1, 1).delaySamples(2, 5999)
                .delaySamples(3, 12000).delaySamples(4, 113).delaySamples(5, 12000).build();
        int total = 30017;
        input = new float[total * 6];
        for (int f = 0; f < total; f++) for (int c = 0; c < 6; c++) {
            input[f * 6 + c] = ((f * 73 + c * 19) % 997 - 498) / 2048f;
        }
        out = render(input, 6, p, 257);
        for (int f = 0; f < total; f++) for (int c = 0; c < 6; c++) {
            int source = f - p.getDelaySamples(c);
            close("ring wrap / max250ms", source < 0 ? 0 : input[source * 6 + c], out[f * 6 + c], 0);
        }
    }

    private static void nativeSeparationAndQuietPassages() {
        DspEngine engine = new DspEngine(480);
        engine.setProfile(flat());
        float[] input = new float[480 * 6], output = new float[480 * 6];
        for (int block = 0; block < 160; block++) {
            Arrays.fill(input, 0);
            for (int f = 0; f < 480; f++) { input[f * 6] = 1e-7f; input[f * 6 + 1] = -0.25f; }
            engine.process(input, 6, output, 480);
            for (int f = 0; f < 480; f++) {
                close("quiet signal retained", 1e-7f, output[f * 6], 0);
                close("FR retained", -0.25, output[f * 6 + 1], 0);
                for (int c = 2; c < 6; c++) close("native front-only passage never upmixes", 0, output[f * 6 + c], 0);
            }
        }
        for (int active = 0; active < 6; active++) {
            Arrays.fill(input, 0); input[active] = 0.125f;
            engine.process(input, 6, output, 1);
            for (int c = 0; c < 6; c++) close("isolated channel never leaks", c == active ? 0.125 : 0, output[c], 0);
        }
    }

    private static void trimsMuteBypassAndInputContracts() {
        AudioProfile p = AudioProfile.defaultProfile().toBuilder().masterGain(0.5f).bypass(true)
                .channelTrim(0, 0.25f).channelTrim(3, 0.5f).build();
        DspEngine engine = new DspEngine(480); engine.setProfile(p);
        float[] in = {0.5f, -0.25f, 0.125f, 0.25f, 0.4f, -0.4f}, out = new float[6];
        engine.process(in, 6, out, 1);
        for (int c = 0; c < 6; c++) close("bypass keeps trims and master", in[c] * 0.5 * p.getChannelTrim(c), out[c], 1e-8);
        engine.setProfile(p.toBuilder().muted(true).build()); engine.process(in, 6, out, 1);
        for (float v : out) close("mute all channels", 0, v, 0);
        engine.setProfile(flat());
        engine.process(in, 6, in, 1);
        close("native in-place supported", 0.5, in[0], 0);
        rejects("channel count declared explicitly", () -> engine.process(new float[2], 2, out, 1));
        rejects("short output rejected", () -> engine.process(new float[12], 6, out, 2));
        rejects("max block enforced", () -> engine.process(new float[481 * 6], 6, new float[481 * 6], 481));
        rejects("negative frame count", () -> engine.process(in, 6, out, -1));
        rejects("null input", () -> engine.process(null, 6, out, 1));
        rejects("null profile", () -> engine.setProfile(null));
        engine.setProfile(flat().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX).build());
        rejects("in-place stereo expansion rejected", () -> engine.process(in, 2, in, 1));
        rejects("six channels are not inferred to be stereo", () -> engine.process(in, 6, out, 1));
    }

    private static void explicitStereoUpmix() {
        AudioProfile p = flat().toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX).build();
        float[] input = new float[48000 * 2];
        for (int f = 0; f < 48000; f++) { input[f * 2] = 0.4f; input[f * 2 + 1] = -0.4f; }
        float[] out = render(input, 2, p, 480);
        float[] expected = {0.4f, -0.4f, 0, 0, 0.2f, -0.2f};
        for (int f = 0; f < 48000; f++) for (int c = 0; c < 6; c++) {
            close("stereo anti-phase matrix", expected[c], out[f * 6 + c], 0);
        }
        Arrays.fill(input, 1e-7f);
        out = render(input, 2, p, 53);
        close("quiet stereo center is never gated", 1e-7f, out[(48000 - 1) * 6 + 2], 0);
        close("quiet stereo low-pass DC floor", 0.5e-7, out[(48000 - 1) * 6 + 3], 1e-13);
        Arrays.fill(input, 0.4f);
        out = render(input, 2, p.toBuilder().bypass(true).build(), 480);
        close("bypass retains explicit upmix center", 0.4f, out[(48000 - 1) * 6 + 2], 0);
        close("stereo LFE normalized mono level", 0.2, out[(48000 - 1) * 6 + 3], 1e-7);
    }

    private static void eqGainAndChannelIsolation() {
        AudioProfile.Builder b = flat().toBuilder().lfeEqEnabled(true);
        for (int i = 0; i < 9; i++) b.lfeEqGainDb(i, 0);
        AudioProfile boost = b.lfeEqGainDb(4, 6).build();
        close("RBJ peak +6 dB at50Hz", Math.pow(10, 6.0 / 20), sineGain(boost, 50, 3, 3), 2e-5);
        AudioProfile cut = b.lfeEqGainDb(4, -12).build();
        close("RBJ peak -12dB at50Hz", Math.pow(10, -12.0 / 20), sineGain(cut, 50, 3, 3), 1e-5);
        close("EQ does not affect fronts", 1, sineGain(boost, 50, 0, 0), 1e-7);
        close("EQ does not create LFE from front", 0, sineGain(boost, 50, 0, 3), 0);
    }

    private static void crossoverAmplitudeAndBassTiming() {
        AudioProfile p = flat().toBuilder().surroundCrossoverEnabled(true).surroundCutoffHz(90).build();
        for (double frequency : new double[] {20, 90, 900}) {
            // Analytic LR4 response with the bilinear-transform frequency warp.
            double ratio = Math.tan(Math.PI * frequency / 48000) / Math.tan(Math.PI * 90 / 48000);
            double power = Math.pow(ratio, 4);
            close("LR4 surround low-pass analytic response", 1 / (1 + power), sineGain(p, frequency, 4, 3), 3e-5);
            close("LR4 surround high-pass analytic response", power / (1 + power), sineGain(p, frequency, 4, 4), 3e-5);
        }
        AudioProfile delayed = p.toBuilder().delaySamples(4, 3408).build();
        float[] in = new float[4000 * 6]; in[4] = 0.1f;
        float[] out = render(in, 6, delayed, 73);
        truth("surround bass reaches undelayed LFE at frame0", out[3] > 0);
        for (int f = 0; f < 3408; f++) close("surround HP waits own delay", 0, out[f * 6 + 4], 0);
        truth("surround HP first output at3408", out[3408 * 6 + 4] > 0.09f);
        close("crossover keeps center gain", 1, sineGain(p, 90, 2, 2), 1e-7);
    }

    private static void centerCopyLeavesCenterFullRange() {
        AudioProfile p = flat().toBuilder().centerBassCopyEnabled(true).centerBassCutoffHz(120).build();
        close("center original stays full range", 1, sineGain(p, 300, 2, 2), 1e-7);
        close("center copy -6dB at120Hz", 0.5, sineGain(p, 120, 2, 3), 2e-5);
        double ratio = Math.tan(Math.PI * 300 / 48000) / Math.tan(Math.PI * 120 / 48000);
        close("center copy attenuation above cutoff", 1 / (1 + Math.pow(ratio, 4)), sineGain(p, 300, 2, 3), 1e-5);
        float[] in = new float[301 * 6]; in[2] = 0.2f;
        float[] out = render(in, 6, p.toBuilder().delaySamples(2, 278).build(), 23);
        truth("center bass follows LFE delay", out[3] > 0);
        for (int f = 0; f < 278; f++) close("center original follows center delay", 0, out[f * 6 + 2], 0);
        close("center original impulse preserved", 0.2f, out[278 * 6 + 2], 0);
    }

    private static void headroomAndFiniteOutput() {
        AudioProfile p = flat().toBuilder().surroundCrossoverEnabled(true).centerBassCopyEnabled(true)
                .automaticLfeHeadroom(true).build();
        close("four-source auto mix bound", 0.25, p.getEffectiveLfeHeadroom(), 0);
        float[] in = new float[48000 * 6];
        for (int f = 0; f < 48000; f++) { in[f * 6 + 2] = 0.5f; in[f * 6 + 3] = 0.5f; in[f * 6 + 4] = 0.5f; in[f * 6 + 5] = 0.5f; }
        float[] out = render(in, 6, p, 480);
        close("coherent bass sum stays at0.5 with headroom", 0.5, out[(48000 - 1) * 6 + 3], 1e-6);
        close("manual margin available", 1f / 3f, p.toBuilder().automaticLfeHeadroom(false)
                .lfeHeadroom(1f / 3f).build().getEffectiveLfeHeadroom(), 0);
        DspEngine engine = new DspEngine(480);
        engine.setProfile(flat().toBuilder().bypass(true).channelTrim(0, 4).build());
        float[] invalid = {1, Float.NaN, Float.POSITIVE_INFINITY, Float.NEGATIVE_INFINITY, -2, 2};
        float[] block = new float[6]; engine.process(invalid, 6, block, 1);
        float[] expected = {1, 0, 0, 0, -1, 1};
        for (int c = 0; c < 6; c++) close("nonfinite guard and clamp", expected[c], block[c], 0);
        eq("count sanitized input", 3, engine.getNonFiniteInputSamples());
        eq("count clamped output", 3, engine.getClippedSamples());
        engine.setProfile(p.toBuilder().automaticLfeHeadroom(false).lfeHeadroom(1).lfeEqEnabled(true).build());
        float[] large = new float[480 * 6], finite = new float[480 * 6];
        Arrays.fill(large, Float.MAX_VALUE);
        engine.process(large, 6, finite, 480);
        for (float value : finite) truth("large finite samples do not poison filters", Float.isFinite(value) && Math.abs(value) <= 1);
        Arrays.fill(large, 0); engine.process(large, 6, finite, 480);
        for (float value : finite) truth("filter tail remains finite after extremes", Float.isFinite(value) && Math.abs(value) <= 1);
    }

    private static void profileChangesAndReset() {
        AudioProfile p = flat().toBuilder().delaySamples(0, 10).build();
        DspEngine engine = new DspEngine(480); engine.setProfile(p);
        float[] in = new float[6], out = new float[6]; in[0] = 0.5f;
        engine.process(in, 6, out, 1);
        engine.setProfile(p.toBuilder().masterGain(0.5f).muted(true).build());
        Arrays.fill(in, 0);
        for (int i = 1; i < 10; i++) engine.process(in, 6, out, 1);
        engine.setProfile(p.toBuilder().masterGain(0.5f).build()); engine.process(in, 6, out, 1);
        close("master/mute updates keep advancing delayed history", 0.25, out[0], 0);
        in[0] = 0.5f; engine.process(in, 6, out, 1); in[0] = 0;
        engine.reset(); engine.process(in, 6, out, 1);
        eq("reset clears counters at block boundary", 1, engine.getFramesProcessed());
        for (int i = 0; i < 20; i++) {
            engine.process(in, 6, out, 1); close("reset discards buffered impulse", 0, out[0], 0);
        }
        in[0] = 0.5f; engine.process(in, 6, out, 1); in[0] = 0;
        engine.setProfile(p.toBuilder().delaySamples(0, 12).build());
        for (int i = 0; i < 20; i++) {
            engine.process(in, 6, out, 1); close("delay change resets old graph history", 0, out[0], 0);
        }
    }

    private static void blockPartitionInvariance() {
        AudioProfile p = AudioProfile.defaultProfile().toBuilder().masterGain(0.7f)
                .centerBassCopyEnabled(true).delaySamples(0, 11).delaySamples(1, 23)
                .delaySamples(2, 17).delaySamples(3, 3).delaySamples(4, 19).delaySamples(5, 21).build();
        float[] in = new float[48000 * 6];
        for (int f = 0; f < 48000; f++) for (int c = 0; c < 6; c++) {
            in[f * 6 + c] = (float) (0.03 * Math.sin(2 * Math.PI * (23 + c * 17) * f / 48000));
        }
        float[] oneBlock = render(in, 6, p, 48000), chunks = render(in, 6, p, 137);
        for (int i = 0; i < chunks.length; i++) close("full graph independent of block partitions", oneBlock[i], chunks[i], 0);
    }

    private static AudioProfile flat() {
        AudioProfile.Builder b = AudioProfile.builder().masterGain(1).muted(false).bypass(false)
                .lfeEqEnabled(false).surroundCrossoverEnabled(false).centerBassCopyEnabled(false)
                .automaticLfeHeadroom(false).lfeHeadroom(1);
        for (int c = 0; c < 6; c++) b.delaySamples(c, 0);
        return b.build();
    }
    private static float[] render(float[] input, int channels, AudioProfile p, int blockFrames) {
        int total = input.length / channels;
        float[] result = new float[total * 6], inBlock = new float[blockFrames * channels], outBlock = new float[blockFrames * 6];
        DspEngine engine = new DspEngine(blockFrames); engine.setProfile(p);
        for (int f = 0; f < total; f += blockFrames) {
            int n = Math.min(blockFrames, total - f);
            System.arraycopy(input, f * channels, inBlock, 0, n * channels);
            engine.process(inBlock, channels, outBlock, n);
            System.arraycopy(outBlock, 0, result, f * 6, n * 6);
        }
        return result;
    }
    private static double sineGain(AudioProfile p, double frequency, int inputChannel, int outputChannel) {
        int frames = 96000;
        float[] input = new float[frames * 6];
        for (int f = 0; f < frames; f++) input[f * 6 + inputChannel] = (float) (0.05 * Math.sin(2 * Math.PI * frequency * f / 48000));
        float[] out = render(input, 6, p, 480);
        double inPower = 0, outPower = 0;
        for (int f = 48000; f < frames; f++) {
            inPower += input[f * 6 + inputChannel] * (double) input[f * 6 + inputChannel];
            outPower += out[f * 6 + outputChannel] * (double) out[f * 6 + outputChannel];
        }
        return Math.sqrt(outPower / inPower);
    }
    private static void rejects(String label, Runnable action) {
        boolean rejected = false;
        try { action.run(); } catch (IllegalArgumentException ex) { rejected = true; }
        truth(label, rejected);
    }
    private static void close(String label, double expected, double observed, double tolerance) {
        checks++;
        if (!Double.isFinite(observed) || Math.abs(expected - observed) > tolerance) {
            throw new AssertionError(label + ": expected " + expected + ", observed " + observed + ", tolerance " + tolerance);
        }
    }
    private static void eq(String label, long expected, long observed) {
        checks++;
        if (expected != observed) throw new AssertionError(label + ": expected " + expected + ", observed " + observed);
    }
    private static void truth(String label, boolean value) {
        checks++;
        if (!value) throw new AssertionError(label);
    }
}
