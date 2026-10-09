// Upmix is permitted only with a source channel count supplied by the decoder
// or per-stream API, before mixing. A six-slot loopback contains no such
// metadata: quiet FC/LFE/SL/SR is never evidence of a stereo source.
// Expected order: FL, FR, FC, LFE, BL/SL, BR/SR; 48 kHz float32 interleaved.
using System;

public sealed class StereoUpmix
{
    private const int Channels = 6;
    private const int SampleRate = 48000;
    private const int FadeFrames = SampleRate / 10; // 100 ms
    private float[] scratch = new float[0];
    private int verifiedSourceChannels;
    private int fadeFrames;
    private Biquad low1;
    private Biquad low2;

    public StereoUpmix()
    {
        // Match the APO LFE: two 120 Hz/Q 0.7071 stages at 48 kHz
        // (Linkwitz-Riley fourth-order approximation, -6.02 dB at 120 Hz).
        low1 = Biquad.LowPass(120.0, SampleRate, 0.7071);
        low2 = Biquad.LowPass(120.0, SampleRate, 0.7071);
    }

    public bool IsSynthesizing { get { return fadeFrames > 0; } }

    // Use at a source/mode change. Every unverified/native call does this too.
    public void Reset()
    {
        verifiedSourceChannels = 0;
        fadeFrames = 0;
        low1.Reset();
        low2.Reset();
    }

    // Compatibility entry point for legacy loopback callers. The boolean can
    // force native but cannot attest that the original source was stereo.
    // Both values therefore preserve all six slots exactly; the pre-mix APO
    // remains responsible for automatically upmixing actual 1/2-channel streams.
    public void Process(byte[] data, int byteOffset, int frameCount, bool forceNative)
    {
        Process(data, byteOffset, frameCount, 0);
    }

    // Data is a six-slot carrier. Only a trusted source/decoder count of 1 or 2
    // permits synthesis. For mono the original sample must be in slot FL; for
    // stereo it must be in FL/FR. Counts 0 (unknown), 6 and other layouts preserve
    // the carrier. Do not pass the loopback format or infer a count from energy.
    // No allocation after the largest observed packet; native needs no scratch.
    public void Process(byte[] data, int byteOffset, int frameCount, int sourceChannelCount)
    {
        if (data == null) throw new ArgumentNullException("data");
        if (byteOffset < 0 || frameCount < 0 || byteOffset > data.Length ||
            frameCount > (data.Length - byteOffset) / (Channels * sizeof(float)))
            throw new ArgumentOutOfRangeException("frameCount", "Invalid six-channel float32 buffer span");
        if (!CanSynthesize(sourceChannelCount)) { Reset(); return; }
        if (frameCount == 0) return;

        int sampleCount = checked(frameCount * Channels);
        int byteCount = checked(sampleCount * sizeof(float));
        if (scratch.Length < sampleCount) scratch = new float[sampleCount];
        Buffer.BlockCopy(data, byteOffset, scratch, 0, byteCount);
        ProcessCore(scratch, 0, frameCount, sourceChannelCount);
        Buffer.BlockCopy(scratch, 0, data, byteOffset, byteCount);
    }

    // Direct float[] interface, also in place. sampleOffset counts floats.
    public void Process(float[] data, int sampleOffset, int frameCount, bool forceNative)
    {
        Process(data, sampleOffset, frameCount, 0);
    }

    public void Process(float[] data, int sampleOffset, int frameCount, int sourceChannelCount)
    {
        if (data == null) throw new ArgumentNullException("data");
        if (sampleOffset < 0 || frameCount < 0 || sampleOffset > data.Length ||
            frameCount > (data.Length - sampleOffset) / Channels)
            throw new ArgumentOutOfRangeException("frameCount", "Invalid six-channel float32 buffer span");
        if (!CanSynthesize(sourceChannelCount)) { Reset(); return; }
        if (frameCount != 0) ProcessCore(data, sampleOffset, frameCount, sourceChannelCount);
    }

    private static bool CanSynthesize(int sourceChannelCount)
    {
        if (sourceChannelCount < 0) throw new ArgumentOutOfRangeException("sourceChannelCount");
        return sourceChannelCount == 1 || sourceChannelCount == 2;
    }

    private static float Clamp(float value)
    {
        if (value > 1f) return 1f;
        if (value < -1f) return -1f;
        return value;
    }

    private void ProcessCore(float[] data, int sampleOffset, int frameCount, int sourceChannelCount)
    {
        if (verifiedSourceChannels != sourceChannelCount)
        {
            Reset();
            verifiedSourceChannels = sourceChannelCount;
        }
        int end = sampleOffset + frameCount * Channels;
        for (int i = sampleOffset; i < end; i += Channels)
        {
            float left = data[i];
            float right = sourceChannelCount == 1 ? left : data[i + 1];
            if (sourceChannelCount == 1) data[i + 1] = left;
            // Fade newly synthesized channels over 100 ms; selection itself
            // comes only from source metadata, never channel activity.
            double mono = (Double.IsNaN(left) || Double.IsInfinity(left) ||
                           Double.IsNaN(right) || Double.IsInfinity(right))
                          ? 0.0 : 0.25 * ((double)left + right);
            float filteredLfe = (float)low2.Process(low1.Process(mono));
            if (fadeFrames < FadeFrames) fadeFrames++;
            float gain = (float)fadeFrames / FadeFrames;
            data[i + 2] = Clamp(0.5f * (left + right) * gain);
            data[i + 3] = Clamp(filteredLfe * gain);
            data[i + 4] = Clamp(0.5f * left * gain);
            data[i + 5] = Clamp(0.5f * right * gain);
        }
    }

    private struct Biquad
    {
        private double b0, b1, b2, a1, a2, z1, z2;

        public static Biquad LowPass(double hz, double sampleRate, double q)
        {
            double omega = 2.0 * Math.PI * hz / sampleRate;
            double cos = Math.Cos(omega);
            double alpha = Math.Sin(omega) / (2.0 * q);
            double a0 = 1.0 + alpha;
            Biquad result = new Biquad();
            result.b0 = (1.0 - cos) / (2.0 * a0);
            result.b1 = (1.0 - cos) / a0;
            result.b2 = result.b0;
            result.a1 = -2.0 * cos / a0;
            result.a2 = (1.0 - alpha) / a0;
            return result;
        }

        public double Process(double x)
        {
            double y = b0 * x + z1;
            z1 = b1 * x - a1 * y + z2;
            z2 = b2 * x - a2 * y;
            return y;
        }

        public void Reset()
        {
            z1 = 0.0;
            z2 = 0.0;
        }
    }
}
