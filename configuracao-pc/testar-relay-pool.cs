// Offline synthetic tests: no endpoint, mpv process, or audible output.
using System;
using System.Collections;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Reflection;
using System.Text;
using System.Threading;

public static class RelayPoolOfflineTests
{
    private const BindingFlags PrivateInstance = BindingFlags.Instance | BindingFlags.NonPublic;
    private const BindingFlags PrivateStatic = BindingFlags.Static | BindingFlags.NonPublic;
    private static readonly Type Relay = typeof(RelayLoopbackLowLatency);
    private static readonly Type StateType = Relay.GetNestedType("State", BindingFlags.NonPublic);
    private static readonly Type PacketType = Relay.GetNestedType("Packet", BindingFlags.NonPublic);
    private static readonly MethodInfo Measure = Relay.GetMethod("MeasurePacket", PrivateStatic);
    private static readonly MethodInfo Write = Relay.GetMethod("WritePacket", PrivateStatic);
    private static readonly MethodInfo Worker = Relay.GetMethod("WriterWorker", PrivateStatic);

    private static object State() { return Activator.CreateInstance(StateType, true); }
    private static object Call(object state, string method, params object[] arguments)
    { return StateType.GetMethod(method, PrivateInstance).Invoke(state, arguments); }
    private static T Field<T>(object value, string name)
    { return (T)value.GetType().GetField(name, PrivateInstance).GetValue(value); }
    private static int PoolCount(object state)
    { object pool = Field<object>(state, "Pool"); return (int)pool.GetType().GetProperty("Count").GetValue(pool, null); }
    private static byte[] Data(object packet) { return Field<byte[]>(packet, "Data"); }
    private static object Rent(object state, int count) { return Call(state, "RentPacket", count); }
    private static void Return(object state, object packet) { Call(state, "ReturnPacket", packet); }
    private static void Assert(bool condition, string message)
    { if (!condition) throw new InvalidOperationException(message); }
    private static void Native(object state)
    { StateType.GetField("ForceNative", PrivateInstance).SetValue(state, true); }

    private sealed class BlockingStream : MemoryStream
    {
        internal readonly ManualResetEvent Entered = new ManualResetEvent(false);
        internal readonly ManualResetEvent Release = new ManualResetEvent(false);
        public override void Write(byte[] buffer, int offset, int count)
        {
            Entered.Set();
            if (!Release.WaitOne(5000)) throw new TimeoutException("Test stream was not released");
            base.Write(buffer, offset, count);
        }
    }

    private sealed class ThrowingStream : MemoryStream
    {
        public override void Write(byte[] buffer, int offset, int count)
        { throw new IOException("Expected synthetic pipe failure"); }
    }

