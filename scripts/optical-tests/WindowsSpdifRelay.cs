// Bounded optical carrier -> mpv decoder -> explicit USB PCM output.
// Run stays muted; RunFronts enables only FL/FR at an explicit 1..25 percent gain.
// Compile with CoreAudioFormatProbe.cs and WindowsSpdifCapture.cs. No HID/volume/default writes.
using System;
using System.Collections.Generic;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Runtime.InteropServices;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;
using System.Threading.Tasks;

namespace Sistema51.Cm6206
{
    public sealed class SpdifRelayResult
    {
        public string Kind = "windows_spdif_live_decode_usb_muted_probe";
        public string StartedAtUtc = DateTime.UtcNow.ToString("o");
        public string CaptureEndpointId, RenderEndpointId, CaptureFriendlyName, RenderFriendlyName;
        public string PcmFile, MpvLogFile, StopFile, MpvArguments, Error;
        public string Outcome = "not_started";
        public int Seconds, MaximumFrames, DeadlineMilliseconds, WriteDeadlineMilliseconds = 1500;
        public uint CaptureBufferFrames;
        public long Frames, Bytes, BytesSent, Packets, EmptyPolls, SilentPackets, DiscontinuityPackets, TimestampErrorPackets, Heartbeats;
        public double TotalMilliseconds, CaptureMilliseconds, MaximumWriteMilliseconds;
        public int? OwnedMpvPid, MpvExitCode;
        public bool CaptureExactFormatSupported, RenderExactFormatSupported, Ok, TimedOut, StopRequested;
        public bool SixChannelUsbOutputNegotiated, Ac3SixChannelSourceDetected, DecoderCrcCheckingEnabled, DecoderLogClean;
        public List<string> DecoderLogProblems = new List<string>();
        public bool StdinClosed, OwnedMpvKilledAfterExitDeadline, CleanupComplete;
        public bool PlaybackPerformed, PlaybackMuted = true;
        public int PlaybackVolume = 0;
        public bool FrontsOnlyOutput;
        public bool SixChannelsAudibleOutput;
        public bool FourSatellitesAudibleOutput, CenterBassMutedOutput;
        public double PlaybackLinearGain;
        public bool DefaultEndpointChanged = false, EndpointVolumeChanged = false, HidOperationsPerformed = false;
        public bool OpticalSourceValidated = false, Ac3Validated = false, PhysicalChannelsValidated = false, LatencyMeasured = false;
        public List<string> CleanupErrors = new List<string>();
        public string[] Limitations = {
            "Live carrier bytes are copied without PCM conversion; silent WASAPI packets are represented by zero bytes.",
            "Successful transport and mpv exit do not prove physical channels, DAC output or acoustic latency; inspect the private decoder log.",
            "The probe always uses mpv volume=0 and mute=yes. There is no unmute option.",
            "Capture, stdin writes and process lifetime are bounded. Individual WASAPI COM calls remain controlled by the driver."
        };
    }

    public sealed class SpdifRelayLogValidation
    {
        public bool SixChannelUsbOutputNegotiated, Ac3SixChannelSourceDetected, DecoderCrcCheckingEnabled, DecoderLogClean, Complete;
        public List<string> Problems = new List<string>();
    }

