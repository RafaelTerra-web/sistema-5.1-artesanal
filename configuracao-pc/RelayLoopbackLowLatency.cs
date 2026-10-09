// Low-latency WASAPI loopback -> WAV/float32 stdin relay for the CABLE render endpoint.
// Requires RelayLoopback.cs for the existing COM declarations and StereoUpmix.cs.
// Does not capture to disk. The WAV data length is unknown (0xFFFFFFFF).
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

public static class RelayLoopbackLowLatency
{
    private const int Channels = 6;
    private const int SampleRate = 48000;
    private const int FrameBytes = Channels * sizeof(float);
    private const int ChunkFrames = 480; // 10 ms; WAV demuxer max_size can match this.
    private const int PoolCapacity = 16;
    private const int MaxQueueBytes = ChunkFrames * FrameBytes * 8; // 80 ms
    private const int TrimQueueBytes = ChunkFrames * FrameBytes * 6; // 60 ms
    private const int LoopbackFlag = 0x00020000;
    private const int EventCallbackFlag = 0x00040000;
    private const uint DiscontinuityFlag = 0x1;
    private const uint SilentFlag = 0x2;
    private const uint TimestampErrorFlag = 0x4;
    private static readonly Guid AudioClientId = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
    private static readonly Guid CaptureClientId = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
    private static readonly Guid FloatSubformat = new Guid("00000003-0000-0010-8000-00AA00389B71");

    [DllImport("avrt.dll", CharSet = CharSet.Unicode, SetLastError = true)]
    private static extern IntPtr AvSetMmThreadCharacteristics(string taskName, out uint taskIndex);

    [DllImport("avrt.dll", SetLastError = true)]
    [return: MarshalAs(UnmanagedType.Bool)]
    private static extern bool AvRevertMmThreadCharacteristics(IntPtr handle);

    private sealed class Packet
    {
        internal readonly byte[] Data = new byte[ChunkFrames * FrameBytes];
        internal int Count;
        internal bool Rented;
    }

    private sealed class State
    {
        internal readonly object Gate = new object();
        internal readonly Queue<Packet> Queue = new Queue<Packet>(PoolCapacity);
        internal readonly Stack<Packet> Pool = new Stack<Packet>(PoolCapacity);
        internal readonly float[] MeterScratch = new float[ChunkFrames * Channels];
        internal readonly long[] PeakBits = new long[Channels];
        internal readonly long[] OverOneSamples = new long[Channels];
        internal bool Cancelled;
        internal Exception Error;
        internal volatile bool ForceNative;
        internal volatile bool AutoUpmix;
        internal volatile bool CaptureMmcss;
        internal volatile bool WriterMmcss;
        internal int QueuedBytes;
        internal int QueueHighBytes;
        internal long CapturedFrames;
        internal long SentFrames;
        internal long PaddingSilenceFrames;
        internal long DroppedFrames;
        internal long Discontinuities;
        internal long TimestampErrors;
        internal long LastCaptureTimestamp;
        internal long MaxCaptureGapTicks;
        internal long MaxWriteTicks;
        internal long WriteInProgressTimestamp;
        internal long AutoUpmixFrames;
        internal long ForceNativeFrames;
        internal long PerStreamFrames;
        internal long EventWakeups;
        internal long PollWakeups;
        internal long DefaultPeriodTicks100ns;
        internal long CaptureBufferFrames;
        internal long PoolAllocations;
        internal long InFlightFrames;
        internal long MeterTicks;
        internal long MaxMeterTicks;
        internal long MeteredFrames;

        internal State()
        {
            for (int i = 0; i < PoolCapacity; i++) Pool.Push(new Packet());
            PoolAllocations = PoolCapacity;
        }

        internal bool IsCancelled { get { lock (Gate) return Cancelled; } }
        internal Exception GetError() { lock (Gate) return Error; }

        internal void Cancel()
        {
            lock (Gate)
            {
                Cancelled = true;
                DrainQueueLocked();
                Monitor.PulseAll(Gate);
            }
        }

