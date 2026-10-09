// Sustained IEC61937 carrier capture. The CM6206 USB endpoint transports the
// encoded carrier as PCM16 bytes; it must never pass through a PCM gain/mixer.
// Decode AC-3 before applying the output gain and the physical USB channel map.
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
    public sealed class SpdifContinuousResult
    {
        public string Status = "starting", Error, CaptureEndpointId, RenderEndpointId, CaptureFriendlyName, RenderFriendlyName;
        public string StartedAtUtc = DateTime.UtcNow.ToString("o"), UpdatedAtUtc, MpvLogFile, StopFile, StatusFile, MpvArguments;
        public long CaptureFrames, CarrierBytesSent, CapturePackets, SilentPackets, DiscontinuityPackets, TimestampErrorPackets;
        public double ElapsedMilliseconds, MaximumWriteMilliseconds, LinearGain;
        public int? OwnedMpvPid, MpvExitCode;
        public int SourceChannels;
        public int RequestedOutputChannels = 8;
        public bool NativeUsbOutputNegotiated;
        public int CrcRejectedFrames;
        public bool Ac3SourceDetected, EightChannelUsbOutputNegotiated, DecoderLogClean = true, DecoderSelected, CrcCheckingEnabled;
        public bool SourceFormatValidated, Ready, StopRequested, CleanupComplete, OwnedMpvKilledAfterExitDeadline;
        public bool DefaultEndpointChanged = false, EndpointVolumeChanged = false, HidOperationsPerformed = false;
        public bool PhysicalChannelsValidated = false, BitPerfectValidated = false, LatencyMeasured = false;
        public string[] Limitations = {
            "CarrierBytesSent/CaptureFrames measure the encoded IEC61937 carrier, not decoded PCM samples.",
            "Ready requires AC-3 six-channel decoder and explicit USB 8-channel AO negotiation; it does not certify audible speakers.",
            "FC is USB slot 2 and LFE slot 3. Surround is provisionally duplicated into both USB rear pairs until hardware identification.",
            "The runner decodes to PCM for analog outputs; it does not claim compressed passthrough to the analog DAC.",
            "WASAPI COM calls remain driver-controlled. Stop/process/write deadlines cannot interrupt a blocked native driver call."
        };
        public List<string> CleanupErrors = new List<string>();
    }

    public static partial class WindowsSpdifRelay
    {
        // At 48 kHz: FL/FR 76.792 ms, FC/LFE 5.792 ms, SL/SR 71 ms.
        // These are relative DSP delays requested for this speaker chain.
        public const string DefaultNativeDelaySamples = "3686S|3686S|278S|278S|3408S|3408S";
        public const string DefaultNativeDelaySamplesCsv = "3686,3686,278,278,3408,3408";
        public static int[] ParseDelaySamplesCsv(string csv)
        {
            if (csv == null || !Regex.IsMatch(csv, @"^[0-9]+(?:,[0-9]+){5}$"))
                throw new ArgumentException("DelaySamplesCsv must contain exactly six nonnegative integers.");
            string[] parts = csv.Split(','); int[] samples = new int[6];
            for (int index = 0; index < 6; index++)
                if (!int.TryParse(parts[index], NumberStyles.None, CultureInfo.InvariantCulture, out samples[index]) || samples[index] > 96000)
                    throw new ArgumentOutOfRangeException("DelaySamplesCsv", "Each delay must be between 0 and 96000 samples.");
            return samples;
        }
        public static string DefaultNativeDspFilter()
        {
            return DefaultNativeDspFilter(DefaultNativeDelaySamplesCsv);
        }
        public static string DefaultNativeDspFilter(string delaySamplesCsv)
        {
            int[] samples = ParseDelaySamplesCsv(delaySamplesCsv);
            StringBuilder delays = new StringBuilder();
            foreach (int value in samples) { if (delays.Length > 0) delays.Append('|'); delays.Append(value.ToString(CultureInfo.InvariantCulture)).Append('S'); }
            return "lavfi=[adelay=" + delays +
                ",pan=7.1|c0=c0|c1=c1|c2=c2|c3=c3|c4=c4|c5=c5|c6=c4|c7=c5]";
        }
        // Shared by file-only tests. A custom config contributes only its single
        // af line; source/output/process options remain owned by this runner.
        public static string BuildContinuousMpvArguments(string renderEndpointId, string logFile, double gain, bool shared, string configPath)
        {
            return BuildContinuousMpvArguments(renderEndpointId, logFile, gain, shared, configPath, null);
        }

        public static string BuildContinuousMpvArguments(string renderEndpointId, string logFile, double gain, bool shared, string configPath, string ipcPath)
        {
            return BuildContinuousMpvArguments(renderEndpointId, logFile, gain, shared, configPath, ipcPath, false);
        }

        public static string BuildContinuousMpvArguments(string renderEndpointId, string logFile, double gain, bool shared, string configPath, string ipcPath, bool muted)
        {
            return BuildContinuousMpvArguments(renderEndpointId, logFile, gain, shared, configPath, ipcPath, muted, DefaultNativeDelaySamplesCsv);
        }

        public static string BuildContinuousMpvArguments(string renderEndpointId, string logFile, double gain, bool shared, string configPath, string ipcPath, bool muted, string delaySamplesCsv, int outputChannels = 8)
        {
            VerifyEndpoint(renderEndpointId, false);
            if (outputChannels != 6 && outputChannels != 8) throw new ArgumentException("USB output must contain six or eight channels.");
            ParseDelaySamplesCsv(delaySamplesCsv);
            if (double.IsNaN(gain) || double.IsInfinity(gain) || gain < 0 || gain > 1)
                throw new ArgumentOutOfRangeException("gain", "Use a decoded PCM linear gain between 0 and 1.");
            // mpv's volume scale is cubic. Public Gain remains a linear PCM
            // amplitude, so the system controller can use the same conversion.
            string playerVolume = (100 * Math.Pow(gain, 1.0 / 3)).ToString("0.########", CultureInfo.InvariantCulture);
            List<string> args = new List<string> { "--no-config", "--load-scripts=no" };
            args.AddRange(new string[] {
                "--no-terminal", "--no-video", "--ao=wasapi", "--audio-exclusive=" + (shared ? "no" : "yes"),
                "--audio-fallback-to-null=no", "--audio-device=wasapi/" + renderEndpointId.Substring("{0.0.0.00000000}.".Length),
                "--audio-spdif=", "--audio-channels=7.1", "--audio-format=s16", "--audio-samplerate=48000", "--ad-lavc-downmix=no",
                "--ad-lavc-o=err_detect=crccheck+explode", "--volume=" + playerVolume, "--volume-max=100", "--mute=" + (muted ? "yes" : "no"),
                "--demuxer=lavf", "--demuxer-lavf-format=spdif", "--demuxer-lavf-analyzeduration=0.1", "--demuxer-lavf-probesize=8192",
                "--cache=no", "--demuxer-readahead-secs=0", "--demuxer-max-bytes=256KiB", "--audio-buffer=0.040",
                "--input-default-bindings=no", "--osc=no", "--media-controls=no", "--input-media-keys=no",
                "--log-file=" + logFile, "--msg-level=all=info,cplayer=v,ad=v,ao/wasapi=debug"
            });
            if (string.IsNullOrEmpty(configPath)) {
                // Native decoded 5.1 positions remain independent. The CM6206's
                // active analog rear pair still needs a physical identification.
                // Delay the six logical channels once, then map/duplicate their
                // already delayed samples into the eight physical USB positions.
                args.Add("--af=" + DefaultNativeDspFilter(delaySamplesCsv));
            } else args.Add("--af=" + ReadNativeDspFilterFromConfig(configPath));
            if (!string.IsNullOrEmpty(ipcPath)) {
                if (!Regex.IsMatch(ipcPath, @"^\\\\\.\\pipe\\[A-Za-z0-9._-]{1,80}$"))
                    throw new ArgumentException("Use an explicit local named pipe for mpv IPC.");
                args.Add("--input-ipc-server=" + ipcPath);
            }
            args.Add("-");
            StringBuilder command = new StringBuilder();
            foreach (string arg in args) { if (command.Length > 0) command.Append(' '); command.Append(Quote(arg)); }
            string commandText = command.ToString();
            if (outputChannels == 6) commandText = commandText.Replace("--audio-channels=7.1", "--audio-channels=5.1(side)")
                .Replace("pan=7.1|c0=c0|c1=c1|c2=c2|c3=c3|c4=c4|c5=c5|c6=c4|c7=c5", "pan=5.1(side)|c0=c0|c1=c1|c2=c2|c3=c3|c4=c4|c5=c5");
            return commandText;
        }

        public static void ObserveContinuousLog(SpdifContinuousResult result, string log)
        {
            if (string.IsNullOrEmpty(log)) return;
            string guid = result.RenderEndpointId.Substring("{0.0.0.00000000}.".Length);
            bool selectedUsb = Regex.IsMatch(log,
                @"(?m)^.*\[ao/wasapi\].*Selecting device '" + Regex.Escape(guid) + @"'.*USB Sound Device");
            MatchCollection outputs = Regex.Matches(log, @"(?m)^.*\[cplayer\].*AO: \[wasapi\] ([^\r\n]+)");
            foreach (Match output in outputs) {
                string expected = result.RequestedOutputChannels == 6 ? @"^48000Hz 5\.1\(side\) 6ch s16$" : @"^48000Hz 7\.1 8ch s16$";
                if (!Regex.IsMatch(output.Groups[1].Value.Trim(), expected))
                    throw new IOException("USB output negotiated an unexpected format: " + output.Groups[1].Value.Trim());
                if (selectedUsb || result.NativeUsbOutputNegotiated) {
                    result.NativeUsbOutputNegotiated = true;
                    result.EightChannelUsbOutputNegotiated = result.RequestedOutputChannels == 8;
                }
            }
            MatchCollection sources = Regex.Matches(log, @"(?m)^.*\[cplayer\].*Audio\s+--aid=\d+\s+\(ac3 (\d+)ch 48000 Hz(?:\s|\))");
            foreach (Match source in sources) {
                result.SourceChannels = int.Parse(source.Groups[1].Value, CultureInfo.InvariantCulture);
                if (result.SourceChannels != 6) throw new IOException("Optical native route requires a decoded AC-3 6-channel source; observed " + result.SourceChannels + ".");
                result.Ac3SourceDetected = true;
            }
            ObserveDecoderEvidence(result, log);
            // The decoder still uses crccheck+explode and rejects these frames.
            // A corrupt burst during lock/source transitions must not permanently
            // tear down an otherwise valid stream. Other errors remain fatal.
            result.CrcRejectedFrames = Regex.Matches(log, @"(?im)^.*\[ffmpeg[^\]]*\].*\b(?:frame\s+CRC\s+mismatch|invalid\s+CRC|CRC\s+error)").Count;
            if (result.CrcRejectedFrames > 0) result.DecoderLogClean = false;
            string checkedLog = Regex.Replace(log,
                @"(?im)^.*\[ffmpeg[^\]]*\].*\b(?:frame\s+CRC\s+mismatch|invalid\s+CRC|CRC\s+error)[^\r\n]*(?:\r?\n.*\[ad\].*Error decoding audio\.[^\r\n]*)?", "");
            bool errors = Regex.IsMatch(checkedLog, @"(?m)^\[[^\]\r\n]+\]\[(?:e|f)\]") ||
                Regex.IsMatch(checkedLog, @"(?im)^.*\[(?:ffmpeg[^\]]*|ad)\].*\b(?:(?:error|failed)\s+(?:while\s+)?decod\w*|invalid\s+data|frame\s+sync\s+error)");
            if (errors) { result.DecoderLogClean = false; throw new IOException("The optical carrier decoder or output reported an error; inspect the private mpv log."); }
            result.SourceFormatValidated = result.Ac3SourceDetected && result.SourceChannels == 6 && result.DecoderSelected && result.CrcCheckingEnabled;
            result.Ready = result.SourceFormatValidated && result.NativeUsbOutputNegotiated && result.CarrierBytesSent > 0;
        }

        public static string ReadNativeDspFilterFromConfig(string configPath)
        {
            string filter = null;
            foreach (string raw in File.ReadAllLines(configPath)) {
                string line = raw.Trim();
                if (!line.StartsWith("af=", StringComparison.Ordinal)) continue;
                if (filter != null) throw new ArgumentException("DSP config must contain exactly one af line.");
                filter = line.Substring(3).Trim();
            }
            if (string.IsNullOrEmpty(filter) || !filter.StartsWith("lavfi=[", StringComparison.Ordinal) || !filter.EndsWith("]", StringComparison.Ordinal))
                throw new ArgumentException("DSP config must contain one explicit lavfi af graph for native six channels.");
            return filter;
        }

        private static void ObserveDecoderEvidence(SpdifContinuousResult result, string log)
        {
            if (Regex.IsMatch(log, @"(?m)^.*\[ad\].*Selected decoder: ac3(?:\s|_)")) result.DecoderSelected = true;
            if (Regex.IsMatch(log, @"(?m)^.*\[cplayer\].*Setting option 'ad-lavc-o' = 'err_detect=crccheck\+explode'")) result.CrcCheckingEnabled = true;
        }

        private static string JsonString(string value)
        {
            if (value == null) return "null";
            StringBuilder b = new StringBuilder("\"");
            foreach (char c in value) {
                if (c == '"' || c == '\\') b.Append('\\').Append(c);
                else if (c < 32) b.Append("\\u").Append(((int)c).ToString("x4"));
                else b.Append(c);
            }
            return b.Append('"').ToString();
        }

        private static string Bool(bool value) { return value ? "true" : "false"; }
        private static void SaveContinuousStatus(SpdifContinuousResult r, Stopwatch elapsed)
        {
            r.UpdatedAtUtc = DateTime.UtcNow.ToString("o"); r.ElapsedMilliseconds = elapsed.Elapsed.TotalMilliseconds;
            string json = "{" +
                "\"Status\":" + JsonString(r.Status) + ",\"Error\":" + JsonString(r.Error) +
                ",\"StartedAtUtc\":" + JsonString(r.StartedAtUtc) + ",\"UpdatedAtUtc\":" + JsonString(r.UpdatedAtUtc) +
                ",\"CaptureEndpointId\":" + JsonString(r.CaptureEndpointId) + ",\"RenderEndpointId\":" + JsonString(r.RenderEndpointId) +
                ",\"OwnedMpvPid\":" + (r.OwnedMpvPid.HasValue ? r.OwnedMpvPid.Value.ToString(CultureInfo.InvariantCulture) : "null") +
                ",\"CaptureFrames\":" + r.CaptureFrames + ",\"CarrierBytesSent\":" + r.CarrierBytesSent +
                ",\"CapturePackets\":" + r.CapturePackets + ",\"SilentPackets\":" + r.SilentPackets +
                ",\"DiscontinuityPackets\":" + r.DiscontinuityPackets + ",\"TimestampErrorPackets\":" + r.TimestampErrorPackets +
                ",\"SourceChannels\":" + r.SourceChannels + ",\"Ac3SourceDetected\":" + Bool(r.Ac3SourceDetected) +
                ",\"EightChannelUsbOutputNegotiated\":" + Bool(r.EightChannelUsbOutputNegotiated) +
                ",\"RequestedOutputChannels\":" + r.RequestedOutputChannels + ",\"NativeUsbOutputNegotiated\":" + Bool(r.NativeUsbOutputNegotiated) +
                ",\"SourceFormatValidated\":" + Bool(r.SourceFormatValidated) + ",\"Ready\":" + Bool(r.Ready) +
                ",\"DecoderLogClean\":" + Bool(r.DecoderLogClean) + ",\"CrcRejectedFrames\":" + r.CrcRejectedFrames + ",\"StopRequested\":" + Bool(r.StopRequested) +
                ",\"DecoderSelected\":" + Bool(r.DecoderSelected) + ",\"CrcCheckingEnabled\":" + Bool(r.CrcCheckingEnabled) +
                ",\"CleanupComplete\":" + Bool(r.CleanupComplete) + ",\"LinearGain\":" + r.LinearGain.ToString("R", CultureInfo.InvariantCulture) +
                ",\"ElapsedMilliseconds\":" + r.ElapsedMilliseconds.ToString("R", CultureInfo.InvariantCulture) + "}";
            string temp = r.StatusFile + ".tmp";
            File.WriteAllText(temp, json, Encoding.UTF8);
            if (File.Exists(r.StatusFile)) File.Replace(temp, r.StatusFile, null); else File.Move(temp, r.StatusFile);
        }

        private static string ReadLogTail(string path)
        {
            if (!File.Exists(path)) return null;
            // Status polling must not scale memory with a days-long log.
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) {
                long start = Math.Max(0, stream.Length - 262144);
                stream.Position = start;
                using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, true)) {
                    if (start > 0) reader.ReadLine();
                    return reader.ReadToEnd();
                }
            }
        }

        private static void ReleaseContinuous(object value, SpdifContinuousResult r)
        {
            try { if (value != null && Marshal.IsComObject(value)) Marshal.ReleaseComObject(value); }
            catch (Exception error) { r.CleanupErrors.Add(error.Message); }
        }
        private static void ReleaseContinuousPointer(IntPtr pointer, SpdifContinuousResult r)
        {
            try { if (pointer != IntPtr.Zero) Marshal.Release(pointer); }
            catch (Exception error) { r.CleanupErrors.Add(error.Message); }
        }

        public static SpdifContinuousResult RunContinuous(string captureEndpointId, string renderEndpointId, string mpvPath,
            string logFile, string stopFile, string statusFile, string artifactRoot, double gain, bool shared, string configPath, string ipcPath,
            int maximumSeconds, int startupTimeoutSeconds)
        {
            return RunContinuous(captureEndpointId, renderEndpointId, mpvPath, logFile, stopFile, statusFile, artifactRoot,
                gain, shared, configPath, ipcPath, maximumSeconds, startupTimeoutSeconds, false);
        }

        public static SpdifContinuousResult RunContinuous(string captureEndpointId, string renderEndpointId, string mpvPath,
            string logFile, string stopFile, string statusFile, string artifactRoot, double gain, bool shared, string configPath, string ipcPath,
            int maximumSeconds, int startupTimeoutSeconds, bool muted)
        {
            return RunContinuous(captureEndpointId, renderEndpointId, mpvPath, logFile, stopFile, statusFile, artifactRoot,
                gain, shared, configPath, ipcPath, maximumSeconds, startupTimeoutSeconds, muted, DefaultNativeDelaySamplesCsv);
        }

        public static SpdifContinuousResult RunContinuous(string captureEndpointId, string renderEndpointId, string mpvPath,
            string logFile, string stopFile, string statusFile, string artifactRoot, double gain, bool shared, string configPath, string ipcPath,
            int maximumSeconds, int startupTimeoutSeconds, bool muted, string delaySamplesCsv, int outputChannels = 8)
        {
            SpdifContinuousResult r = new SpdifContinuousResult { CaptureEndpointId = captureEndpointId, RenderEndpointId = renderEndpointId, LinearGain = gain, RequestedOutputChannels = outputChannels };
            Stopwatch total = Stopwatch.StartNew(), running = new Stopwatch();
            IDeviceEnumerator enumerator = null; IDevice input = null, output = null;
            IAudioClient audio = null, renderProbe = null; ISpdifCaptureClient capture = null;
            IntPtr audioPointer = IntPtr.Zero, renderPointer = IntPtr.Zero, capturePointer = IntPtr.Zero;
            IntPtr captureFormat = IntPtr.Zero, renderFormat = IntPtr.Zero;
            Process player = null; Task pendingWrite = null; bool started = false;
            try {
                if (Thread.CurrentThread.GetApartmentState() != ApartmentState.STA) throw new InvalidOperationException("Use an STA PowerShell process.");
                if (maximumSeconds < 0 || startupTimeoutSeconds < 5 || startupTimeoutSeconds > 120) throw new ArgumentOutOfRangeException("Invalid startup/duration bound.");
                VerifyEndpoint(captureEndpointId, true); VerifyEndpoint(renderEndpointId, false);
                string root = Path.GetFullPath(artifactRoot).TrimEnd(Path.DirectorySeparatorChar) + Path.DirectorySeparatorChar;
                if (!root.EndsWith("android-a34" + Path.DirectorySeparatorChar + "artifacts" + Path.DirectorySeparatorChar, StringComparison.OrdinalIgnoreCase))
                    throw new ArgumentException("Artifact root must be this repository's android-a34/artifacts directory.");
                r.MpvLogFile = PrivatePath(root, logFile, ".log"); r.StopFile = PrivatePath(root, stopFile, ".stop"); r.StatusFile = PrivatePath(root, statusFile, ".json");
                foreach (string path in new string[] { r.MpvLogFile, r.StopFile, r.StatusFile }) Directory.CreateDirectory(Path.GetDirectoryName(path));
                if (!File.Exists(mpvPath)) throw new FileNotFoundException("mpv is missing.", mpvPath);
                if (!string.IsNullOrEmpty(configPath) && !File.Exists(configPath)) throw new FileNotFoundException("DSP configuration is missing.", configPath);
                r.MpvArguments = BuildContinuousMpvArguments(renderEndpointId, logFile, gain, shared, configPath, ipcPath, muted, delaySamplesCsv, outputChannels);
                SaveContinuousStatus(r, total);
                enumerator = (IDeviceEnumerator)new DeviceEnumeratorClass();
                Require(enumerator.GetDevice(captureEndpointId, out input), "Get explicit optical input");
                Require(enumerator.GetDevice(renderEndpointId, out output), "Get explicit analog output");
                int state; Require(input.GetState(out state), "Input state"); if (state != 1) throw new IOException("Input is inactive.");
                Require(output.GetState(out state), "Output state"); if (state != 1) throw new IOException("Output is inactive.");
                r.CaptureFriendlyName = FriendlyName(input); r.RenderFriendlyName = FriendlyName(output);
                if (r.CaptureFriendlyName == null || r.CaptureFriendlyName.IndexOf("SPDIF", StringComparison.OrdinalIgnoreCase) < 0 ||
                    r.CaptureFriendlyName.IndexOf("USB Sound Device", StringComparison.OrdinalIgnoreCase) < 0 ||
                    r.CaptureFriendlyName.IndexOf("Microphone", StringComparison.OrdinalIgnoreCase) >= 0 || r.CaptureFriendlyName.IndexOf("Line", StringComparison.OrdinalIgnoreCase) >= 0)
                    throw new IOException("Input must be the SPDIF capture endpoint of USB Sound Device.");
                if (r.RenderFriendlyName == null || r.RenderFriendlyName.IndexOf("USB Sound Device", StringComparison.OrdinalIgnoreCase) < 0 || r.RenderFriendlyName.IndexOf("SPDIF", StringComparison.OrdinalIgnoreCase) >= 0)
                    throw new IOException("Output must be the analog render endpoint of USB Sound Device.");
                Guid iid = AudioId;
                Require(input.Activate(ref iid, 23, IntPtr.Zero, out audioPointer), "Activate input");
                audio = (IAudioClient)Marshal.GetTypedObjectForIUnknown(audioPointer, typeof(IAudioClient));
                captureFormat = Format(2, 3);
                if (audio.IsFormatSupported(1, captureFormat, IntPtr.Zero) != 0) throw new IOException("Exclusive carrier PCM16/2ch/48k is unsupported.");
                if (!shared) {
                    Require(output.Activate(ref iid, 23, IntPtr.Zero, out renderPointer), "Activate output preflight");
                    renderProbe = (IAudioClient)Marshal.GetTypedObjectForIUnknown(renderPointer, typeof(IAudioClient));
                    renderFormat = Format(outputChannels, outputChannels == 6 ? 0x60F : 0x63F);
                    if (renderProbe.IsFormatSupported(1, renderFormat, IntPtr.Zero) != 0) throw new IOException("Exclusive PCM16/8ch/48k/0x63F output is unsupported.");
                    ReleaseContinuous(renderProbe, r); renderProbe = null; ReleaseContinuousPointer(renderPointer, r); renderPointer = IntPtr.Zero;
                }
                Require(audio.Initialize(1, 0, 1000000, 100000, captureFormat, IntPtr.Zero), "Initialize exclusive optical carrier input");
                uint bufferFrames; Require(audio.GetBufferSize(out bufferFrames), "Input buffer size");
                if (bufferFrames == 0 || bufferFrames > 48000) throw new IOException("Unexpected input buffer size.");
                iid = CaptureId; Require(audio.GetService(ref iid, out capturePointer), "Capture service");
                capture = (ISpdifCaptureClient)Marshal.GetTypedObjectForIUnknown(capturePointer, typeof(ISpdifCaptureClient));
                byte[] buffer = new byte[checked((int)bufferFrames * 4)];
                ProcessStartInfo info = new ProcessStartInfo(mpvPath, r.MpvArguments) { UseShellExecute = false, CreateNoWindow = true, RedirectStandardInput = true };
                player = Process.Start(info); if (player == null) throw new IOException("mpv did not start."); r.OwnedMpvPid = player.Id;
                Require(audio.Start(), "Start optical input"); started = true; running.Start();
                r.Status = "waiting_for_ac3"; SaveContinuousStatus(r, total);
                long nextStatus = 0;
                while (maximumSeconds == 0 || running.Elapsed.TotalSeconds < maximumSeconds) {
                    if (File.Exists(r.StopFile)) { r.StopRequested = true; break; }
                    if (player.HasExited) throw new IOException("Owned decoder exited before stop; code " + player.ExitCode + ".");
                    if (running.ElapsedMilliseconds >= nextStatus) {
                        ObserveContinuousLog(r, ReadLogTail(r.MpvLogFile));
                        r.Status = r.Ready ? "running" : "waiting_for_ac3";
                        SaveContinuousStatus(r, total); nextStatus = running.ElapsedMilliseconds + 1000;
                        if (!r.Ready && running.Elapsed.TotalSeconds >= startupTimeoutSeconds)
                            throw new TimeoutException("No verified AC-3 six-channel / USB eight-channel route within the startup timeout. Check optical cable, TV audio system/Auto 1/DD+ disabled and active source.");
                    }
                    IntPtr data; uint frames, flags; ulong position, qpc;
                    Require(capture.GetBuffer(out data, out frames, out flags, out position, out qpc), "Capture GetBuffer");
                    if (frames == 0) { Thread.Sleep(1); continue; }
                    int count;
                    try {
                        if (frames > bufferFrames) throw new IOException("Capture packet exceeds initialized buffer.");
                        count = checked((int)frames * 4);
                        if ((flags & 2) != 0) { Array.Clear(buffer, 0, count); r.SilentPackets++; }
                        else { if (data == IntPtr.Zero) throw new IOException("Non-silent carrier has no data."); Marshal.Copy(data, buffer, 0, count); }
                        if ((flags & 1) != 0) r.DiscontinuityPackets++;
                        if ((flags & 4) != 0) r.TimestampErrorPackets++;
                        r.CapturePackets++;
                    } finally { Require(capture.ReleaseBuffer(frames), "Capture ReleaseBuffer"); }
                    r.CaptureFrames += frames;
                    Stopwatch writeTime = Stopwatch.StartNew();
                    pendingWrite = player.StandardInput.BaseStream.WriteAsync(buffer, 0, count);
                    while (!pendingWrite.Wait(25)) {
                        if (File.Exists(r.StopFile)) { r.StopRequested = true; break; }
                        if (writeTime.ElapsedMilliseconds >= 1500) throw new TimeoutException("Optical decoder stdin blocked for 1500 ms.");
                    }
                    if (r.StopRequested) break;
                    r.MaximumWriteMilliseconds = Math.Max(r.MaximumWriteMilliseconds, writeTime.Elapsed.TotalMilliseconds);
                    pendingWrite = null; r.CarrierBytesSent += count;
                }
                if (!r.Ready && !r.StopRequested) throw new IOException("Capture duration ended before AC-3 six-channel / USB eight-channel readiness.");
                r.Status = "stopping";
            } catch (Exception error) { r.Error = error.GetBaseException().Message; r.Status = "failed"; }
            finally {
                r.Ready = false;
                if (started) { try { Require(audio.Stop(), "Stop optical input"); } catch (Exception e) { r.CleanupErrors.Add(e.Message); } }
                if (player != null) {
                    try { player.StandardInput.Close(); } catch (Exception e) { r.CleanupErrors.Add("Close stdin: " + e.Message); }
                    try {
                        if (!player.WaitForExit(4000)) { player.Kill(); r.OwnedMpvKilledAfterExitDeadline = true; if (!player.WaitForExit(2000)) r.CleanupErrors.Add("Owned decoder did not exit after kill."); }
                        if (player.HasExited) r.MpvExitCode = player.ExitCode;
                    } catch (Exception e) { r.CleanupErrors.Add("Owned decoder cleanup: " + e.Message); }
                    if (pendingWrite != null) { try { if (!pendingWrite.Wait(250)) r.CleanupErrors.Add("Pending stdin write did not finish."); } catch (Exception e) { if (!r.StopRequested) r.CleanupErrors.Add("Pending write: " + e.GetBaseException().Message); } }
                    player.Dispose();
                }
                ReleaseContinuous(capture, r); ReleaseContinuousPointer(capturePointer, r); ReleaseContinuous(audio, r); ReleaseContinuousPointer(audioPointer, r);
                ReleaseContinuous(renderProbe, r); ReleaseContinuousPointer(renderPointer, r); ReleaseContinuous(input, r); ReleaseContinuous(output, r); ReleaseContinuous(enumerator, r);
                if (captureFormat != IntPtr.Zero) Marshal.FreeHGlobal(captureFormat); if (renderFormat != IntPtr.Zero) Marshal.FreeHGlobal(renderFormat);
                r.CleanupComplete = r.CleanupErrors.Count == 0;
                if (r.Error == null && !r.CleanupComplete) r.Error = "Cleanup failed: " + string.Join("; ", r.CleanupErrors.ToArray());
                if (r.Error == null && !r.StopRequested && r.MpvExitCode != 0) r.Error = "Decoder exit code " + r.MpvExitCode + ".";
                r.Status = r.Error == null ? "stopped" : "failed";
                if (r.StatusFile != null) { try { SaveContinuousStatus(r, total); } catch (Exception e) { r.CleanupErrors.Add("Status write: " + e.Message); r.CleanupComplete = false; r.Status = "failed"; } }
            }
            return r;
        }
    }
}
