// Capture only: no render client, endpoint selection, volume changes or HID writes.
// Compile together with CoreAudioFormatProbe.cs to reuse its COM definitions.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.IO;
using System.Runtime.InteropServices;
using System.Threading;

namespace Sistema51.Cm6206
{
    [ComImport, Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ISpdifCaptureClient
    {
        [PreserveSig] int GetBuffer(out IntPtr data, out uint frames, out uint flags, out ulong devicePosition, out ulong qpcPosition);
        [PreserveSig] int ReleaseBuffer(uint frames);
        [PreserveSig] int GetNextPacketSize(out uint frames);
    }

    // The unused slots preserve IAudioEndpointVolume's native vtable ordering.
    // Only GetChannelCount, GetMasterVolumeLevelScalar and GetMute are invoked.
    [ComImport, Guid("5CDF2C82-841E-4546-9722-0CF74078229A"), InterfaceType(ComInterfaceType.InterfaceIsIUnknown)]
    internal interface ISpdifEndpointVolume
    {
        [PreserveSig] int ReservedRegisterCallback(IntPtr callback);
        [PreserveSig] int ReservedUnregisterCallback(IntPtr callback);
        [PreserveSig] int GetChannelCount(out uint count);
        [PreserveSig] int ReservedSetMasterDecibels(float value, IntPtr eventContext);
        [PreserveSig] int ReservedSetMasterScalar(float value, IntPtr eventContext);
        [PreserveSig] int ReservedGetMasterDecibels(out float value);
        [PreserveSig] int GetMasterVolumeLevelScalar(out float value);
        [PreserveSig] int ReservedSetChannelDecibels(uint channel, float value, IntPtr eventContext);
        [PreserveSig] int ReservedSetChannelScalar(uint channel, float value, IntPtr eventContext);
        [PreserveSig] int ReservedGetChannelDecibels(uint channel, out float value);
        [PreserveSig] int ReservedGetChannelScalar(uint channel, out float value);
        [PreserveSig] int ReservedSetMute([MarshalAs(UnmanagedType.Bool)] bool value, IntPtr eventContext);
        [PreserveSig] int GetMute([MarshalAs(UnmanagedType.Bool)] out bool value);
    }

    public sealed class SpdifCaptureResult
    {
        public string CapturedAtUtc = DateTime.UtcNow.ToString("o");
        public string Kind = "windows_exclusive_spdif_capture";
        public string EndpointId;
        public string FriendlyName;
        public string PcmFile;
        public string Format = "PCM16LE 48000Hz stereo WAVEFORMATEXTENSIBLE mask=0x3";
        public int SampleRate = 48000, Channels = 2, BitsPerSample = 16;
        public int RequestedDurationMilliseconds = 3000;
        public int MaximumFrames = 144000;
        public int DeadlineMilliseconds = 10000;
        public bool ExactFormatSupported;
        public bool Ok;
        public bool TimedOut;
        public string Outcome = "not_started";
        public string Error;
        public long Frames, Bytes, Packets, EmptyPolls;
        public long DiscontinuityPackets, SilentPackets, TimestampErrorPackets;
        public uint BufferFrames;
        public uint? EndpointVolumeChannelCount;
        public float? EndpointMasterVolumeScalar;
        public bool? EndpointMuted;
        public List<string> EndpointVolumeErrors = new List<string>();
        public ulong FirstDevicePosition, LastDevicePosition;
        public double StreamMilliseconds, TotalMilliseconds;
        public bool CleanupComplete;
        public bool PlaybackPerformed = false, DefaultEndpointChanged = false, VolumeChanged = false, HidOperationsPerformed = false;
        public bool OpticalSourceValidated = false, Ac3Validated = false, BitPerfectValidated = false;
        public Dictionary<string, long> Flags = new Dictionary<string, long>();
        public List<string> CleanupErrors = new List<string>();
        public string[] Limitations = {
            "Selecting the named SPDIF endpoint and receiving samples does not establish their original source or AC3 transparency; analyze the saved samples.",
            "Capture is limited to three seconds and 144000 frames. Individual COM calls are controlled by the Windows driver.",
            "Exclusive mode avoids the shared Windows mixer; this alone does not prove bit-perfect optical input."
        };
    }

    public static class WindowsSpdifCapture
    {
        private static readonly Guid AudioId = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
        private static readonly Guid CaptureId = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
        private static readonly Guid EndpointVolumeId = new Guid("5CDF2C82-841E-4546-9722-0CF74078229A");
        [DllImport("ole32.dll")] private static extern int PropVariantClear(ref PropertyValue value);
        private static void Require(int hr, string operation)
        {
            if (hr < 0) throw new COMException(operation + " returned 0x" + unchecked((uint)hr).ToString("X8"), hr);
        }
        private static void Release(object value, SpdifCaptureResult result)
        {
            try { if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value); }
            catch (Exception error) { result.CleanupErrors.Add(error.Message); }
        }
        private static void ReleasePointer(IntPtr value, SpdifCaptureResult result)
        {
            try { if (value != IntPtr.Zero) Marshal.Release(value); }
            catch (Exception error) { result.CleanupErrors.Add(error.Message); }
        }
        private static string GetFriendlyName(IDevice device)
        {
            IProperties properties = null;
            try
            {
                Require(device.OpenPropertyStore(0, out properties), "OpenPropertyStore read-only");
                PropertyKey key = new PropertyKey("A45C254E-DF1C-4EFD-8020-67D146A850E0", 14);
                PropertyValue value;
                Require(properties.GetValue(ref key, out value), "Read friendly name");
                try { return value.Describe(); }
                finally { PropVariantClear(ref value); }
            }
            finally { if (properties != null) Marshal.ReleaseComObject(properties); }
        }
        private static void ReadEndpointVolume(IDevice device, SpdifCaptureResult result)
        {
            ISpdifEndpointVolume volume = null;
            IntPtr pointer = IntPtr.Zero;
            try
            {
                Guid iid = EndpointVolumeId;
                Require(device.Activate(ref iid, 23, IntPtr.Zero, out pointer), "Activate endpoint volume for read-only inspection");
                volume = (ISpdifEndpointVolume)Marshal.GetTypedObjectForIUnknown(pointer, typeof(ISpdifEndpointVolume));
                uint channels;
                int hr = volume.GetChannelCount(out channels);
                if (hr >= 0) result.EndpointVolumeChannelCount = channels;
                else result.EndpointVolumeErrors.Add("GetChannelCount returned 0x" + unchecked((uint)hr).ToString("X8"));
                float scalar;
                hr = volume.GetMasterVolumeLevelScalar(out scalar);
                if (hr >= 0) result.EndpointMasterVolumeScalar = scalar;
                else result.EndpointVolumeErrors.Add("GetMasterVolumeLevelScalar returned 0x" + unchecked((uint)hr).ToString("X8"));
                bool muted;
                hr = volume.GetMute(out muted);
                if (hr >= 0) result.EndpointMuted = muted;
                else result.EndpointVolumeErrors.Add("GetMute returned 0x" + unchecked((uint)hr).ToString("X8"));
            }
            catch (Exception error) { result.EndpointVolumeErrors.Add(error.Message); }
            finally { Release(volume, result); ReleasePointer(pointer, result); }
        }
        private static IntPtr MakeFormat()
        {
            byte[] bytes = new byte[40];
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)0xFFFE), 0, bytes, 0, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)2), 0, bytes, 2, 2);
            Buffer.BlockCopy(BitConverter.GetBytes(48000), 0, bytes, 4, 4);
            Buffer.BlockCopy(BitConverter.GetBytes(192000), 0, bytes, 8, 4);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)4), 0, bytes, 12, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)16), 0, bytes, 14, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)22), 0, bytes, 16, 2);
            Buffer.BlockCopy(BitConverter.GetBytes((ushort)16), 0, bytes, 18, 2);
            Buffer.BlockCopy(BitConverter.GetBytes(3), 0, bytes, 20, 4);
            Buffer.BlockCopy(new Guid("00000001-0000-0010-8000-00AA00389B71").ToByteArray(), 0, bytes, 24, 16);
            IntPtr value = Marshal.AllocHGlobal(bytes.Length);
            Marshal.Copy(bytes, 0, value, bytes.Length);
            return value;
        }

        public static SpdifCaptureResult ReadVolumeOnly(string endpointId)
        {
            SpdifCaptureResult result = new SpdifCaptureResult { EndpointId = endpointId, Kind = "endpoint_volume_read_only" };
            IDeviceEnumerator enumerator = null;
            IDevice device = null;
            try
            {
                if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA)
                    throw new InvalidOperationException("Run in an STA PowerShell process.");
                enumerator = (IDeviceEnumerator)new DeviceEnumeratorClass();
                Require(enumerator.GetDevice(endpointId, out device), "GetDevice explicit endpoint");
                result.FriendlyName = GetFriendlyName(device);
                ReadEndpointVolume(device, result);
                result.Ok = result.EndpointVolumeErrors.Count == 0;
                result.Outcome = result.Ok ? "volume_read" : "volume_read_incomplete";
            }
            catch (Exception error) { result.Error = error.Message; result.Outcome = "failed"; }
            finally { Release(device, result); Release(enumerator, result); result.CleanupComplete = result.CleanupErrors.Count == 0; }
            return result;
        }

        public static SpdifCaptureResult Run(string endpointId, string pcmFile, string artifactRoot)
        {
            return Run(endpointId, pcmFile, artifactRoot, 3);
        }

        // Longer bounded capture permits a complete six-channel synthetic sequence.
        public static SpdifCaptureResult Run(string endpointId, string pcmFile, string artifactRoot, int seconds)
        {
            SpdifCaptureResult result = new SpdifCaptureResult { EndpointId = endpointId };
            Stopwatch total = Stopwatch.StartNew(), stream = new Stopwatch();
            IDeviceEnumerator enumerator = null;
            IDevice device = null;
            IAudioClient audio = null;
            ISpdifCaptureClient capture = null;
            IntPtr audioPointer = IntPtr.Zero, capturePointer = IntPtr.Zero, format = IntPtr.Zero;
            FileStream output = null;
            bool started = false;
            try
            {
                if (seconds < 1 || seconds > 10)
                    throw new ArgumentOutOfRangeException("seconds", "Capture must last between one and ten seconds.");
                result.RequestedDurationMilliseconds = seconds * 1000;
                result.MaximumFrames = seconds * 48000;
                result.DeadlineMilliseconds = result.RequestedDurationMilliseconds + 7000;
                result.Limitations[1] = "Capture is limited to " + seconds + " seconds and " + result.MaximumFrames +
                    " frames. Individual COM calls are controlled by the Windows driver.";
                if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA)
                    throw new InvalidOperationException("Run in an STA PowerShell process.");
                Guid endpointGuid;
                if (endpointId == null || !endpointId.StartsWith("{0.0.1.00000000}.", StringComparison.OrdinalIgnoreCase) ||
                    !Guid.TryParse(endpointId.Substring("{0.0.1.00000000}.".Length), out endpointGuid))
                    throw new ArgumentException("Specify an exact capture endpoint ID: {0.0.1.00000000}.{GUID}.");
                string root = Path.GetFullPath(artifactRoot).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                string target = Path.GetFullPath(pcmFile);
                if (!root.EndsWith("android-a34" + Path.DirectorySeparatorChar + "artifacts" + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase) ||
                    !target.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !target.EndsWith(".pcm", StringComparison.OrdinalIgnoreCase))
                    throw new ArgumentException("Raw capture must stay inside this repository's android-a34/artifacts directory.");
                if (File.Exists(target)) throw new IOException("Refusing to overwrite an existing capture.");
                result.PcmFile = target;
                enumerator = (IDeviceEnumerator)new DeviceEnumeratorClass();
                Require(enumerator.GetDevice(endpointId, out device), "GetDevice explicit capture endpoint");
                int state;
                Require(device.GetState(out state), "GetState");
                if (state != 1) throw new InvalidOperationException("Selected endpoint is not active.");
                result.FriendlyName = GetFriendlyName(device);
                if (result.FriendlyName == null || result.FriendlyName.IndexOf("SPDIF", StringComparison.OrdinalIgnoreCase) < 0 ||
                    result.FriendlyName.IndexOf("USB Sound Device", StringComparison.OrdinalIgnoreCase) < 0 ||
                    result.FriendlyName.IndexOf("Microphone", StringComparison.OrdinalIgnoreCase) >= 0 ||
                    result.FriendlyName.IndexOf("Line", StringComparison.OrdinalIgnoreCase) >= 0)
                    throw new InvalidOperationException("Endpoint must be the SPDIF input of USB Sound Device; microphone and line inputs are rejected.");
                ReadEndpointVolume(device, result);
                Guid iid = AudioId;
                Require(device.Activate(ref iid, 23, IntPtr.Zero, out audioPointer), "Activate capture IAudioClient");
                audio = (IAudioClient)Marshal.GetTypedObjectForIUnknown(audioPointer, typeof(IAudioClient));
                format = MakeFormat();
                int formatHr = audio.IsFormatSupported(1, format, IntPtr.Zero);
                Require(formatHr, "IsFormatSupported exclusive PCM16 stereo 48k");
                if (formatHr != 0) throw new InvalidOperationException("Exact capture format was not accepted.");
                result.ExactFormatSupported = true;
                Require(audio.Initialize(1, 0, 1000000, 100000, format, IntPtr.Zero), "Initialize exclusive capture");
                Require(audio.GetBufferSize(out result.BufferFrames), "GetBufferSize");
                if (result.BufferFrames == 0 || result.BufferFrames > 48000) throw new InvalidOperationException("Unexpected capture buffer size.");
                iid = CaptureId;
                Require(audio.GetService(ref iid, out capturePointer), "GetService capture");
                capture = (ISpdifCaptureClient)Marshal.GetTypedObjectForIUnknown(capturePointer, typeof(ISpdifCaptureClient));
                byte[] buffer = new byte[checked((int)result.BufferFrames * 4)];
                if (total.ElapsedMilliseconds >= result.DeadlineMilliseconds) throw new TimeoutException("Capture setup exceeded the total deadline.");
                Directory.CreateDirectory(Path.GetDirectoryName(target));
                output = new FileStream(target, FileMode.CreateNew, FileAccess.Write, FileShare.None);
                Require(audio.Start(), "Start capture");
                started = true;
                stream.Start();
                while (stream.ElapsedMilliseconds < result.RequestedDurationMilliseconds && result.Frames < result.MaximumFrames)
                {
                    if (total.ElapsedMilliseconds >= result.DeadlineMilliseconds) throw new TimeoutException("Capture exceeded the total deadline.");
                    IntPtr data;
                    uint frames, flags;
                    ulong position, qpc;
                    int hr = capture.GetBuffer(out data, out frames, out flags, out position, out qpc);
                    Require(hr, "Capture GetBuffer");
                    if (frames == 0) { result.EmptyPolls++; Thread.Sleep(1); continue; }
                    try
                    {
                        result.Packets++;
                        string key = "0x" + flags.ToString("X8");
                        if (!result.Flags.ContainsKey(key)) result.Flags[key] = 0;
                        result.Flags[key]++;
                        if ((flags & 1) != 0) result.DiscontinuityPackets++;
                        if ((flags & 2) != 0) result.SilentPackets++;
                        if ((flags & 4) != 0) result.TimestampErrorPackets++;
                        if (result.Packets == 1) result.FirstDevicePosition = position;
                        result.LastDevicePosition = position;
                        if (frames > result.BufferFrames) throw new InvalidOperationException("Packet exceeds the initialized capture buffer.");
                        int count = checked((int)Math.Min((long)frames, result.MaximumFrames - result.Frames) * 4);
                        if ((flags & 2) != 0) Array.Clear(buffer, 0, count);
                        else
                        {
                            if (data == IntPtr.Zero) throw new InvalidOperationException("Non-silent packet has no sample pointer.");
                            Marshal.Copy(data, buffer, 0, count);
                        }
                        output.Write(buffer, 0, count);
                        result.Bytes += count;
                        result.Frames += count / 4;
                    }
                    finally { Require(capture.ReleaseBuffer(frames), "Capture ReleaseBuffer"); }
                }
                result.Outcome = result.Frames > 0 ? "samples_captured_source_unvalidated" : "no_capture_packets";
                result.Ok = result.Frames > 0;
            }
            catch (Exception error) { result.Error = error.Message; result.Outcome = "failed"; result.Ok = false; result.TimedOut = error is TimeoutException; }
            finally
            {
                result.StreamMilliseconds = stream.Elapsed.TotalMilliseconds;
                if (started)
                {
                    try { Require(audio.Stop(), "Stop capture"); }
                    catch (Exception error) { result.CleanupErrors.Add(error.Message); }
                }
                if (output != null)
                {
                    try { output.Dispose(); }
                    catch (Exception error) { result.CleanupErrors.Add(error.Message); }
                }
                Release(capture, result); ReleasePointer(capturePointer, result);
                Release(audio, result); ReleasePointer(audioPointer, result);
                Release(device, result); Release(enumerator, result);
                if (format != IntPtr.Zero) Marshal.FreeHGlobal(format);
                result.CleanupComplete = result.CleanupErrors.Count == 0;
                if (!result.CleanupComplete) result.Ok = false;
                result.TotalMilliseconds = total.Elapsed.TotalMilliseconds;
            }
            return result;
        }
    }
}
