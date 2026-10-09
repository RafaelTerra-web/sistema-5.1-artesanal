package br.com.sistema51.a34.dsp;

import java.io.BufferedOutputStream;
import java.io.File;
import java.io.FileOutputStream;
import java.io.IOException;
import java.io.OutputStream;
import java.nio.charset.StandardCharsets;

/** Deterministic export harness for independent mpv/FFmpeg graph comparisons. */
public final class DspGoldenVectors {
    private static final int FRAMES = 192000;

    public static void main(String[] args) throws IOException {
        if (args.length != 1) throw new IllegalArgumentException("Usage: DspGoldenVectors outputDirectory");
        File directory = new File(args[0]);
        if (!directory.isDirectory() && !directory.mkdirs()) throw new IOException("Cannot create output directory");
        float[] nativeInput = new float[FRAMES * 6], stereoInput = new float[FRAMES * 2];
        for (int f = 0; f < FRAMES; f++) {
            // First3 seconds contain different tones per channel; final1 second
            // is silence to flush delays and most IIR tails within the same span.
            if (f >= 144000) continue;
            double envelope = f < 480 ? f / 480.0 : f > 143520 ? (144000 - f) / 480.0 : 1;
            for (int c = 0; c < 6; c++) {
                double frequency = 20 + 17 * c;
                nativeInput[f * 6 + c] = (float) (envelope * (0.04 * Math.sin(2 * Math.PI * frequency * f / 48000)
                        + 0.025 * Math.sin(2 * Math.PI * (frequency + 211) * f / 48000)));
            }
            stereoInput[f * 2] = (float) (envelope * (0.06 * Math.sin(2 * Math.PI * 50 * f / 48000)
                    + 0.03 * Math.sin(2 * Math.PI * 401 * f / 48000)));
            stereoInput[f * 2 + 1] = (float) (envelope * (0.05 * Math.sin(2 * Math.PI * 80 * f / 48000)
                    + 0.03 * Math.sin(2 * Math.PI * 701 * f / 48000)));
        }
        writeFloat(new File(directory, "native-input.f32le"), nativeInput);
        writeFloat(new File(directory, "stereo-input.f32le"), stereoInput);
        AudioProfile manual = AudioProfile.defaultProfile().toBuilder().masterGain(0.25f)
                .automaticLfeHeadroom(false).lfeHeadroom(1f / 3f).build();
        AudioProfile auto = manual.toBuilder().automaticLfeHeadroom(true).build();
        AudioProfile center = manual.toBuilder().centerBassCopyEnabled(true).lfeHeadroom(0.25f).build();
        AudioProfile stereo = manual.toBuilder().inputMode(AudioProfile.InputMode.STEREO_UPMIX).build();
        StringBuilder manifest = new StringBuilder("{\n  \"sampleRate\":48000,\n  \"inputFrames\":192000,\n  \"outputFrames\":192000,\n  \"layout\":[\"FL\",\"FR\",\"FC\",\"LFE\",\"SL\",\"SR\"],\n  \"vectors\":[\n");
        export(directory, "native-manual", "native-input.f32le", nativeInput, 6, manual, manifest, true);
        export(directory, "native-auto", "native-input.f32le", nativeInput, 6, auto, manifest, true);
        export(directory, "native-center", "native-input.f32le", nativeInput, 6, center, manifest, true);
        export(directory, "stereo-upmix", "stereo-input.f32le", stereoInput, 2, stereo, manifest, false);
        manifest.append("\n  ]\n}\n");
        try (OutputStream out = new FileOutputStream(new File(directory, "manifest.json"))) {
            out.write(manifest.toString().getBytes(StandardCharsets.UTF_8));
        }
        System.out.println("Exported 4 DSP vectors + 2 PCM inputs to " + directory.getAbsolutePath());
    }

    private static void export(File directory, String name, String inputFile, float[] input, int channels,
                               AudioProfile profile, StringBuilder manifest, boolean comma) throws IOException {
        float[] result = new float[FRAMES * 6], inBlock = new float[480 * channels], outBlock = new float[480 * 6];
        DspEngine engine = new DspEngine(480); engine.setProfile(profile);
        for (int f = 0; f < FRAMES; f += 480) {
            System.arraycopy(input, f * channels, inBlock, 0, 480 * channels);
            engine.process(inBlock, channels, outBlock, 480);
            System.arraycopy(outBlock, 0, result, f * 6, 480 * 6);
        }
        writeFloat(new File(directory, name + "-output.f32le"), result);
        manifest.append("    {\"name\":\"").append(name).append("\",\"input\":\"").append(inputFile)
                .append("\",\"output\":\"").append(name).append("-output.f32le\",\"inputChannels\":")
                .append(channels).append(",\"masterGain\":0.25,\"delaysSamples\":[3686,3686,278,278,3408,3408],")
                .append("\"surroundCutoffHz\":90,\"surroundSend\":1,\"centerCopyEnabled\":")
                .append(profile.isCenterBassCopyEnabled()).append(",\"centerCutoffHz\":120,\"centerSend\":1,")
                .append("\"eqFrequencyHz\":[20,25,30,40,50,60,80,100,120],")
                .append("\"eqGainDb\":[6,6,6,5.5,1.5,-4,1,1,-2.5],\"eqQ\":2,")
                .append("\"effectiveLfeHeadroom\":").append(profile.getEffectiveLfeHeadroom())
                .append(",\"clippedSamples\":").append(engine.getClippedSamples()).append("}");
        if (comma) manifest.append(",\n");
    }

    private static void writeFloat(File file, float[] values) throws IOException {
        try (OutputStream out = new BufferedOutputStream(new FileOutputStream(file))) {
            byte[] bytes = new byte[4];
            for (float value : values) {
                int bits = Float.floatToIntBits(value);
                bytes[0] = (byte) bits; bytes[1] = (byte) (bits >>> 8);
                bytes[2] = (byte) (bits >>> 16); bytes[3] = (byte) (bits >>> 24);
                out.write(bytes);
            }
        }
    }
}