    public static string Run()
    {
        StringBuilder result = new StringBuilder();
        object state = State();
        List<object> rented = new List<object>();
        for (int i = 0; i < 17; i++) rented.Add(Rent(state, 11520));
        Assert(PoolCount(state) == 0, "Pool exhaustion");
        Assert(Field<long>(state, "PoolAllocations") == 17, "Fallback allocation count");
        for (int i = 0; i < rented.Count; i++) Return(state, rented[i]);
        Assert(PoolCount(state) == 16, "Fallback return must keep cache bounded to 16");
        result.AppendLine("PASS fallback: 17 rentals; 17 allocations; cache returns to 16");

        state = State();
        for (int i = 0; i < 100000; i++)
        { object packet = Rent(state, 11520); Return(state, packet); }
        Assert(Field<long>(state, "PoolAllocations") == 16, "Steady-state allocations");
        result.AppendLine("PASS reuse: 100000 cycles without extra packet allocations");

        state = State();
        for (int i = 0; i < 9; i++) Call(state, "Enqueue", Rent(state, 11520));
        Assert(Field<int>(state, "QueuedBytes") == 6 * 11520, "Trim target must remain 60 ms");
        Assert(Field<long>(state, "DroppedFrames") == 3 * 480, "Trim accounting");
        Assert(PoolCount(state) == 10, "Trim returns three buffers");
        Call(state, "Cancel");
        Assert(PoolCount(state) == 16 && Field<int>(state, "QueuedBytes") == 0, "Cancel drains queue");
        Call(state, "Enqueue", Rent(state, 24));
        Assert(PoolCount(state) == 16, "Capture racing with cancellation returns buffer");
        result.AppendLine("PASS trim/cancel: 80/60 ms thresholds preserved; all queued/racing buffers returned");

        state = State(); Native(state);
        object partial = Rent(state, 72);
        byte[] bytes = Data(partial);
        for (int i = 0; i < bytes.Length; i++) bytes[i] = 0xA5;
        Array.Clear(bytes, 0, Field<int>(partial, "Count"));
        Assert(bytes[71] == 0 && bytes[72] == 0xA5, "Only the valid silent span may be cleared");
        using (MemoryStream output = new MemoryStream())
        {
            Write.Invoke(null, new object[] { state, output, new StereoUpmix(), partial, false });
            Assert(output.Length == 72, "A partial packet must write Count, not buffer capacity");
            byte[] written = output.ToArray();
            for (int i = 0; i < written.Length; i++) Assert(written[i] == 0, "Partial silence output");
        }
        Return(state, partial);
        Assert(Field<long>(state, "SentFrames") == 3 && Field<long>(state, "MeteredFrames") == 3,
            "Partial frame accounting");
        result.AppendLine("PASS partial silence: 3 frames written/measured; stale unused tail excluded");

        state = State(); Native(state);
        Call(state, "Enqueue", Rent(state, 11520));
        Call(state, "Enqueue", Rent(state, 11520));
        using (BlockingStream blocking = new BlockingStream())
        {
            object blockedState = state;
            Exception threadError = null;
            Thread writer = new Thread(() =>
            { try { Worker.Invoke(null, new object[] { blockedState, blocking }); } catch (Exception ex) { threadError = ex; } });
            writer.Start();
            Assert(blocking.Entered.WaitOne(5000), "Writer did not enter stream");
            Assert(Field<long>(state, "InFlightFrames") == 480, "In-flight metric while pipe blocked");
            Call(state, "Cancel");
            Assert(PoolCount(state) == 15, "Cancel must leave only writer-owned buffer out");
            blocking.Release.Set();
            Assert(writer.Join(5000), "Writer did not stop");
            Assert(threadError == null, "Writer invocation failed");
            Assert(PoolCount(state) == 16 && Field<long>(state, "InFlightFrames") == 0, "Writer returns in-flight packet");
        }
        result.AppendLine("PASS cancel during write: inFlightFrames=480 while blocked, then 0; cache returns to 16");

        state = State(); Native(state);
        Call(state, "Enqueue", Rent(state, 11520));
        Call(state, "Enqueue", Rent(state, 11520));
        using (ThrowingStream failure = new ThrowingStream()) Worker.Invoke(null, new object[] { state, failure });
        Assert(Field<Exception>(state, "Error") is IOException, "Expected writer failure missing");
        Assert(PoolCount(state) == 16 && Field<int>(state, "QueuedBytes") == 0, "Exception packet cleanup");
        Assert(Field<long>(state, "InFlightFrames") == 0 && Field<long>(state, "WriteInProgressTimestamp") == 0,
            "Exception metric cleanup");
        Assert(Field<long>(state, "SentFrames") == 0, "Failed write cannot count as sent");
        result.AppendLine("PASS write exception: queued and writer-owned buffers returned; counters cleared");

        state = State();
        object measured = Rent(state, 24);
        float[] values = new float[] { -1.2f, 0.5f, 1.1f, -0.8f, 0, 1.5f };
        Buffer.BlockCopy(values, 0, Data(measured), 0, 24);
        byte[] original = (byte[])Data(measured).Clone();
        Measure.Invoke(null, new object[] { state, measured });
        long[] counts = Field<long[]>(state, "OverOneSamples");
        long[] peaks = Field<long[]>(state, "PeakBits");
        long[] expectedCounts = new long[] { 1, 0, 1, 0, 0, 1 };
        for (int i = 0; i < 6; i++)
        {
            Assert(counts[i] == expectedCounts[i], "Per-channel over-one count");
            Assert(Math.Abs(BitConverter.Int64BitsToDouble(peaks[i]) - Math.Abs(values[i])) < 0.000001,
                "Per-channel peak");
        }
        for (int i = 0; i < original.Length; i++) Assert(original[i] == Data(measured)[i], "Meter must not alter signal");
        Return(state, measured);
        result.AppendLine("PASS meter: peaks/over-one counts correct for all channels; bytes untouched");

        state = State();
        object benchmark = Rent(state, 11520);
        float[] pattern = new float[2880];
        for (int i = 0; i < pattern.Length; i++) pattern[i] = (float)Math.Sin(i * 0.03) * 0.8f;
        Buffer.BlockCopy(pattern, 0, Data(benchmark), 0, 11520);
        for (int i = 0; i < 1000; i++) Measure.Invoke(null, new object[] { state, benchmark });
        StateType.GetField("MeterTicks", PrivateInstance).SetValue(state, 0L);
        StateType.GetField("MaxMeterTicks", PrivateInstance).SetValue(state, 0L);
        StateType.GetField("MeteredFrames", PrivateInstance).SetValue(state, 0L);
        for (int i = 0; i < 20000; i++) Measure.Invoke(null, new object[] { state, benchmark });
        double costMs = Field<long>(state, "MeterTicks") * 1000.0 / Stopwatch.Frequency / 20000;
        double maximumMs = Field<long>(state, "MaxMeterTicks") * 1000.0 / Stopwatch.Frequency;
        result.AppendLine("METER meanMsPer10ms=" + costMs.ToString("F6", System.Globalization.CultureInfo.InvariantCulture) +
            " maxMs=" + maximumMs.ToString("F6", System.Globalization.CultureInfo.InvariantCulture));
        Return(state, benchmark);
        result.AppendLine("PASS all synthetic tests; no endpoint/process/audio used");
        return result.ToString();
    }
}
