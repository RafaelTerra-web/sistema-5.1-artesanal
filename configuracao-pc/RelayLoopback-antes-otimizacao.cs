// WASAPI loopback relay for the six-channel VB-CABLE render endpoint.
// Audio is streamed in memory to mpv stdin; no captured audio is saved to disk.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Threading;

[ComImport, Guid("BCDE0395-E52F-467C-8E3D-C4579291692E")]
internal class RelayMMDeviceEnumeratorClass { }

[ComImport, Guid("A95664D2-9614-4F35-A746-DE8DB63617E6"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IRelayMMDeviceEnumerator
{
    [PreserveSig] int EnumAudioEndpoints(int flow, int mask, out IntPtr devices);
    [PreserveSig] int GetDefaultAudioEndpoint(int flow, int role, out IRelayMMDevice device);
    [PreserveSig] int GetDevice([MarshalAs(UnmanagedType.LPWStr)] string id, out IRelayMMDevice device);
    [PreserveSig] int RegisterEndpointNotificationCallback(IntPtr callback);
    [PreserveSig] int UnregisterEndpointNotificationCallback(IntPtr callback);
}

[ComImport, Guid("D666063F-1587-4E43-81F1-B948E807363F"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IRelayMMDevice
{
    [PreserveSig] int Activate(ref Guid iid, int clsctx, IntPtr activation, out IntPtr value);
    [PreserveSig] int OpenPropertyStore(int access, out IntPtr properties);
    [PreserveSig] int GetId(out IntPtr id);
    [PreserveSig] int GetState(out int state);
}

[ComImport, Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IRelayAudioClient
{
    [PreserveSig] int Initialize(int shareMode, int flags, long duration, long periodicity, IntPtr format, IntPtr session);
    [PreserveSig] int GetBufferSize(out uint frames);
    [PreserveSig] int GetStreamLatency(out long latency);
    [PreserveSig] int GetCurrentPadding(out uint frames);
    [PreserveSig] int IsFormatSupported(int shareMode, IntPtr format, out IntPtr closest);
    [PreserveSig] int GetMixFormat(out IntPtr format);
    [PreserveSig] int GetDevicePeriod(out long normal, out long minimum);
    [PreserveSig] int Start();
    [PreserveSig] int Stop();
    [PreserveSig] int Reset();
    [PreserveSig] int SetEventHandle(IntPtr handle);
    [PreserveSig] int GetService(ref Guid iid, out IntPtr service);
}

[ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
internal interface IRelayCaptureClient
{
    [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
    [PreserveSig] int ReleaseBuffer(uint frames);
    [PreserveSig] int GetNextPacketSize(out uint frames);
}

public static class RelayLoopback
{
    private const int Channels = 6;
    private const int SampleRate = 48000;
    private const int FrameBytes = Channels * sizeof(float);
    private const int TickFrames = 480; // 10 ms at 48 kHz
    private const int TickBytes = TickFrames * FrameBytes;
    private const int MaxBufferedBytes = 24 * TickBytes; // bound relay queue to 240 ms
    private const int TargetBufferedBytes = 12 * TickBytes;
    private const int LoopbackFlag = 0x00020000;
    private const uint SilentFlag = 0x2;
    private static readonly Guid AudioClientId = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
    private static readonly Guid CaptureClientId = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
    private static readonly Guid FloatSubformat = new Guid("00000003-0000-0010-8000-00AA00389B71");

    private static void Require(int hr, string action)
    {
        if (hr < 0) throw new COMException(action + " failed (0x" + unchecked((uint)hr).ToString("X8") + ")", hr);
    }

    private static void Log(string path, string message)
    {
        if (File.Exists(path) && new FileInfo(path).Length > 2097152) {
            if (File.Exists(path + ".old")) File.Delete(path + ".old");
            File.Move(path, path + ".old");
        }
        File.AppendAllText(path, DateTime.Now.ToString("yyyy-MM-dd HH:mm:ss.fff") + " " + message + Environment.NewLine);
    }

    // Quote one Windows command-line argument for a CreateProcess command line.
    // ProcessStartInfo.ArgumentList is unavailable in Windows PowerShell 5.1/.NET Framework.
    private static string QuoteArgument(string value)
    {
        if (value.Length > 0 && value.IndexOfAny(new char[] { ' ', '\t', '\n', '\v', '"' }) < 0)
            return value;
        StringBuilder result = new StringBuilder();
        result.Append('"');
        int slashes = 0;
        foreach (char c in value)
        {
            if (c == '\\') { slashes++; continue; }
            if (c == '"')
            {
                result.Append('\\', slashes * 2 + 1);
                result.Append('"');
                slashes = 0;
                continue;
            }
            result.Append('\\', slashes);
            slashes = 0;
            result.Append(c);
        }
        result.Append('\\', slashes * 2);
        result.Append('"');
        return result.ToString();
    }

    private static string JoinArguments(string[] arguments)
    {
        StringBuilder commandLine = new StringBuilder();
        foreach (string argument in arguments)
        {
            if (commandLine.Length > 0) commandLine.Append(' ');
            commandLine.Append(QuoteArgument(argument));
        }
        return commandLine.ToString();
    }

    private static void CountSamples(byte[] packet, long[] nonzero, float[] peak)
    {
        const float floor = 0.00001f;
        for (int offset = 0; offset + FrameBytes <= packet.Length; offset += FrameBytes)
        {
            for (int channel = 0; channel < Channels; channel++)
            {
                float sample = BitConverter.ToSingle(packet, offset + channel * sizeof(float));
                float magnitude = Math.Abs(sample);
                if (magnitude > floor && !Single.IsInfinity(magnitude) && !Single.IsNaN(magnitude))
                {
                    nonzero[channel]++;
                    if (magnitude > peak[channel]) peak[channel] = magnitude;
                }
            }
        }
    }

    private static string ChannelStats(long[] nonzero, float[] peak)
    {
        StringBuilder value = new StringBuilder("channels");
        for (int channel = 0; channel < Channels; channel++)
        {
            value.Append(' ');
            value.Append(channel + 1);
            value.Append("=count:");
            value.Append(nonzero[channel]);
            value.Append(",peak:");
            value.Append(peak[channel].ToString("F5", System.Globalization.CultureInfo.InvariantCulture));
            nonzero[channel] = 0;
            peak[channel] = 0;
        }
        return value.ToString();
    }

    private static void VerifyMixFormat(IntPtr format)
    {
        if (format == IntPtr.Zero) throw new InvalidOperationException("GetMixFormat returned no format");
        int tag = (ushort)Marshal.ReadInt16(format, 0);
        int channels = (ushort)Marshal.ReadInt16(format, 2);
        int rate = Marshal.ReadInt32(format, 4);
        int blockAlign = (ushort)Marshal.ReadInt16(format, 12);
        int bits = (ushort)Marshal.ReadInt16(format, 14);
        int cbSize = (ushort)Marshal.ReadInt16(format, 16);
        bool isFloat = tag == 3;
        if (tag == 0xFFFE && cbSize >= 22)
        {
            byte[] guidBytes = new byte[16];
            Marshal.Copy(IntPtr.Add(format, 24), guidBytes, 0, 16);
            isFloat = new Guid(guidBytes) == FloatSubformat;
        }
        if (channels != Channels || rate != SampleRate || bits != 32 || blockAlign != FrameBytes || !isFloat)
            throw new InvalidOperationException("CABLE Input mix must be 6-channel 48 kHz float32; observed tag=" + tag + ", channels=" + channels + ", rate=" + rate + ", bits=" + bits + ", blockAlign=" + blockAlign);
    }

    private static void AddPacket(Queue<byte[]> queue, ref int queuedBytes, byte[] packet)
    {
        queue.Enqueue(packet);
        queuedBytes += packet.Length;
    }

    private static void DiscardOldest(Queue<byte[]> queue, ref int queuedBytes, ref int firstOffset, int targetBytes)
    {
        while (queuedBytes > targetBytes && queue.Count > 0)
        {
            byte[] head = queue.Dequeue();
            int available = head.Length - firstOffset;
            queuedBytes -= available;
            firstOffset = 0;
        }
    }

    private static int FillTick(Queue<byte[]> queue, ref int queuedBytes, ref int firstOffset, byte[] tick)
    {
        Array.Clear(tick, 0, tick.Length);
        int written = 0;
        while (written < tick.Length && queue.Count > 0)
        {
            byte[] head = queue.Peek();
            int size = Math.Min(tick.Length - written, head.Length - firstOffset);
            Buffer.BlockCopy(head, firstOffset, tick, written, size);
            written += size;
            firstOffset += size;
            queuedBytes -= size;
            if (firstOffset >= head.Length)
            {
                queue.Dequeue();
                firstOffset = 0;
            }
        }
        return written;
    }

    // endpointId is the full render endpoint ID for CABLE Input.
    // stopFile is a caller-controlled sentinel; existence stops the relay.
    public static void Run(string endpointId, string mpvPath, string configPath, string logPath, string stopFile)
    {
        if (String.IsNullOrWhiteSpace(endpointId) || String.IsNullOrWhiteSpace(mpvPath) ||
            String.IsNullOrWhiteSpace(configPath) || String.IsNullOrWhiteSpace(logPath) || String.IsNullOrWhiteSpace(stopFile))
            throw new ArgumentException("All five paths/IDs are required");
        if (File.Exists(stopFile)) throw new IOException("Stop sentinel already exists: " + stopFile);
        if (!File.Exists(mpvPath)) throw new FileNotFoundException("mpv executable missing", mpvPath);
        if (!File.Exists(configPath)) throw new FileNotFoundException("mpv config missing", configPath);

        File.WriteAllText(logPath, "");
        IRelayMMDeviceEnumerator enumerator = null;
        IRelayMMDevice device = null;
        IRelayAudioClient audio = null;
        IRelayCaptureClient capture = null;
        IntPtr audioPtr = IntPtr.Zero;
        IntPtr capturePtr = IntPtr.Zero;
        IntPtr mix = IntPtr.Zero;
        Process player = null;
        bool started = false;
        try
        {
            enumerator = (IRelayMMDeviceEnumerator)new RelayMMDeviceEnumeratorClass();
            Require(enumerator.GetDevice(endpointId, out device), "GetDevice");
            int deviceState;
            Require(device.GetState(out deviceState), "GetState");
            if (deviceState != 1) throw new InvalidOperationException("CABLE Input endpoint is not active: state=" + deviceState);
            Guid audioIid = AudioClientId;
            Require(device.Activate(ref audioIid, 23, IntPtr.Zero, out audioPtr), "Activate IAudioClient");
            audio = (IRelayAudioClient)Marshal.GetTypedObjectForIUnknown(audioPtr, typeof(IRelayAudioClient));
            Require(audio.GetMixFormat(out mix), "GetMixFormat");
            VerifyMixFormat(mix);
            bool isCaptureEndpoint = endpointId.StartsWith("{0.0.1.", StringComparison.OrdinalIgnoreCase);
            Require(audio.Initialize(0, isCaptureEndpoint ? 0 : LoopbackFlag, 0, 0, mix, IntPtr.Zero), "Initialize capture");
            Guid captureIid = CaptureClientId;
            Require(audio.GetService(ref captureIid, out capturePtr), "GetService IAudioCaptureClient");
            capture = (IRelayCaptureClient)Marshal.GetTypedObjectForIUnknown(capturePtr, typeof(IRelayCaptureClient));

            ProcessStartInfo psi = new ProcessStartInfo(mpvPath);
            psi.UseShellExecute = false;
            psi.CreateNoWindow = true;
            psi.RedirectStandardInput = true;
            psi.Arguments = JoinArguments(new string[] {
                "--no-config",
                "--include=" + configPath,
                "--no-video",
                "--cache=no",
                "--audio-buffer=0.1",
                "--demuxer=rawaudio",
                "--demuxer-rawaudio-format=floatle",
                "--demuxer-rawaudio-rate=48000",
                "--demuxer-rawaudio-channels=5.1",
                "--log-file=" + logPath + ".mpv.log",
                "-"
            });
            player = Process.Start(psi);
            if (player == null) throw new InvalidOperationException("Could not start mpv");
            Require(audio.Start(), "Start loopback");
            started = true;
            Log(logPath, "running endpoint=" + endpointId + " mpvPid=" + player.Id + " format=6ch/48000/float32");

            Queue<byte[]> queue = new Queue<byte[]>();
            StereoUpmix upmix = new StereoUpmix();
            string nativeModeFile = Path.Combine(Path.GetDirectoryName(logPath), "audio-sistema.nativo");
            string nativeTestFile = Path.Combine(Path.GetDirectoryName(logPath), "audio-sistema.teste-nativo");
            bool forceNative = false;
            long nextModeCheckMs = 0;
            int queuedBytes = 0;
            int firstOffset = 0;
            byte[] tick = new byte[TickBytes];
            long sentTicks = 0, underflowTicks = 0, droppedPackets = 0;
            long[] nonzero = new long[Channels];
            float[] peak = new float[Channels];
            Stopwatch clock = Stopwatch.StartNew();
            long nextTickMs = 40; // short capture lead-in; silence remains continuous
            long nextLogMs = 1000;
            while (!File.Exists(stopFile) && !player.HasExited)
            {
                uint available;
                Require(capture.GetNextPacketSize(out available), "GetNextPacketSize");
                while (available > 0)
                {
                    IntPtr data;
                    uint frames, flags;
                    ulong devicePosition, qpcPosition;
                    Require(capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpcPosition), "GetBuffer");
                    try
                    {
                        int byteCount = checked((int)frames * FrameBytes);
                        byte[] packet = new byte[byteCount];
                        if ((flags & SilentFlag) == 0 && data != IntPtr.Zero)
                        {
                            Marshal.Copy(data, packet, 0, byteCount);
                        }
                        AddPacket(queue, ref queuedBytes, packet);
                    }
                    finally { Require(capture.ReleaseBuffer(frames), "ReleaseBuffer"); }
                    Require(capture.GetNextPacketSize(out available), "GetNextPacketSize");
                }

                if (queuedBytes > MaxBufferedBytes)
                {
                    DiscardOldest(queue, ref queuedBytes, ref firstOffset, TargetBufferedBytes);
                    droppedPackets++;
                }

                long elapsed = clock.ElapsedMilliseconds;
                if (elapsed >= nextModeCheckMs)
                {
                    forceNative = File.Exists(nativeModeFile) ||
                        (File.Exists(nativeTestFile) && DateTime.UtcNow - File.GetLastWriteTimeUtc(nativeTestFile) < TimeSpan.FromMinutes(5));
                    nextModeCheckMs = elapsed + 100;
                }
                if (elapsed - nextTickMs > 100)
                {
                    // A blocked pipe must not create an unbounded burst of stale audio.
                    nextTickMs = elapsed;
                    if (queuedBytes > TargetBufferedBytes)
                    {
                        DiscardOldest(queue, ref queuedBytes, ref firstOffset, TargetBufferedBytes);
                        droppedPackets++;
                    }
                }
                if (elapsed >= nextTickMs)
                {
                    int populated = FillTick(queue, ref queuedBytes, ref firstOffset, tick);
                    if (populated < TickBytes) underflowTicks++;
                    upmix.Process(tick, 0, TickFrames, forceNative);
                    CountSamples(tick, nonzero, peak);
                    player.StandardInput.BaseStream.Write(tick, 0, tick.Length);
                    sentTicks++;
                    nextTickMs += 10;
                }
                else Thread.Sleep(2);

                if (elapsed >= nextLogMs)
                {
                    Log(logPath, "ticks=" + sentTicks + " underflows=" + underflowTicks + " queueMs=" + (queuedBytes / (double)TickBytes * 10.0).ToString("F1") + " drops=" + droppedPackets + " upmix=" + (forceNative ? "native" : (upmix.IsSynthesizing ? "fronts-auto" : "per-stream")));
                    Log(logPath, ChannelStats(nonzero, peak));
                    nextLogMs = elapsed + 1000;
                }
            }
            Log(logPath, File.Exists(stopFile) ? "stop sentinel detected" : "mpv exited code=" + player.ExitCode);
        }
        catch (Exception ex)
        {
            try { Log(logPath, "ERROR " + ex); } catch { }
            throw;
        }
        finally
        {
            if (started && audio != null) { try { audio.Stop(); } catch { } }
            if (player != null)
            {
                try { player.StandardInput.BaseStream.Close(); } catch { }
                try { if (!player.WaitForExit(2000)) player.Kill(); } catch { }
                player.Dispose();
            }
            if (capture != null) Marshal.ReleaseComObject(capture);
            if (capturePtr != IntPtr.Zero) Marshal.Release(capturePtr);
            if (mix != IntPtr.Zero) Marshal.FreeCoTaskMem(mix);
            if (audio != null) Marshal.ReleaseComObject(audio);
            if (audioPtr != IntPtr.Zero) Marshal.Release(audioPtr);
            if (device != null) Marshal.ReleaseComObject(device);
            if (enumerator != null) Marshal.ReleaseComObject(enumerator);
            try { Log(logPath, "stopped"); } catch { }
        }
    }
}
