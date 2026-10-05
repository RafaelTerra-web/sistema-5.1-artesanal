// Detects six-channel PCM streams that carry only FL/FR and synthesizes the
// four otherwise empty channels. The decision is heuristic: a native 5.1
// program can have an extended passage with only FL/FR content.
// Expected order: FL, FR, FC, LFE, BL/SL, BR/SR; 48 kHz float32 interleaved.
using System;

public sealed class StereoUpmix
{
    private const int Channels = 6;
    private const int SampleRate = 48000;
    private const int GraceFrames = SampleRate * 3 / 2; // 1.5 s of front-only audio
    private const int FadeFrames = SampleRate / 10; // 100 ms
    private const int SilenceResetFrames = SampleRate * 3 / 2;
    private const float ActivityFloor = 0.00001f;
    private float[] scratch = new float[0];
    private int frontOnlyFrames;
    private int silentFrames;
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

    // Use at a source/mode change. Process(..., forceNative:true) does this too.
    public void Reset()
    {
        frontOnlyFrames = 0;
        silentFrames = 0;
        fadeFrames = 0;
        low1.Reset();
        low2.Reset();
    }

    // Convenient for RelayLoopback's byte[] tick. No allocation after the
    // largest observed packet; data is overwritten in place only if eligible.
    public void Process(byte[] data, int byteOffset, int frameCount, bool forceNative)
    {
        if (data == null) throw new ArgumentNullException("data");
        if (byteOffset < 0 || frameCount < 0 || byteOffset > data.Length ||
            frameCount > (data.Length - byteOffset) / (Channels * sizeof(float)))
            throw new ArgumentOutOfRangeException("frameCount", "Invalid six-channel float32 buffer span");
        if (forceNative) { Reset(); return; }
        if (frameCount == 0) return;

        int sampleCount = checked(frameCount * Channels);
        int byteCount = checked(sampleCount * sizeof(float));
        if (scratch.Length < sampleCount) scratch = new float[sampleCount];
        Buffer.BlockCopy(data, byteOffset, scratch, 0, byteCount);
        if (ProcessCore(scratch, 0, frameCount))
            Buffer.BlockCopy(scratch, 0, data, byteOffset, byteCount);
    }

    // Direct float[] interface, also in place. sampleOffset counts floats.
    public void Process(float[] data, int sampleOffset, int frameCount, bool forceNative)
    {
        if (data == null) throw new ArgumentNullException("data");
        if (sampleOffset < 0 || frameCount < 0 || sampleOffset > data.Length ||
            frameCount > (data.Length - sampleOffset) / Channels)
            throw new ArgumentOutOfRangeException("frameCount", "Invalid six-channel float32 buffer span");
        if (forceNative) { Reset(); return; }
        if (frameCount != 0) ProcessCore(data, sampleOffset, frameCount);
    }

    private static bool Active(float value)
    {
        return Math.Abs(value) > ActivityFloor;
    }

    private static float Clamp(float value)
    {
        if (value > 1f) return 1f;
        if (value < -1f) return -1f;
        return value;
    }

    // Conservative at buffer boundaries: if any FC/LFE/BL/BR sample is
    // active, preserve the *entire* buffer and immediately clear upmix state.
    private bool ProcessCore(float[] data, int sampleOffset, int frameCount)
    {
        int end = sampleOffset + frameCount * Channels;
        for (int i = sampleOffset; i < end; i += Channels)
        {
            if (Active(data[i + 2]) || Active(data[i + 3]) ||
                Active(data[i + 4]) || Active(data[i + 5]))
            {
                Reset();
                return false;
            }
        }

        bool modified = false;
        for (int i = sampleOffset; i < end; i += Channels)
        {
            float left = data[i];
            float right = data[i + 1];
            bool frontsActive = Active(left) || Active(right);
            if (frontsActive)
            {
                silentFrames = 0;
                if (frontOnlyFrames < GraceFrames) frontOnlyFrames++;
            }
            else
            {
                if (silentFrames < SilenceResetFrames) silentFrames++;
                if (silentFrames >= SilenceResetFrames)
                {
                    Reset();
                    continue;
                }
            }

            // Warm the filter during the grace period so the LFE has no
            // start-up transient when the synthesized channels fade in.
            double mono = (Double.IsNaN(left) || Double.IsInfinity(left) ||
                           Double.IsNaN(right) || Double.IsInfinity(right))
                          ? 0.0 : 0.25 * ((double)left + right);
            float filteredLfe = (float)low2.Process(low1.Process(mono));
            if (frontOnlyFrames < GraceFrames) continue;

            if (fadeFrames < FadeFrames) fadeFrames++;
            float gain = (float)fadeFrames / FadeFrames;
            data[i + 2] = Clamp(0.5f * (left + right) * gain);
            data[i + 3] = Clamp(filteredLfe * gain);
            data[i + 4] = Clamp(0.5f * left * gain);
            data[i + 5] = Clamp(0.5f * right * gain);
            modified = true;
        }
        return modified;
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