        internal void Fail(Exception exception)
        {
            lock (Gate)
            {
                if (Error == null) Error = exception;
                Cancelled = true;
                DrainQueueLocked();
                Monitor.PulseAll(Gate);
            }
        }

        internal Packet RentPacket(int count)
        {
            if (count <= 0 || count > ChunkFrames * FrameBytes || count % FrameBytes != 0)
                throw new ArgumentOutOfRangeException("count");
            lock (Gate)
            {
                Packet packet;
                if (Pool.Count > 0) packet = Pool.Pop();
                else
                {
                    // Capture must not wait behind a stalled pipe. The cache stays
                    // bounded; an emergency allocation is visible in the metrics.
                    packet = new Packet();
                    Interlocked.Increment(ref PoolAllocations);
                }
                packet.Count = count;
                packet.Rented = true;
                return packet;
            }
        }

        private void ReturnPacketLocked(Packet packet)
        {
            if (packet == null || !packet.Rented)
                throw new InvalidOperationException("Packet was not rented or was already returned");
            packet.Rented = false;
            packet.Count = 0;
            if (Pool.Count < PoolCapacity) Pool.Push(packet);
        }

        internal void ReturnPacket(Packet packet)
        {
            lock (Gate) ReturnPacketLocked(packet);
        }

        private void DrainQueueLocked()
        {
            while (Queue.Count > 0) ReturnPacketLocked(Queue.Dequeue());
            QueuedBytes = 0;
        }

        internal void Enqueue(Packet packet)
        {
            lock (Gate)
            {
                if (Cancelled) { ReturnPacketLocked(packet); return; }
                Queue.Enqueue(packet);
                QueuedBytes += packet.Count;
                if (QueuedBytes > QueueHighBytes) QueueHighBytes = QueuedBytes;
                if (QueuedBytes > MaxQueueBytes)
                {
                    while (QueuedBytes > TrimQueueBytes && Queue.Count > 0)
                    {
                        Packet old = Queue.Dequeue();
                        QueuedBytes -= old.Count;
                        Interlocked.Add(ref DroppedFrames, old.Count / FrameBytes);
                        ReturnPacketLocked(old);
                    }
                }
                Monitor.Pulse(Gate);
            }
        }

        internal bool DequeueOrWait(int milliseconds, out Packet packet)
        {
            lock (Gate)
            {
                if (Queue.Count == 0 && !Cancelled) Monitor.Wait(Gate, milliseconds);
                if (Cancelled || Queue.Count == 0) { packet = null; return false; }
                packet = Queue.Dequeue();
                QueuedBytes -= packet.Count;
                return true;
            }
        }
    }

    private static void Require(int hr, string operation)
    {
        if (hr < 0)
            throw new COMException(operation + " failed (0x" + unchecked((uint)hr).ToString("X8") + ")", hr);
    }

    private static void UpdateMax(ref long destination, long value)
    {
        long old;
        while (value > (old = Interlocked.Read(ref destination)) &&
               Interlocked.CompareExchange(ref destination, value, old) != old) { }
    }

