// Isolated source-selection regressions. No device, decoder or player opened.
using System;
using System.Text;

public static class StereoUpmixMetadataTests
{
    private static void Assert(bool value, string reason)
    { if (!value) throw new InvalidOperationException(reason); }

    private static float[] Fronts(int frames)
    {
        float[] data = new float[frames * 6];
        for (int i = 0; i < data.Length; i += 6)
        { data[i] = 0.25f; data[i + 1] = 0.125f; }
        return data;
    }

    private static byte[] Bytes(float[] values)
    {
        byte[] data = new byte[values.Length * sizeof(float)];
        Buffer.BlockCopy(values, 0, data, 0, data.Length);
        return data;
    }

    private static void Equal(byte[] before, byte[] after, string reason)
    {
        Assert(before.Length == after.Length, reason);
        for (int i = 0; i < before.Length; i++) Assert(before[i] == after[i], reason);
    }

    public static string Run()
    {
        StringBuilder result = new StringBuilder();
        StereoUpmix upmix = new StereoUpmix();
        // A native film can use only its fronts for arbitrarily long passages.
        float[] quietNative = Fronts(48000 * 10);
        byte[] original = Bytes(quietNative);
        upmix.Process(quietNative, 0, 48000 * 10, false);
        Equal(original, Bytes(quietNative), "Legacy Auto changed front-only native 5.1");
        Assert(!upmix.IsSynthesizing, "Unknown legacy source synthesized channels");
        result.AppendLine("PASS legacy Auto: 10 seconds of front-only native 5.1 preserved byte for byte");

        foreach (int count in new int[] { 0, 3, 4, 6, 8 })
        {
            // Native preservation includes quiet slots and every float bit.
            float[] values = new float[] { .4f, -.2f, float.NaN, float.PositiveInfinity, -.1f, .7f };
            byte[] data = Bytes(values);
            byte[] before = (byte[])data.Clone();
            upmix.Process(data, 0, 1, count);
            Equal(before, data, "Non-mono/stereo metadata changed the carrier");
        }
        result.AppendLine("PASS source metadata: unknown and 3/4/6/8-channel counts never upmix");

        float[] stereo = Fronts(48000);
        upmix.Process(stereo, 0, 48000, 2);
        Assert(upmix.IsSynthesizing, "Verified stereo did not synthesize");
        int final = stereo.Length - 6;
        Assert(stereo[final] == .25f && stereo[final + 1] == .125f, "Stereo fronts changed");
        Assert(Math.Abs(stereo[final + 2] - .1875f) < 1e-6, "Stereo center gain");
        Assert(Math.Abs(stereo[final + 3] - .09375f) < 1e-6, "Stereo low-pass DC gain");
        Assert(stereo[final + 4] == .125f && stereo[final + 5] == .0625f, "Stereo surround gains");
        Assert(stereo[2] > 0 && stereo[2] < .001f, "Stereo fade start");
        result.AppendLine("PASS verified stereo: center, LR4 LFE and surrounds synthesize; fronts preserved");

        float[] afterStereo = Fronts(48000 * 2);
        original = Bytes(afterStereo);
        upmix.Process(afterStereo, 0, 48000 * 2, 6);
        Equal(original, Bytes(afterStereo), "Stereo-to-native transition changed native audio");
        Assert(!upmix.IsSynthesizing, "Native transition retained synthesis state");
        upmix.Process(afterStereo, 0, 48000 * 2, true);
        Equal(original, Bytes(afterStereo), "Forced native changed audio");
        result.AppendLine("PASS mode transition: verified stereo to native preserves the first native block and clears filters");

        float[] mono = Fronts(48000);
        upmix.Process(mono, 0, 48000, 1);
        final = mono.Length - 6;
        Assert(mono[final] == .25f && mono[final + 1] == .25f && mono[final + 2] == .25f,
            "Mono did not use verified FL input");
        Assert(Math.Abs(mono[final + 3] - .125f) < 1e-6 && mono[final + 4] == .125f && mono[final + 5] == .125f,
            "Mono LFE/surround gains");
        result.AppendLine("PASS verified mono: FL sample expands into the logical six-channel layout");

        float[] whole = Fronts(9600);
        float[] split = (float[])whole.Clone();
        new StereoUpmix().Process(whole, 0, 9600, 2);
        StereoUpmix blocks = new StereoUpmix();
        for (int frame = 0; frame < 9600; frame += 480) blocks.Process(split, frame * 6, 480, 2);
        Equal(Bytes(whole), Bytes(split), "Packet boundaries changed DSP/fade history");

        byte[] carrier = new byte[Bytes(Fronts(480)).Length + 48];
        for (int i = 0; i < carrier.Length; i++) carrier[i] = 0xA5;
        byte[] payload = Bytes(Fronts(480));
        Buffer.BlockCopy(payload, 0, carrier, 24, payload.Length);
        new StereoUpmix().Process(carrier, 24, 480, 2);
        for (int i = 0; i < 24; i++)
            Assert(carrier[i] == 0xA5 && carrier[carrier.Length - 1 - i] == 0xA5, "Byte span escaped packet");
        bool rejected = false;
        try { new StereoUpmix().Process(carrier, 24, 482, 2); }
        catch (ArgumentOutOfRangeException) { rejected = true; }
        Assert(rejected, "Out-of-range packet accepted");
        result.AppendLine("PASS packet boundaries: split processing identical; byte spans bounded");
        return result.ToString();
    }
}