    public static class WindowsSpdifRelay
    {
        private static readonly Guid AudioId = new Guid("1CB9AD4C-DBFA-4C32-B178-C2F568A703B2");
        private static readonly Guid CaptureId = new Guid("C8ADBD64-E71E-48A0-A4DE-185C395CD317");
        [DllImport("ole32.dll")] private static extern int PropVariantClear(ref PropertyValue value);
        private static void Require(int hr, string operation)
        {
            if (hr < 0) throw new COMException(operation + " returned 0x" + unchecked((uint)hr).ToString("X8"), hr);
        }
        private static void Release(object value, SpdifRelayResult result)
        {
            try { if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value); }
            catch (Exception error) { result.CleanupErrors.Add(error.Message); }
        }
        private static void ReleasePointer(IntPtr pointer, SpdifRelayResult result)
        {
            try { if (pointer != IntPtr.Zero) Marshal.Release(pointer); }
            catch (Exception error) { result.CleanupErrors.Add(error.Message); }
        }
        private static string FriendlyName(IDevice device)
        {
            IProperties properties = null;
            try {
                Require(device.OpenPropertyStore(0, out properties), "OpenPropertyStore read-only");
                PropertyKey key = new PropertyKey("A45C254E-DF1C-4EFD-8020-67D146A850E0", 14);
                PropertyValue value; Require(properties.GetValue(ref key, out value), "Get friendly name");
                try { return value.Describe(); } finally { PropVariantClear(ref value); }
            } finally { if (properties != null) Marshal.ReleaseComObject(properties); }
        }
        private static void VerifyEndpoint(string id, bool capture)
        {
            string prefix = capture ? "{0.0.1.00000000}." : "{0.0.0.00000000}.";
            Guid parsed;
            if (id == null || !id.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) || !Guid.TryParse(id.Substring(prefix.Length), out parsed))
                throw new ArgumentException("An explicit " + (capture ? "capture" : "render") + " endpoint ID is required.");
        }
        private static IntPtr Format(int channels, int mask)
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
            IntPtr pointer = Marshal.AllocHGlobal(40); Marshal.Copy(bytes, 0, pointer, 40); return pointer;
        }
        private static string Quote(string value)
        {
            StringBuilder quoted = new StringBuilder("\""); int slashes = 0;
            foreach (char c in value) {
                if (c == '\\') { slashes++; continue; }
                if (c == '"') { quoted.Append('\\', slashes * 2 + 1); quoted.Append(c); slashes = 0; continue; }
                quoted.Append('\\', slashes); slashes = 0; quoted.Append(c);
            }
            quoted.Append('\\', slashes * 2).Append('"'); return quoted.ToString();
        }
        private static string PrivatePath(string root, string path, string suffix)
        {
            string full = Path.GetFullPath(path);
            if (!full.StartsWith(root, StringComparison.OrdinalIgnoreCase) || !full.EndsWith(suffix, StringComparison.OrdinalIgnoreCase))
                throw new ArgumentException("Probe artifacts must remain inside android-a34/artifacts with suffix " + suffix);
            if (File.Exists(full)) throw new IOException("Refusing to reuse probe artifact: " + full);
            return full;
        }

        // Pure validation: no device access or process launch. All evidence must be in the completed log.
        public static SpdifRelayLogValidation ValidateDecoderLog(string log, string renderEndpointId)
        {
            SpdifRelayLogValidation result = new SpdifRelayLogValidation();
            if (string.IsNullOrEmpty(log)) { result.Problems.Add("Decoder log is missing or empty."); return result; }
            string guid = renderEndpointId == null ? "" : renderEndpointId.Substring(renderEndpointId.LastIndexOf('.') + 1);
            bool selectedUsb = guid.Length > 0 && Regex.IsMatch(log,
                @"(?m)^.*\[ao/wasapi\].*Selecting device '" + Regex.Escape(guid) + @"'.*USB Sound Device");
            MatchCollection outputs = Regex.Matches(log, @"(?m)^.*\[cplayer\].*AO: \[wasapi\] ([^\r\n]+)");
            bool expectedOutput = outputs.Count > 0;
            foreach (Match output in outputs) {
                if (!Regex.IsMatch(output.Groups[1].Value.Trim(), @"^48000Hz 5\.1\(side\) 6ch s16$")) expectedOutput = false;
            }
            result.SixChannelUsbOutputNegotiated = selectedUsb && expectedOutput;
            result.Ac3SixChannelSourceDetected = Regex.IsMatch(log, @"(?m)^.*\[cplayer\].*Audio\s+--aid=\d+\s+\(ac3 6ch 48000 Hz(?:\s|\))") &&
                Regex.IsMatch(log, @"(?m)^.*\[ad\].*Selected decoder: ac3(?:\s|_)");
            result.DecoderCrcCheckingEnabled = Regex.IsMatch(log,
                @"(?m)^.*\[cplayer\].*Setting option 'ad-lavc-o' = 'err_detect=crccheck\+explode'");
            bool errors = Regex.IsMatch(log, @"(?m)^\[[^\]\r\n]+\]\[(?:e|f)\]") ||
                Regex.IsMatch(log, @"(?im)^.*\[(?:ffmpeg[^\]]*|ad)\].*\b(?:crc\s+(?:mismatch|error)|invalid\s+crc|(?:error|failed)\s+(?:while\s+)?decod\w*|invalid\s+data|frame\s+sync\s+error)");
            result.DecoderLogClean = !errors;
            if (!result.SixChannelUsbOutputNegotiated) result.Problems.Add("The explicit USB output did not negotiate exclusively 48000Hz 5.1(side) 6ch s16 in the log.");
            if (!result.Ac3SixChannelSourceDetected) result.Problems.Add("AC-3 six-channel 48 kHz source and decoder were not both observed.");
            if (!result.DecoderCrcCheckingEnabled) result.Problems.Add("Strict CRC decoder option was not confirmed in the log.");
            if (!result.DecoderLogClean) result.Problems.Add("Decoder log contains an error/fatal message or codec failure.");
            result.Complete = result.SixChannelUsbOutputNegotiated && result.Ac3SixChannelSourceDetected &&
                result.DecoderCrcCheckingEnabled && result.DecoderLogClean;
            return result;
        }

        public static SpdifRelayResult Run(string captureEndpointId, string renderEndpointId, string mpvPath,
            string pcmFile, string mpvLogFile, string stopFile, string artifactRoot, int seconds)
        {
            return RunCore(captureEndpointId, renderEndpointId, mpvPath, pcmFile, mpvLogFile, stopFile, artifactRoot, seconds, false, 0, false);
        }

        public static SpdifRelayResult RunFronts(string captureEndpointId, string renderEndpointId, string mpvPath,
            string pcmFile, string mpvLogFile, string stopFile, string artifactRoot, int seconds, int volumePercent)
        {
            return RunCore(captureEndpointId, renderEndpointId, mpvPath, pcmFile, mpvLogFile, stopFile, artifactRoot, seconds, true, volumePercent, false);
        }

        public static SpdifRelayResult RunSixChannels(string captureEndpointId, string renderEndpointId, string mpvPath,
            string pcmFile, string mpvLogFile, string stopFile, string artifactRoot, int seconds, int volumePercent)
        {
            return RunCore(captureEndpointId, renderEndpointId, mpvPath, pcmFile, mpvLogFile, stopFile, artifactRoot, seconds, false, volumePercent, true);
        }

        public static SpdifRelayResult RunSatellites(string captureEndpointId, string renderEndpointId, string mpvPath,
            string pcmFile, string mpvLogFile, string stopFile, string artifactRoot, int seconds, int volumePercent)
        {
            return RunCore(captureEndpointId, renderEndpointId, mpvPath, pcmFile, mpvLogFile, stopFile, artifactRoot, seconds, false, volumePercent, true, true);
        }

        private static SpdifRelayResult RunCore(string captureEndpointId, string renderEndpointId, string mpvPath,
            string pcmFile, string mpvLogFile, string stopFile, string artifactRoot, int seconds, bool frontsOnly, int volumePercent, bool allChannels, bool centerBassMuted = false)
        {
            SpdifRelayResult result = new SpdifRelayResult { CaptureEndpointId = captureEndpointId, RenderEndpointId = renderEndpointId, Seconds = seconds };
            Stopwatch total = Stopwatch.StartNew(), captureTime = new Stopwatch();
            IDeviceEnumerator enumerator = null; IDevice input = null, output = null;
            IAudioClient audio = null, renderProbe = null; ISpdifCaptureClient capture = null;
            IntPtr audioPointer = IntPtr.Zero, renderPointer = IntPtr.Zero, capturePointer = IntPtr.Zero;
            IntPtr captureFormat = IntPtr.Zero, renderFormat = IntPtr.Zero;
            Process player = null; FileStream raw = null; Task pendingWrite = null; bool started = false;
            try {
                bool audible = frontsOnly || allChannels;
                int maximumGain = allChannels ? 60 : 25;
                if (audible && (volumePercent < 1 || volumePercent > maximumGain))
                    throw new ArgumentOutOfRangeException("volumePercent", "Linear gain exceeds the selected listening mode's limit.");
                result.FrontsOnlyOutput = frontsOnly;
                result.SixChannelsAudibleOutput = allChannels && !centerBassMuted;
                result.FourSatellitesAudibleOutput = centerBassMuted;
                result.CenterBassMutedOutput = centerBassMuted;
                result.PlaybackMuted = !audible;
                // mpv's volume slider is cubic. Apply the requested linear gain
                // explicitly in the decoded PCM pan filter, with player volume 100.
                result.PlaybackVolume = audible ? 100 : 0;
                result.PlaybackLinearGain = audible ? volumePercent / 100.0 : 0;
                if (frontsOnly) {
                    result.Kind = "windows_spdif_live_decode_usb_fronts_listening_probe";
                    result.Limitations[2] = "Only FL/FR are enabled after decoding, at an explicit 1..25 percent linear PCM gain. Other four channels are zeroed.";
                }
                if (allChannels) {
                    result.Kind = "windows_spdif_live_decode_usb_six_channel_listening_probe";
                    result.Limitations[2] = "All six decoded channels are enabled at the same explicit 1..60 percent linear PCM gain.";
                }
                if (centerBassMuted) {
                    result.Kind = "windows_spdif_live_decode_usb_four_satellites_probe";
                    result.Limitations[2] = "FC and LFE are zeroed after decoding; only the four satellites are enabled.";
                }
                if (seconds < 5 || seconds > (allChannels ? 30 : 15))
                    throw new ArgumentOutOfRangeException("seconds", "Duration exceeds the selected probe mode's bounded limit.");
                if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA) throw new InvalidOperationException("Use an STA PowerShell process.");
                result.MaximumFrames = seconds * 48000; result.DeadlineMilliseconds = seconds * 1000 + 7000;
                VerifyEndpoint(captureEndpointId, true); VerifyEndpoint(renderEndpointId, false);
                string root = Path.GetFullPath(artifactRoot).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                if (!root.EndsWith("android-a34" + Path.DirectorySeparatorChar + "artifacts" + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                    throw new ArgumentException("Artifact root must be this repository's android-a34/artifacts directory.");
                result.PcmFile = PrivatePath(root, pcmFile, ".pcm"); result.MpvLogFile = PrivatePath(root, mpvLogFile, ".log");
                result.StopFile = PrivatePath(root, stopFile, ".stop");
                if (!File.Exists(mpvPath)) throw new FileNotFoundException("mpv is missing.", mpvPath);
                enumerator = (IDeviceEnumerator)new DeviceEnumeratorClass();
                Require(enumerator.GetDevice(captureEndpointId, out input), "Get explicit input");
                Require(enumerator.GetDevice(renderEndpointId, out output), "Get explicit output");
                int state; Require(input.GetState(out state), "Input GetState"); if (state != 1) throw new IOException("Input is inactive.");
                Require(output.GetState(out state), "Output GetState"); if (state != 1) throw new IOException("Output is inactive.");
                result.CaptureFriendlyName = FriendlyName(input); result.RenderFriendlyName = FriendlyName(output);
                if (result.CaptureFriendlyName == null || result.CaptureFriendlyName.IndexOf("SPDIF", StringComparison.OrdinalIgnoreCase) < 0 ||
                    result.CaptureFriendlyName.IndexOf("USB Sound Device", StringComparison.OrdinalIgnoreCase) < 0 ||
                    result.CaptureFriendlyName.IndexOf("Microphone", StringComparison.OrdinalIgnoreCase) >= 0 || result.CaptureFriendlyName.IndexOf("Line", StringComparison.OrdinalIgnoreCase) >= 0)
                    throw new IOException("Input must be the SPDIF input of USB Sound Device.");
                if (result.RenderFriendlyName == null || result.RenderFriendlyName.IndexOf("USB Sound Device", StringComparison.OrdinalIgnoreCase) < 0 ||
                    result.RenderFriendlyName.IndexOf("SPDIF", StringComparison.OrdinalIgnoreCase) >= 0)
                    throw new IOException("Output must be the analog PCM render endpoint of USB Sound Device.");
                Guid iid = AudioId;
                Require(input.Activate(ref iid, 23, IntPtr.Zero, out audioPointer), "Activate input");
                audio = (IAudioClient)Marshal.GetTypedObjectForIUnknown(audioPointer, typeof(IAudioClient));
                Require(output.Activate(ref iid, 23, IntPtr.Zero, out renderPointer), "Activate output for format preflight only");
                renderProbe = (IAudioClient)Marshal.GetTypedObjectForIUnknown(renderPointer, typeof(IAudioClient));
                captureFormat = Format(2, 3); renderFormat = Format(6, 0x60F);
                if (audio.IsFormatSupported(1, captureFormat, IntPtr.Zero) != 0) throw new IOException("Exclusive carrier PCM16/2ch/48k is unsupported.");
                result.CaptureExactFormatSupported = true;
                if (renderProbe.IsFormatSupported(1, renderFormat, IntPtr.Zero) != 0) throw new IOException("Exclusive PCM16/6ch/48k/0x60F output is unsupported.");
                result.RenderExactFormatSupported = true;
                // The probe never initializes the render client; mpv owns only the explicit USB output.
                Release(renderProbe, result); renderProbe = null; ReleasePointer(renderPointer, result); renderPointer = IntPtr.Zero;
                Require(audio.Initialize(1, 0, 1000000, 100000, captureFormat, IntPtr.Zero), "Initialize exclusive input");
                Require(audio.GetBufferSize(out result.CaptureBufferFrames), "Input buffer size");
                if (result.CaptureBufferFrames == 0 || result.CaptureBufferFrames > 48000) throw new IOException("Unexpected input buffer size.");
                iid = CaptureId; Require(audio.GetService(ref iid, out capturePointer), "Capture service");
                capture = (ISpdifCaptureClient)Marshal.GetTypedObjectForIUnknown(capturePointer, typeof(ISpdifCaptureClient));
                byte[] buffer = new byte[checked((int)result.CaptureBufferFrames * 4)];
                if (total.ElapsedMilliseconds >= result.DeadlineMilliseconds) throw new TimeoutException("Preflight exceeded total deadline.");
                Directory.CreateDirectory(Path.GetDirectoryName(result.PcmFile));
                raw = new FileStream(result.PcmFile, FileMode.CreateNew, FileAccess.Write, FileShare.Read);
                string[] args = { "--no-config", "--load-scripts=no", "--no-terminal", "--no-video", "--ao=wasapi", "--audio-exclusive=yes",
                    "--audio-fallback-to-null=no", "--audio-device=wasapi/" + renderEndpointId.Substring("{0.0.0.00000000}.".Length),
                    "--audio-spdif=", "--audio-channels=5.1(side)", "--audio-format=s16", "--audio-samplerate=48000", "--ad-lavc-downmix=no",
                    "--ad-lavc-o=err_detect=crccheck+explode", "--volume=" + result.PlaybackVolume,
                    audible ? "--mute=no" : "--mute=yes", "--demuxer=lavf", "--demuxer-lavf-format=spdif",
                    "--demuxer-lavf-analyzeduration=0.1", "--demuxer-lavf-probesize=8192",
                    "--log-file=" + result.MpvLogFile, "--msg-level=all=info,ao/wasapi=debug", "-" };
                List<string> playerArgs = new List<string>(args);
                if (frontsOnly) {
                    string gain = result.PlaybackLinearGain.ToString("0.00", CultureInfo.InvariantCulture);
                    playerArgs.Insert(playerArgs.Count - 1,
                        "--af=lavfi=[pan=5.1(side)|c0=" + gain + "*c0|c1=" + gain + "*c1|c2=0*c2|c3=0*c3|c4=0*c4|c5=0*c5]");
                }
                if (allChannels) {
                    string gain = result.PlaybackLinearGain.ToString("0.00", CultureInfo.InvariantCulture);
                    string centerGain = centerBassMuted ? "0" : gain;
                    playerArgs.Insert(playerArgs.Count - 1,
                        "--af=lavfi=[pan=5.1(side)|c0=" + gain + "*c0|c1=" + gain + "*c1|c2=" + centerGain +
                        "*c2|c3=" + centerGain + "*c3|c4=" + gain + "*c4|c5=" + gain + "*c5]");
                }
                StringBuilder command = new StringBuilder(); foreach (string arg in playerArgs) { if (command.Length > 0) command.Append(' '); command.Append(Quote(arg)); }
                result.MpvArguments = command.ToString();
                ProcessStartInfo info = new ProcessStartInfo(mpvPath, result.MpvArguments) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true };
                player = Process.Start(info); if (player == null) throw new IOException("mpv did not start."); result.OwnedMpvPid = player.Id;
                Require(audio.Start(), "Start input"); started = true; result.PlaybackPerformed = true; captureTime.Start();
                long nextHeartbeat = 0;
                while (captureTime.ElapsedMilliseconds < seconds * 1000L && result.Frames < result.MaximumFrames) {
                    if (File.Exists(result.StopFile)) { result.StopRequested = true; break; }
                    if (total.ElapsedMilliseconds >= result.DeadlineMilliseconds) throw new TimeoutException("Relay exceeded total deadline.");
                    if (player.HasExited) throw new IOException("Owned mpv exited before capture ended with code " + player.ExitCode);
                    if (captureTime.ElapsedMilliseconds >= nextHeartbeat) {
                        result.Heartbeats++; Console.WriteLine((audible ? "Audible optical relay: " : "Muted optical relay: ") + "frames=" + result.Frames + " sentBytes=" + result.BytesSent);
                        nextHeartbeat = captureTime.ElapsedMilliseconds + 1000;
                    }
                    IntPtr data; uint frames, flags; ulong position, qpc;
                    Require(capture.GetBuffer(out data, out frames, out flags, out position, out qpc), "Capture GetBuffer");
                    if (frames == 0) { result.EmptyPolls++; Thread.Sleep(1); continue; }
                    int count;
                    try {
                        if (frames > result.CaptureBufferFrames) throw new IOException("Capture packet exceeds initialized buffer.");
                        count = checked((int)Math.Min((long)frames, result.MaximumFrames - result.Frames) * 4);
                        if ((flags & 2) != 0) { Array.Clear(buffer, 0, count); result.SilentPackets++; }
                        else { if (data == IntPtr.Zero) throw new IOException("Non-silent packet has no data."); Marshal.Copy(data, buffer, 0, count); }
                        if ((flags & 1) != 0) result.DiscontinuityPackets++; if ((flags & 4) != 0) result.TimestampErrorPackets++;
                        result.Packets++;
                    } finally { Require(capture.ReleaseBuffer(frames), "Capture ReleaseBuffer"); }
                    // Release the driver buffer before disk or stdin IO; carrier bytes remain unchanged.
                    raw.Write(buffer, 0, count); result.Bytes += count; result.Frames += count / 4;
                    Stopwatch writeTime = Stopwatch.StartNew();
                    pendingWrite = player.StandardInput.BaseStream.WriteAsync(buffer, 0, count);
                    while (!pendingWrite.Wait(25)) {
                        if (File.Exists(result.StopFile)) { result.StopRequested = true; throw new IOException("Stop requested during stdin write."); }
                        if (writeTime.ElapsedMilliseconds >= result.WriteDeadlineMilliseconds || total.ElapsedMilliseconds >= result.DeadlineMilliseconds)
                            throw new TimeoutException("mpv stdin write exceeded bounded deadline.");
                    }
                    result.MaximumWriteMilliseconds = Math.Max(result.MaximumWriteMilliseconds, writeTime.Elapsed.TotalMilliseconds);
                    pendingWrite = null; result.BytesSent += count;
                }
                result.Outcome = result.StopRequested ? "stopped_by_sentinel" : (allChannels ? "carrier_relay_completed_six_channels" : (frontsOnly ? "carrier_relay_completed_fronts_only" : "carrier_relay_completed_output_muted"));
            } catch (Exception error) {
                result.Error = error.GetBaseException().Message; result.Outcome = "failed"; result.TimedOut = error is TimeoutException;
            } finally {
                result.CaptureMilliseconds = captureTime.Elapsed.TotalMilliseconds;
                if (started) { try { Require(audio.Stop(), "Stop input"); } catch (Exception error) { result.CleanupErrors.Add(error.Message); } }
                if (player != null) {
                    try { player.StandardInput.Close(); result.StdinClosed = true; } catch (Exception error) { result.CleanupErrors.Add("Close owned stdin: " + error.Message); }
                    try {
                        if (!player.WaitForExit(4000)) {
                            player.Kill(); result.OwnedMpvKilledAfterExitDeadline = true;
                            if (!player.WaitForExit(2000)) result.CleanupErrors.Add("Owned mpv did not exit after kill deadline.");
                        }
                        if (player.HasExited) result.MpvExitCode = player.ExitCode;
                    } catch (Exception error) { result.CleanupErrors.Add("Owned mpv cleanup: " + error.Message); }
                    if (pendingWrite != null) { try { if (!pendingWrite.Wait(250)) result.CleanupErrors.Add("Pending stdin write did not finish after owned process exit."); } catch (Exception error) { result.CleanupErrors.Add("Pending write: " + error.GetBaseException().Message); } }
                    player.Dispose();
                }
                if (raw != null) { try { raw.Dispose(); } catch (Exception error) { result.CleanupErrors.Add("Raw file cleanup: " + error.Message); } }
                Release(capture, result); ReleasePointer(capturePointer, result); Release(audio, result); ReleasePointer(audioPointer, result);
                Release(renderProbe, result); ReleasePointer(renderPointer, result); Release(input, result); Release(output, result); Release(enumerator, result);
                if (captureFormat != IntPtr.Zero) Marshal.FreeHGlobal(captureFormat); if (renderFormat != IntPtr.Zero) Marshal.FreeHGlobal(renderFormat);
                if (result.MpvLogFile != null) {
                    try {
                        SpdifRelayLogValidation validation = ValidateDecoderLog(File.Exists(result.MpvLogFile) ? File.ReadAllText(result.MpvLogFile) : null, renderEndpointId);
                        result.SixChannelUsbOutputNegotiated = validation.SixChannelUsbOutputNegotiated;
                        result.Ac3SixChannelSourceDetected = validation.Ac3SixChannelSourceDetected;
                        result.DecoderCrcCheckingEnabled = validation.DecoderCrcCheckingEnabled;
                        result.DecoderLogClean = validation.DecoderLogClean;
                        result.DecoderLogProblems.AddRange(validation.Problems);
                    } catch (Exception error) { result.DecoderLogProblems.Add("Decoder log read failed: " + error.Message); }
                }
                result.CleanupComplete = result.CleanupErrors.Count == 0;
                result.Ok = result.Error == null && result.BytesSent > 0 && result.BytesSent == result.Bytes && result.MpvExitCode == 0 &&
                    !result.OwnedMpvKilledAfterExitDeadline && result.CleanupComplete && !result.StopRequested &&
                    result.SixChannelUsbOutputNegotiated && result.Ac3SixChannelSourceDetected && result.DecoderCrcCheckingEnabled && result.DecoderLogClean;
                if (!result.Ok && result.Error == null && !result.StopRequested) result.Outcome = "transport_or_owned_decoder_incomplete";
                result.TotalMilliseconds = total.Elapsed.TotalMilliseconds;
            }
            return result;
        }
    }
}
