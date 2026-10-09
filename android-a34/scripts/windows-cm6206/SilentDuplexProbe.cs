using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Runtime.InteropServices;
using System.Threading;

namespace Sistema51.Cm6206
{
    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IDuplexCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    [ComImport, Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface IDuplexRenderClient
    {
        [PreserveSig] int GetBuffer(uint frames, out IntPtr data);
        [PreserveSig] int ReleaseBuffer(uint frames, uint flags);
    }

    public sealed class DuplexCall
    {
        public string Operation;
        public int HResult;
        public string HResultHex;
        public double ElapsedMilliseconds;
    }

    public sealed class DuplexResult
    {
        public string CapturedAtUtc = DateTime.UtcNow.ToString("o");
        public string Mode = "exclusive_silent_duplex";
        public string RenderEndpointId;
        public string CaptureEndpointId;
        public string RenderFormat = "48000Hz PCM16 6ch WAVEFORMATEXTENSIBLE mask=0x0000060F";
        public string CaptureFormat = "48000Hz PCM16 2ch WAVEFORMATEXTENSIBLE mask=0x00000003";
        public int RequestedDurationMilliseconds = 3000;
        public int DeadlineMilliseconds = 10000;
        public long RequestedBuffer100ns = 1000000; // 100 ms buffer, conservative scheduler margin.
        public long RequestedPeriod100ns = 100000; // 10 ms polling stream period.
        public string ApartmentState;
        public string Outcome = "not_started";
        public string Error;
        public bool OnlyZeroSamplesWritten = true;
        public bool RawCaptureSaved = false;
        public bool DefaultEndpointChanged = false;
        public bool VolumeChanged = false;
        public bool HidOperationsPerformed = false;
        public bool CleanupComplete;
        public uint RenderBufferFrames;
        public uint CaptureBufferFrames;
        public long RenderApiLatency100ns;
        public long CaptureApiLatency100ns;
        public long RenderFramesWritten;
        public long RenderWriteCalls;
        public long RenderZeroBytesWritten;
        public uint FinalRenderPaddingFrames;
        public long RenderFramesConsumedEstimate;
        public uint MaximumRenderPaddingFrames;
        public long CaptureFramesReturned;
        public long CaptureFramesReleased;
        public long CapturePackets;
        public long CaptureEmptyPolls;
        public long CaptureDiscontinuityPackets;
        public long CaptureSilentPackets;
        public long CaptureTimestampErrorPackets;
        public ulong FirstCaptureDevicePosition;
        public ulong LastCaptureDevicePosition;
        public ulong FirstCaptureQpc100ns;
        public ulong LastCaptureQpc100ns;
        public double MaximumWriteGapMilliseconds;
        public double MaximumWriteCallMilliseconds;
        public double MaximumPollGapMilliseconds;
        public double StreamElapsedMilliseconds;
        public double TotalElapsedMilliseconds;
        public Dictionary<string, long> CaptureFlags = new Dictionary<string, long>();
        public List<DuplexCall> Calls = new List<DuplexCall>();
        public List<string> CleanupErrors = new List<string>();
        public string[] Limitations = {
            "Counts and timing come from WASAPI buffers, not from physical or acoustic measurements.",
            "RenderFramesConsumedEstimate = submitted frames minus final padding; it does not prove physical analog output.",
            "Capture packets are discarded without saving samples; no optical source is attached.",
            "This probe cannot establish SPDIF lock, AC-3 transparency, channel routing or end-to-end latency.",
            "The deadline is checked between API calls; the Windows driver controls individual COM-call completion."
        };
    }

    public static class SilentDuplexProbe
    {
        private static readonly Guid AudioId = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
        private static readonly Guid RenderId = new Guid("F294ACFC-3146-4483-A7BF-ADDCA7C260E2");
        private static readonly Guid CaptureId = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");

        private static string Hex(int hr) { return "0x" + unchecked((uint)hr).ToString("X8"); }
        private static void Require(int hr, string operation)
        {
            if (hr < 0) throw new COMException(operation + " returned " + Hex(hr), hr);
        }
        private static void Record(DuplexResult result, Stopwatch total, string operation, int hr)
        {
            result.Calls.Add(new DuplexCall { Operation = operation, HResult = hr, HResultHex = Hex(hr), ElapsedMilliseconds = total.Elapsed.TotalMilliseconds });
        }
        private static void Release(object instance)
        {
            if (instance != null && Marshal.IsComObject(instance)) Marshal.ReleaseComObject(instance);
        }
        private static void CheckDeadline(Stopwatch total)
        {
            if (total.ElapsedMilliseconds >= 10000) throw new TimeoutException("Probe exceeded the 10-second deadline.");
        }
        private static IntPtr Format(int channels, uint mask)
        {
            byte[] bytes = new byte[40];
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)0xFFFE), 0, bytes, 0, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)channels), 0, bytes, 2, 2);
            Buffer.BlockCopy(BitConverter.GetBytes(48000), 0, bytes, 4, 4);
            Buffer.BlockCopy(BitConverter.GetBytes(48000 * channels * 2), 0, bytes, 8, 4);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)(channels * 2)), 0, bytes, 12, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)16), 0, bytes, 14, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)22), 0, bytes, 16, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)16), 0, bytes, 18, 2);
            Buffer.BlockCopy(BitConverter.GetBytes(mask), 0, bytes, 20, 4);
            Buffer.BlockCopy(new Guid("00000001-0000-0010-8000-00AA00389B71").ToByteArray(), 0, bytes, 24, 16);
            IntPtr pointer = Marshal.AllocHGlobal(bytes.Length);
            Marshal.Copy(bytes, 0, pointer, bytes.Length);
            return pointer;
        }
        private static IAudioClient Activate(IDevice device, out IntPtr pointer)
        {
            Guid iid = AudioId;
            Require(device.Activate(ref iid, 23, IntPtr.Zero, out pointer), "Activate IAudioClient");
            return (IAudioClient)Marshal.GetTypedObjectForIUnknown(pointer, typeof(IAudioClient));
        }
        private static void ZeroWrite(IDuplexRenderClient render, uint frames, byte[] zeros, DuplexResult result, Stopwatch stream, ref double lastWrite)
        {
            if (frames == 0) return;
            Stopwatch call = Stopwatch.StartNew();
            IntPtr buffer;
            Require(render.GetBuffer(frames, out buffer), "Render GetBuffer");
            bool released = false;
            try
            {
                int count = checked((int)frames * 12);
                Marshal.Copy(zeros, 0, buffer, count);
                Require(render.ReleaseBuffer(frames, 0), "Render ReleaseBuffer zero PCM");
                released = true;
                result.RenderFramesWritten += frames;
                result.RenderWriteCalls++;
                result.RenderZeroBytesWritten += count;
            }
            finally
            {
                if (!released) render.ReleaseBuffer(0, 0);
            }
            double now = stream.Elapsed.TotalMilliseconds;
            if (lastWrite >= 0) result.MaximumWriteGapMilliseconds = Math.Max(result.MaximumWriteGapMilliseconds, now - lastWrite);
            lastWrite = now;
            result.MaximumWriteCallMilliseconds = Math.Max(result.MaximumWriteCallMilliseconds, call.Elapsed.TotalMilliseconds);
        }

        public static DuplexResult Run(string renderEndpointId, string captureEndpointId)
        {
            DuplexResult result = new DuplexResult { RenderEndpointId = renderEndpointId, CaptureEndpointId = captureEndpointId };
            result.ApartmentState = Thread.CurrentThread.GetApartmentState().ToString();
            Stopwatch total = Stopwatch.StartNew();
            Stopwatch stream = new Stopwatch();
            IDeviceEnumerator enumerator = null;
            IDevice renderDevice = null, captureDevice = null;
            IAudioClient renderAudio = null, captureAudio = null;
            IDuplexRenderClient render = null;
            IDuplexCaptureClient capture = null;
            IntPtr renderAudioPointer = IntPtr.Zero, captureAudioPointer = IntPtr.Zero;
            IntPtr renderPointer = IntPtr.Zero, capturePointer = IntPtr.Zero;
            IntPtr renderFormat = IntPtr.Zero, captureFormat = IntPtr.Zero;
            bool captureStarted = false, renderStarted = false;
            try
            {
                if (result.ApartmentState != "STA") throw new InvalidOperationException("Run this probe in an STA PowerShell process.");
                if (renderEndpointId == null || !renderEndpointId.StartsWith("{0.0.0.")) throw new ArgumentException("An exact render endpoint ID is required.");
                if (captureEndpointId == null || !captureEndpointId.StartsWith("{0.0.1.")) throw new ArgumentException("An exact SPDIF capture endpoint ID is required.");
                enumerator = (IDeviceEnumerator)new DeviceEnumeratorClass();
                Require(enumerator.GetDevice(renderEndpointId, out renderDevice), "GetDevice render");
                Require(enumerator.GetDevice(captureEndpointId, out captureDevice), "GetDevice SPDIF capture");
                int state;
                Require(renderDevice.GetState(out state), "GetState render");
                if (state != 1) throw new InvalidOperationException("The selected render endpoint is not active.");
                Require(captureDevice.GetState(out state), "GetState SPDIF capture");
                if (state != 1) throw new InvalidOperationException("The selected SPDIF endpoint is not active.");
                renderAudio = Activate(renderDevice, out renderAudioPointer);
                captureAudio = Activate(captureDevice, out captureAudioPointer);
                renderFormat = Format(6, 0x60F);
                captureFormat = Format(2, 3);
                CheckDeadline(total);
                int hr = captureAudio.Initialize(1, 0, result.RequestedBuffer100ns, result.RequestedPeriod100ns, captureFormat, IntPtr.Zero);
                Record(result, total, "Capture Initialize exclusive SPDIF PCM16 2ch48k mask3", hr);
                Require(hr, "Capture Initialize");
                CheckDeadline(total);
                hr = renderAudio.Initialize(1, 0, result.RequestedBuffer100ns, result.RequestedPeriod100ns, renderFormat, IntPtr.Zero);
                Record(result, total, "Render Initialize exclusive PCM16 6ch48k mask60F", hr);
                Require(hr, "Render Initialize");
                Require(renderAudio.GetBufferSize(out result.RenderBufferFrames), "Render GetBufferSize");
                Require(captureAudio.GetBufferSize(out result.CaptureBufferFrames), "Capture GetBufferSize");
                Require(renderAudio.GetStreamLatency(out result.RenderApiLatency100ns), "Render GetStreamLatency");
                Require(captureAudio.GetStreamLatency(out result.CaptureApiLatency100ns), "Capture GetStreamLatency");
                Guid iid = RenderId;
                Require(renderAudio.GetService(ref iid, out renderPointer), "GetService render");
                render = (IDuplexRenderClient)Marshal.GetTypedObjectForIUnknown(renderPointer, typeof(IDuplexRenderClient));
                iid = CaptureId;
                Require(captureAudio.GetService(ref iid, out capturePointer), "GetService SPDIF capture");
                capture = (IDuplexCaptureClient)Marshal.GetTypedObjectForIUnknown(capturePointer, typeof(IDuplexCaptureClient));
                byte[] zeros = new byte[checked((int)result.RenderBufferFrames * 12)];
                double lastWrite = -1;
                ZeroWrite(render, result.RenderBufferFrames, zeros, result, stream, ref lastWrite);
                CheckDeadline(total);
                stream.Start();
                hr = captureAudio.Start();
                Record(result, total, "Capture Start", hr);
                Require(hr, "Capture Start");
                captureStarted = true;
                hr = renderAudio.Start();
                Record(result, total, "Render Start", hr);
                Require(hr, "Render Start");
                renderStarted = true;
                double previousPoll = stream.Elapsed.TotalMilliseconds;
                while (stream.ElapsedMilliseconds < 3000)
                {
                    CheckDeadline(total);
                    double poll = stream.Elapsed.TotalMilliseconds;
                    result.MaximumPollGapMilliseconds = Math.Max(result.MaximumPollGapMilliseconds, poll - previousPoll);
                    previousPoll = poll;
                    uint padding;
                    Require(renderAudio.GetCurrentPadding(out padding), "Render GetCurrentPadding");
                    result.MaximumRenderPaddingFrames = Math.Max(result.MaximumRenderPaddingFrames, padding);
                    if (padding > result.RenderBufferFrames) throw new InvalidOperationException("Render padding exceeds the buffer size.");
                    ZeroWrite(render, result.RenderBufferFrames - padding, zeros, result, stream, ref lastWrite);
                    // Bound the drain pass; do not allow input backlog to starve silent output.
                    for (int packetIndex = 0; packetIndex < 16; packetIndex++)
                    {
                        CheckDeadline(total);
                        IntPtr data;
                        uint frames, flags;
                        ulong devicePosition, qpcPosition;
                        hr = capture.GetBuffer(out data, out frames, out flags, out devicePosition, out qpcPosition);
                        Require(hr, "SPDIF Capture GetBuffer");
                        if (frames == 0) { result.CaptureEmptyPolls++; break; }
                        result.CapturePackets++;
                        result.CaptureFramesReturned += frames;
                        string flag = "0x" + flags.ToString("X8");
                        if (!result.CaptureFlags.ContainsKey(flag)) result.CaptureFlags[flag] = 0;
                        result.CaptureFlags[flag]++;
                        if ((flags & 1) != 0) result.CaptureDiscontinuityPackets++;
                        if ((flags & 2) != 0) result.CaptureSilentPackets++;
                        if ((flags & 4) != 0) result.CaptureTimestampErrorPackets++;
                        if (result.CapturePackets == 1)
                        {
                            result.FirstCaptureDevicePosition = devicePosition;
                            result.FirstCaptureQpc100ns = qpcPosition;
                        }
                        result.LastCaptureDevicePosition = devicePosition;
                        result.LastCaptureQpc100ns = qpcPosition;
                        // Discard input: no capture samples are copied or persisted.
                        Require(capture.ReleaseBuffer(frames), "SPDIF Capture ReleaseBuffer");
                        result.CaptureFramesReleased += frames;
                    }
                    Thread.Sleep(1);
                }
                Require(renderAudio.GetCurrentPadding(out result.FinalRenderPaddingFrames), "Final render GetCurrentPadding");
                result.RenderFramesConsumedEstimate = result.RenderFramesWritten - result.FinalRenderPaddingFrames;
                result.Outcome = result.CaptureFramesReleased > 0 && result.RenderFramesWritten > result.RenderBufferFrames ? "duplex_api_streams_passed" : "started_but_no_progress";
            }
            catch (Exception error)
            {
                result.Error = error.Message;
                result.Outcome = "failed";
            }
            finally
            {
                result.StreamElapsedMilliseconds = stream.Elapsed.TotalMilliseconds;
                try
                {
                    if (renderStarted)
                    {
                        int hr = renderAudio.Stop();
                        Record(result, total, "Render Stop", hr);
                        if (hr < 0) result.CleanupErrors.Add("Render Stop " + Hex(hr));
                    }
                    if (captureStarted)
                    {
                        int hr = captureAudio.Stop();
                        Record(result, total, "Capture Stop", hr);
                        if (hr < 0) result.CleanupErrors.Add("Capture Stop " + Hex(hr));
                    }
                }
                catch (Exception error) { result.CleanupErrors.Add(error.Message); }
                try
                {
                    Release(render); Release(capture);
                    if (renderPointer != IntPtr.Zero) Marshal.Release(renderPointer);
                    if (capturePointer != IntPtr.Zero) Marshal.Release(capturePointer);
                    Release(renderAudio); Release(captureAudio);
                    if (renderAudioPointer != IntPtr.Zero) Marshal.Release(renderAudioPointer);
                    if (captureAudioPointer != IntPtr.Zero) Marshal.Release(captureAudioPointer);
                    Release(renderDevice); Release(captureDevice); Release(enumerator);
                }
                catch (Exception error) { result.CleanupErrors.Add(error.Message); }
                if (renderFormat != IntPtr.Zero) Marshal.FreeHGlobal(renderFormat);
                if (captureFormat != IntPtr.Zero) Marshal.FreeHGlobal(captureFormat);
                result.CleanupComplete = result.CleanupErrors.Count == 0;
                result.TotalElapsedMilliseconds = total.Elapsed.TotalMilliseconds;
            }
            return result;
        }
    }
}