    private static string Quote(string value)
    {
        if (value.Length > 0 && value.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '"' }) < 0)
            return value;
        StringBuilder result = new StringBuilder("\"");
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"') { result.Append('\\', slashes * 2 + 1); result.Append('"'); slashes = 0; continue; }
            result.Append('\\', slashes); slashes = 0; result.Append(c);
        }
        result.Append('\\', slashes * 2); result.Append('"');
        return result.ToString();
    }

    private static void VerifyMixFormat(IntPtr format)
    {
        if (format == IntPtr.Zero) throw new InvalidOperationException("GetMixFormat returned no format");
        int tag = (ushort)Marshal.ReadInt16(format, 0);
        int channels = (ushort)Marshal.ReadInt16(format, 2);
        int rate = Marshal.ReadInt32(format, 4);
        int alignment = (ushort)Marshal.ReadInt16(format, 12);
        int bits = (ushort)Marshal.ReadInt16(format, 14);
        int cbSize = (ushort)Marshal.ReadInt16(format, 16);
        bool isFloat = tag == 3;
        if (tag == 0xFFFE && cbSize >= 22)
        {
            byte[] bytes = new byte[16];
            Marshal.Copy(IntPtr.Add(format, 24), bytes, 0, 16);
            isFloat = new Guid(bytes) == FloatSubformat;
        }
        if (channels != Channels || rate != SampleRate || bits != 32 || alignment != FrameBytes || !isFloat)
            throw new InvalidOperationException("Expected CABLE mix 6ch/48k/float32, observed tag=" + tag +
                " channels=" + channels + " rate=" + rate + " bits=" + bits + " blockAlign=" + alignment);
    }

    private static void WriteWaveHeader(Stream output)
    {
        BinaryWriter writer = new BinaryWriter(output, Encoding.ASCII, true);
        writer.Write(Encoding.ASCII.GetBytes("RIFF"));
        writer.Write(uint.MaxValue);
        writer.Write(Encoding.ASCII.GetBytes("WAVE"));
        writer.Write(Encoding.ASCII.GetBytes("fmt "));
        writer.Write((uint)40); // WAVEFORMATEXTENSIBLE
        writer.Write((ushort)0xFFFE);
        writer.Write((ushort)Channels);
        writer.Write((uint)SampleRate);
        writer.Write((uint)(SampleRate * FrameBytes));
        writer.Write((ushort)FrameBytes);
        writer.Write((ushort)32);
        writer.Write((ushort)22); // extension size
        writer.Write((ushort)32); // valid bits
        writer.Write((uint)0x3F); // FL FR FC LFE BL BR
        writer.Write(FloatSubformat.ToByteArray());
        writer.Write(Encoding.ASCII.GetBytes("data"));
        writer.Write(uint.MaxValue);
        writer.Flush();
    }

    private static void CaptureWorker(State state, string endpointId)
    {
        IntPtr mmcss = IntPtr.Zero;
        IRelayMMDeviceEnumerator enumerator = null;
        IRelayMMDevice device = null;
        IRelayAudioClient audio = null;
        IRelayCaptureClient capture = null;
        IntPtr audioPtr = IntPtr.Zero;
        IntPtr capturePtr = IntPtr.Zero;
        IntPtr mix = IntPtr.Zero;
        bool started = false;
        try
        {
            uint taskIndex;
            mmcss = AvSetMmThreadCharacteristics("Audio", out taskIndex);
            state.CaptureMmcss = mmcss != IntPtr.Zero;
            enumerator = (IRelayMMDeviceEnumerator)new RelayMMDeviceEnumeratorClass();
            Require(enumerator.GetDevice(endpointId, out device), "GetDevice");
            int deviceState;
            Require(device.GetState(out deviceState), "GetState");
            if (deviceState != 1) throw new InvalidOperationException("CABLE endpoint inactive: state=" + deviceState);
            Guid audioIid = AudioClientId;
            Require(device.Activate(ref audioIid, 23, IntPtr.Zero, out audioPtr), "Activate IAudioClient");
            audio = (IRelayAudioClient)Marshal.GetTypedObjectForIUnknown(audioPtr, typeof(IRelayAudioClient));
            Require(audio.GetMixFormat(out mix), "GetMixFormat");
            VerifyMixFormat(mix);
            long normalPeriod, minimumPeriod;
            Require(audio.GetDevicePeriod(out normalPeriod, out minimumPeriod), "GetDevicePeriod");
            state.DefaultPeriodTicks100ns = normalPeriod;
            Require(audio.Initialize(0, LoopbackFlag | EventCallbackFlag, 0, 0, mix, IntPtr.Zero), "Initialize event loopback");
            uint bufferFrames;
            Require(audio.GetBufferSize(out bufferFrames), "GetBufferSize");
            state.CaptureBufferFrames = bufferFrames;
            using (AutoResetEvent ready = new AutoResetEvent(false))
            {
                Require(audio.SetEventHandle(ready.SafeWaitHandle.DangerousGetHandle()), "SetEventHandle");
                Guid captureIid = CaptureClientId;
                Require(audio.GetService(ref captureIid, out capturePtr), "GetService IAudioCaptureClient");
                capture = (IRelayCaptureClient)Marshal.GetTypedObjectForIUnknown(capturePtr, typeof(IRelayCaptureClient));
                Require(audio.Start(), "Start loopback");
                started = true;
                Interlocked.Exchange(ref state.LastCaptureTimestamp, Stopwatch.GetTimestamp());
                while (!state.IsCancelled)
                {
                    // Windows 10+ signals loopback events; 20 ms timeout also
                    // drains packets if a virtual driver misses an event.
                    if (ready.WaitOne(20)) Interlocked.Increment(ref state.EventWakeups);
                    else Interlocked.Increment(ref state.PollWakeups);
                    uint available;
                    Require(capture.GetNextPacketSize(out available), "GetNextPacketSize");
                    while (available > 0 && !state.IsCancelled)
                    {
                        IntPtr data;
                        uint frames, flags;
                        ulong position, qpc;
                        Require(capture.GetBuffer(out data, out frames, out flags, out position, out qpc), "GetBuffer");
                        try
                        {
                            if ((flags & DiscontinuityFlag) != 0) Interlocked.Increment(ref state.Discontinuities);
                            if ((flags & TimestampErrorFlag) != 0) Interlocked.Increment(ref state.TimestampErrors);
                            int remaining = checked((int)frames);
                            int copied = 0;
                            while (remaining > 0)
                            {
                                int partFrames = Math.Min(remaining, ChunkFrames);
                                Packet packet = state.RentPacket(partFrames * FrameBytes);
                                try
                                {
                                    if ((flags & SilentFlag) == 0 && data != IntPtr.Zero)
                                        Marshal.Copy(IntPtr.Add(data, copied * FrameBytes), packet.Data, 0, packet.Count);
                                    else Array.Clear(packet.Data, 0, packet.Count);
                                    state.Enqueue(packet);
                                    packet = null; // queue now owns it, including cancellation/trim.
                                }
                                finally { if (packet != null) state.ReturnPacket(packet); }
                                copied += partFrames;
                                remaining -= partFrames;
                            }
                            Interlocked.Add(ref state.CapturedFrames, frames);
                            long now = Stopwatch.GetTimestamp();
                            long previous = Interlocked.Exchange(ref state.LastCaptureTimestamp, now);
                            if (previous != 0) UpdateMax(ref state.MaxCaptureGapTicks, now - previous);
                        }
                        finally { Require(capture.ReleaseBuffer(frames), "ReleaseBuffer"); }
                        Require(capture.GetNextPacketSize(out available), "GetNextPacketSize");
                    }
                }
            }
        }
        catch (Exception exception) { state.Fail(exception); }
        finally
        {
            if (started && audio != null) { try { audio.Stop(); } catch { } }
            if (capture != null) Marshal.ReleaseComObject(capture);
            if (capturePtr != IntPtr.Zero) Marshal.Release(capturePtr);
            if (mix != IntPtr.Zero) Marshal.FreeCoTaskMem(mix);
            if (audio != null) Marshal.ReleaseComObject(audio);
            if (audioPtr != IntPtr.Zero) Marshal.Release(audioPtr);
            if (device != null) Marshal.ReleaseComObject(device);
            if (enumerator != null) Marshal.ReleaseComObject(enumerator);
            if (mmcss != IntPtr.Zero) AvRevertMmThreadCharacteristics(mmcss);
        }
    }

    private static void UpdatePeak(ref long destination, double value)
    {
        long old;
        while (value > BitConverter.Int64BitsToDouble(old = Interlocked.Read(ref destination)) &&
               Interlocked.CompareExchange(ref destination, BitConverter.DoubleToInt64Bits(value), old) != old) { }
    }

    private static void MeasurePacket(State state, Packet packet)
    {
        long before = Stopwatch.GetTimestamp();
        Buffer.BlockCopy(packet.Data, 0, state.MeterScratch, 0, packet.Count);
        int samples = packet.Count / sizeof(float);
        for (int channel = 0; channel < Channels; channel++)
        {
            double peak = 0;
            long overOne = 0;
            for (int sample = channel; sample < samples; sample += Channels)
            {
                double absolute = Math.Abs(state.MeterScratch[sample]);
                if (absolute > peak) peak = absolute;
                if (absolute > 1.0) overOne++;
            }
            UpdatePeak(ref state.PeakBits[channel], peak);
            if (overOne != 0) Interlocked.Add(ref state.OverOneSamples[channel], overOne);
        }
        long elapsed = Stopwatch.GetTimestamp() - before;
        Interlocked.Add(ref state.MeterTicks, elapsed);
        UpdateMax(ref state.MaxMeterTicks, elapsed);
        Interlocked.Add(ref state.MeteredFrames, packet.Count / FrameBytes);
    }

    private static void WritePacket(State state, Stream output, StereoUpmix upmix,
                                    Packet packet, bool padding)
    {
        int frames = packet.Count / FrameBytes;
        Interlocked.Exchange(ref state.InFlightFrames, frames);
        try
        {
            bool native = state.ForceNative;
            upmix.Process(packet.Data, 0, frames, native);
            state.AutoUpmix = upmix.IsSynthesizing;
            MeasurePacket(state, packet); // observation only; no gain/limiter/EQ.
            long before = Stopwatch.GetTimestamp();
            Interlocked.Exchange(ref state.WriteInProgressTimestamp, before);
            try { output.Write(packet.Data, 0, packet.Count); }
            finally
            {
                UpdateMax(ref state.MaxWriteTicks, Stopwatch.GetTimestamp() - before);
                Interlocked.Exchange(ref state.WriteInProgressTimestamp, 0);
            }
            Interlocked.Add(ref state.SentFrames, frames);
            if (padding) Interlocked.Add(ref state.PaddingSilenceFrames, frames);
            if (native) Interlocked.Add(ref state.ForceNativeFrames, frames);
            else if (upmix.IsSynthesizing) Interlocked.Add(ref state.AutoUpmixFrames, frames);
            else Interlocked.Add(ref state.PerStreamFrames, frames);
        }
        finally { Interlocked.Exchange(ref state.InFlightFrames, 0); }
    }

    private static void WriterWorker(State state, Stream output)
    {
        IntPtr mmcss = IntPtr.Zero;
        try
        {
            uint taskIndex;
            mmcss = AvSetMmThreadCharacteristics("Audio", out taskIndex);
            state.WriterMmcss = mmcss != IntPtr.Zero;
            StereoUpmix upmix = new StereoUpmix();
            Packet idleSilence = new Packet();
            idleSilence.Count = ChunkFrames * FrameBytes;
            long nextIdleWrite = 0;
            while (!state.IsCancelled)
            {
                Packet packet;
                if (state.DequeueOrWait(10, out packet))
                {
                    try { WritePacket(state, output, upmix, packet, false); }
                    finally { state.ReturnPacket(packet); }
                    nextIdleWrite = 0;
                    continue;
                }
                if (state.IsCancelled) break;
                long now = Stopwatch.GetTimestamp();
                long sinceCapture = now - Interlocked.Read(ref state.LastCaptureTimestamp);
                if (sinceCapture < Stopwatch.Frequency * 40 / 1000) continue;
                if (nextIdleWrite == 0 || now >= nextIdleWrite)
                {
                    // The source has truly stopped. Keep HDMI AC-3 alive,
                    // without padding an ordinary empty capture poll.
                    Array.Clear(idleSilence.Data, 0, idleSilence.Count);
                    WritePacket(state, output, upmix, idleSilence, true);
                    // Advance the deadline, not "now + 10ms": a timer wakeup
                    // that arrives late must not lower the silence sample rate.
                    long afterWrite = Stopwatch.GetTimestamp();
                    if (nextIdleWrite == 0) nextIdleWrite = afterWrite;
                    nextIdleWrite += Stopwatch.Frequency / 100;
                    while (nextIdleWrite <= afterWrite && !state.IsCancelled)
                    {
                        Array.Clear(idleSilence.Data, 0, idleSilence.Count);
                        WritePacket(state, output, upmix, idleSilence, true);
                        nextIdleWrite += Stopwatch.Frequency / 100;
                    }
                }
            }
        }
        catch (Exception exception) { if (!state.IsCancelled) state.Fail(exception); }
        finally { if (mmcss != IntPtr.Zero) AvRevertMmThreadCharacteristics(mmcss); }
    }

    private static void Log(string path, string message)
    {
        if (File.Exists(path) && new FileInfo(path).Length > 2097152)
        {
            if (File.Exists(path + ".old")) File.Delete(path + ".old");
            File.Move(path, path + ".old");
        }
        File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " " + message + Environment.NewLine);
    }

    private static void LogMetrics(State state, string path)
    {
        int queueBytes, highBytes;
        lock (state.Gate) { queueBytes = state.QueuedBytes; highBytes = state.QueueHighBytes; state.QueueHighBytes = queueBytes; }
        long writeStarted = Interlocked.Read(ref state.WriteInProgressTimestamp);
        long meteredFrames = Interlocked.Read(ref state.MeteredFrames);
        StringBuilder peaks = new StringBuilder();
        StringBuilder overOne = new StringBuilder();
        string[] channelNames = new string[] { "FL", "FR", "CEN", "LFE", "SL", "SR" };
        for (int channel = 0; channel < Channels; channel++)
        {
            if (channel != 0) { peaks.Append(','); overOne.Append(','); }
            peaks.Append(channelNames[channel]).Append(':').Append(
                BitConverter.Int64BitsToDouble(Interlocked.Read(ref state.PeakBits[channel])).ToString("F6", CultureInfo.InvariantCulture));
            overOne.Append(channelNames[channel]).Append(':').Append(Interlocked.Read(ref state.OverOneSamples[channel]));
        }
        string mode = state.ForceNative ? "native" : (state.AutoUpmix ? "fronts-auto" : "per-stream");
        Log(path, "capturedFrames=" + Interlocked.Read(ref state.CapturedFrames) +
            " sentFrames=" + Interlocked.Read(ref state.SentFrames) +
            " paddingSilenceFrames=" + Interlocked.Read(ref state.PaddingSilenceFrames) +
            " droppedFrames=" + Interlocked.Read(ref state.DroppedFrames) +
            " queueMs=" + (queueBytes * 1000.0 / (FrameBytes * SampleRate)).ToString("F1") +
            " queueHighMs=" + (highBytes * 1000.0 / (FrameBytes * SampleRate)).ToString("F1") +
            " discontinuities=" + Interlocked.Read(ref state.Discontinuities) +
            " timestampErrors=" + Interlocked.Read(ref state.TimestampErrors) +
            " maxWriteMs=" + (Interlocked.Read(ref state.MaxWriteTicks) * 1000.0 / Stopwatch.Frequency).ToString("F1") +
            " writeInProgressMs=" + (writeStarted == 0 ? 0.0 :
                (Stopwatch.GetTimestamp() - writeStarted) * 1000.0 / Stopwatch.Frequency).ToString("F1") +
            " maxCaptureGapMs=" + (Interlocked.Read(ref state.MaxCaptureGapTicks) * 1000.0 / Stopwatch.Frequency).ToString("F1") +
            " eventWakeups=" + Interlocked.Read(ref state.EventWakeups) +
            " pollWakeups=" + Interlocked.Read(ref state.PollWakeups) +
            " autoFrames=" + Interlocked.Read(ref state.AutoUpmixFrames) +
            " nativeFrames=" + Interlocked.Read(ref state.ForceNativeFrames) +
            " perStreamFrames=" + Interlocked.Read(ref state.PerStreamFrames) +
            " captureMmcss=" + state.CaptureMmcss +
            " writerMmcss=" + state.WriterMmcss +
            " capturePeriodMs=" + (Interlocked.Read(ref state.DefaultPeriodTicks100ns) / 10000.0).ToString("F1") +
            " captureBufferFrames=" + Interlocked.Read(ref state.CaptureBufferFrames) +
            " poolAllocations=" + Interlocked.Read(ref state.PoolAllocations) +
            " inFlightFrames=" + Interlocked.Read(ref state.InFlightFrames) +
            " meterMsPer10ms=" + (meteredFrames == 0 ? 0.0 :
                Interlocked.Read(ref state.MeterTicks) * 1000.0 * ChunkFrames / (Stopwatch.Frequency * (double)meteredFrames)).ToString("F4", CultureInfo.InvariantCulture) +
            " maxMeterMs=" + (Interlocked.Read(ref state.MaxMeterTicks) * 1000.0 / Stopwatch.Frequency).ToString("F4", CultureInfo.InvariantCulture) +
            " peakAbs=" + peaks +
            " overOneSamples=" + overOne +
            " mode=" + mode);
    }

    /// Read-only format preflight, without initializing capture or starting a player.
    public static void VerifySourceFormat(string endpointId)
    {
        IRelayMMDeviceEnumerator enumerator = null;
        IRelayMMDevice device = null;
        IRelayAudioClient client = null;
        IntPtr clientPointer = IntPtr.Zero, format = IntPtr.Zero;
        try
        {
            enumerator = (IRelayMMDeviceEnumerator)new RelayMMDeviceEnumeratorClass();
            Require(enumerator.GetDevice(endpointId, out device), "Preflight GetDevice");
            int state;
            Require(device.GetState(out state), "Preflight GetState");
            if (state != 1) throw new InvalidOperationException("Capture source is not active");
            Guid iid = AudioClientId;
            Require(device.Activate(ref iid, 23, IntPtr.Zero, out clientPointer), "Preflight Activate");
            client = (IRelayAudioClient)Marshal.GetTypedObjectForIUnknown(clientPointer, typeof(IRelayAudioClient));
            Require(client.GetMixFormat(out format), "Preflight GetMixFormat");
            VerifyMixFormat(format);
        }
        finally
        {
            if (format != IntPtr.Zero) Marshal.FreeCoTaskMem(format);
            if (client != null) Marshal.ReleaseComObject(client);
            if (clientPointer != IntPtr.Zero) Marshal.Release(clientPointer);
            if (device != null) Marshal.ReleaseComObject(device);
            if (enumerator != null) Marshal.ReleaseComObject(enumerator);
        }
    }

    public static void Run(string endpointId, string mpvPath, string configPath, string logPath, string stopFile)
    {
        if (String.IsNullOrWhiteSpace(endpointId) || String.IsNullOrWhiteSpace(mpvPath) ||
            String.IsNullOrWhiteSpace(configPath) || String.IsNullOrWhiteSpace(logPath) || String.IsNullOrWhiteSpace(stopFile))
            throw new ArgumentException("All five paths/IDs are required");
        if (File.Exists(stopFile)) throw new IOException("Stop sentinel already exists: " + stopFile);
        if (!File.Exists(mpvPath)) throw new FileNotFoundException("mpv executable missing", mpvPath);
        if (!File.Exists(configPath)) throw new FileNotFoundException("mpv config missing", configPath);
        File.WriteAllText(logPath, "");

        State state = new State();
        state.LastCaptureTimestamp = Stopwatch.GetTimestamp();
        string directory = Path.GetDirectoryName(logPath);
        string nativeModeFile = Path.Combine(directory, "audio-sistema.nativo");
        string nativeTestFile = Path.Combine(directory, "audio-sistema.teste-nativo");
        state.ForceNative = File.Exists(nativeModeFile) ||
            (File.Exists(nativeTestFile) && DateTime.UtcNow - File.GetLastWriteTimeUtc(nativeTestFile) < TimeSpan.FromMinutes(5));
        Process player = null;
        Thread captureThread = null;
        Thread writerThread = null;
        try
        {
            ProcessStartInfo psi = new ProcessStartInfo(mpvPath);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardInput = true;
            psi.Arguments = "--no-config --include=" + Quote(configPath) +
                            " --no-video --log-file=" + Quote(logPath + ".mpv.log") + " -";
            player = Process.Start(psi);
            if (player == null) throw new InvalidOperationException("Could not start mpv");
            WriteWaveHeader(player.StandardInput.BaseStream);

            writerThread = new Thread(() => WriterWorker(state, player.StandardInput.BaseStream));
            writerThread.Name = "Audio 5.1 WAV writer";
            writerThread.IsBackground = true;
            writerThread.Priority = ThreadPriority.AboveNormal;
            writerThread.SetApartmentState(ApartmentState.MTA);
            captureThread = new Thread(() => CaptureWorker(state, endpointId));
            captureThread.Name = "Audio 5.1 WASAPI capture";
            captureThread.IsBackground = true;
            captureThread.Priority = ThreadPriority.AboveNormal;
            captureThread.SetApartmentState(ApartmentState.MTA);
            writerThread.Start();
            captureThread.Start();
            Log(logPath, "running endpoint=" + endpointId + " mpvPid=" + player.Id +
                " format=WAV/6ch/48000/float32/mask0x3F queueMaxMs=80 threadPriority=AboveNormal");

            Stopwatch clock = Stopwatch.StartNew();
            long nextLogMs = 1000;
            while (!File.Exists(stopFile) && !player.HasExited && state.GetError() == null)
            {
                state.ForceNative = File.Exists(nativeModeFile) ||
                    (File.Exists(nativeTestFile) && DateTime.UtcNow - File.GetLastWriteTimeUtc(nativeTestFile) < TimeSpan.FromMinutes(5));
                Thread.Sleep(100);
                long writeStarted = Interlocked.Read(ref state.WriteInProgressTimestamp);
                if (writeStarted != 0)
                {
                    long now = Stopwatch.GetTimestamp();
                    // WASAPI's first feed may take time after device activation.
                    // This grace is bounded; keep the regular watchdog afterward.
                    if (clock.ElapsedMilliseconds >= 3000 && now - writeStarted > Stopwatch.Frequency * 750 / 1000)
                    {
                        int queued;
                        lock (state.Gate) queued = state.QueuedBytes;
                        long sinceCapture = now - Interlocked.Read(ref state.LastCaptureTimestamp);
                        if (queued >= TrimQueueBytes && sinceCapture < Stopwatch.Frequency / 10)
                        {
                            state.Fail(new TimeoutException("mpv stdin blocked over 750 ms while WASAPI capture continues; queueMs=" +
                                (queued * 1000.0 / (FrameBytes * SampleRate)).ToString("F1")));
                            break;
                        }
                    }
                }
                if (clock.ElapsedMilliseconds >= nextLogMs)
                {
                    LogMetrics(state, logPath);
                    nextLogMs = clock.ElapsedMilliseconds + 1000;
                }
            }
            Exception error = state.GetError();
            if (error != null) throw new InvalidOperationException("Audio relay worker failed", error);
            Log(logPath, File.Exists(stopFile) ? "stop sentinel detected" : "mpv exited code=" + player.ExitCode);
        }
        catch (Exception exception)
        {
            try { Log(logPath, "ERROR " + exception); } catch { }
            throw;
        }
        finally
        {
            state.Cancel();
            // A wedged mpv pipe cannot be closed cleanly until its owner exits.
            // Kill only the mpv instance started by this relay, then join workers.
            if (state.GetError() != null && player != null)
            {
                try { if (!player.HasExited) player.Kill(); } catch { }
            }
            if (captureThread != null && captureThread.IsAlive) captureThread.Join(2000);
            if (writerThread != null && writerThread.IsAlive && !writerThread.Join(2000))
            {
                if (player != null) { try { player.Kill(); } catch { } }
                writerThread.Join(2000);
            }
            if (player != null)
            {
                try { player.StandardInput.BaseStream.Close(); } catch { }
                try { if (!player.WaitForExit(2000)) player.Kill(); } catch { }
                player.Dispose();
            }
            try { Log(logPath, "stopped"); } catch { }
        }
    }
}
