// Controller-owned six-channel PCM loopback -> AC-3 IEC61937 HDMI encoder.
// Keeps DSP in the analog decoder route. It does not implement original compressed
// bitstream passthrough: decoded browser PCM is re-encoded into AC-3.
using System;
using System.Diagnostics;
using System.Globalization;
using System.IO;
using System.Text;
using System.Text.RegularExpressions;
using System.Threading;

namespace Sistema51.Cm6206
{
    public sealed class Ac3EncoderStatus
    {
        public string Status = "starting", Error, StartedAtUtc = DateTime.UtcNow.ToString("o"), UpdatedAtUtc;
        public string CaptureEndpointId, RenderEndpointId, SourceMode = "Auto";
        public long SentFrames, CapturedFrames;
        public int? OwnedMpvPid;
        public bool Ready, SixChannelPcmSourceValidated, HdmiAc3CarrierOutputNegotiated;
        public bool OriginalCompressedBitstreamPreserved = false, UpmixApplied = false, EndpointVolumeChanged = false, HidOperationsPerformed = false;
        public bool StopRequested, CleanupComplete;
    }

    public static class WindowsAc3Encoder
    {
        private static void VerifyRenderId(string id)
        {
            Guid parsed; const string prefix = "{0.0.0.00000000}.";
            if (id == null || !id.StartsWith(prefix, StringComparison.OrdinalIgnoreCase) || !Guid.TryParse(id.Substring(prefix.Length), out parsed))
                throw new ArgumentException("Use an explicit render endpoint ID.");
        }

        public static string BuildConfig(string renderEndpointId, int bitrate)
        {
            return BuildConfig(renderEndpointId, bitrate, "Auto");
        }

        public static string NormalizeSourceMode(string inputMode)
        {
            foreach (string value in new string[] { "Auto", "Stereo", "Native" })
                if (string.Equals(value, inputMode, StringComparison.OrdinalIgnoreCase)) return value;
            throw new ArgumentException("InputMode must be Auto, Stereo or Native.");
        }

        public static string BuildConfig(string renderEndpointId, int bitrate, string inputMode)
        {
            VerifyRenderId(renderEndpointId);
            inputMode = NormalizeSourceMode(inputMode);
            if (bitrate != 384 && bitrate != 448 && bitrate != 640) throw new ArgumentOutOfRangeException("bitrate", "Use AC-3 bitrate 384, 448 or 640 kbps.");
            // The virtual mix has six slots even when the declared source is
            // stereo. Only the user's independently confirmed Stereo override
            // reconstructs channels from FL/FR. Auto/Native never inspect energy.
            string upmix = inputMode == "Stereo" ?
                "lavfi=[pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1|c3=0.25*c0+0.25*c1|c4=0.5*c0|c5=0.5*c1]," : "";
            return "audio-device=wasapi/" + renderEndpointId.Substring("{0.0.0.00000000}.".Length) + "\n" +
                "ao=wasapi\naudio-exclusive=yes\naudio-fallback-to-null=no\naudio-channels=5.1\naudio-spdif=\nad-lavc-downmix=no\n" +
                "af=" + upmix + "lavcac3enc=tospdif=yes:bitrate=" + bitrate.ToString(CultureInfo.InvariantCulture) + ":minch=6\n" +
                "audio-buffer=0.040\nvolume=100\nvolume-max=100\nmute=no\nspeed=1\ncache=no\n" +
                "demuxer=lavf\ndemuxer-lavf-format=wav\ndemuxer-lavf-probe-info=no\ndemuxer-lavf-o=ignore_length=1,max_size=11520\n" +
                "demuxer-lavf-buffersize=4096\nstream-buffer-size=8192\ndemuxer-readahead-secs=0\ndemuxer-max-bytes=64KiB\n" +
                "terminal=no\nosc=no\ninput-default-bindings=no\nmedia-controls=no\ninput-media-keys=no\nload-scripts=no\n" +
                "msg-level=all=info,cplayer=v,ao/wasapi=debug\n";
        }

        public static void ObserveLogs(Ac3EncoderStatus status, string relayLog, string playerLog)
        {
            if (!string.IsNullOrEmpty(relayLog)) {
                MatchCollection sent = Regex.Matches(relayLog, @"\bsentFrames=(\d+)");
                if (sent.Count > 0) status.SentFrames = long.Parse(sent[sent.Count - 1].Groups[1].Value, CultureInfo.InvariantCulture);
                MatchCollection captured = Regex.Matches(relayLog, @"\bcapturedFrames=(\d+)");
                if (captured.Count > 0) status.CapturedFrames = long.Parse(captured[captured.Count - 1].Groups[1].Value, CultureInfo.InvariantCulture);
                Match pid = Regex.Match(relayLog, @"\bmpvPid=(\d+)");
                if (pid.Success) status.OwnedMpvPid = int.Parse(pid.Groups[1].Value, CultureInfo.InvariantCulture);
                if (Regex.IsMatch(relayLog, @"(?m)^.*\bERROR\s")) throw new IOException("PCM loopback encoder relay reported an error.");
            }
            if (!string.IsNullOrEmpty(playerLog)) {
                string guid = status.RenderEndpointId.Substring("{0.0.0.00000000}.".Length);
                bool selectedHdmi = Regex.IsMatch(playerLog, @"(?m)^.*\[ao/wasapi\].*Selecting device '" + Regex.Escape(guid) + @"'.*SONY");
                MatchCollection outputs = Regex.Matches(playerLog, @"(?m)^.*\[cplayer\].*AO: \[wasapi\] ([^\r\n]+)");
                foreach (Match output in outputs) {
                    if (output.Groups[1].Value.Trim() != "48000Hz stereo 2ch spdif-ac3") throw new IOException("HDMI output is not an AC-3 IEC61937 carrier: " + output.Groups[1].Value.Trim());
                    if (selectedHdmi || status.HdmiAc3CarrierOutputNegotiated) status.HdmiAc3CarrierOutputNegotiated = true;
                }
                if (Regex.IsMatch(playerLog, @"(?m)^\[[^\]\r\n]+\]\[(?:e|f)\]")) throw new IOException("HDMI AC-3 encoder or output reported an error.");
            }
            status.Ready = status.SixChannelPcmSourceValidated && status.HdmiAc3CarrierOutputNegotiated && status.SentFrames > 0;
        }

        private static string ReadTail(string path)
        {
            if (!File.Exists(path)) return null;
            using (FileStream stream = new FileStream(path, FileMode.Open, FileAccess.Read, FileShare.ReadWrite)) {
                long start = Math.Max(0, stream.Length - 262144); stream.Position = start;
                using (StreamReader reader = new StreamReader(stream, Encoding.UTF8, true)) { if (start > 0) reader.ReadLine(); return reader.ReadToEnd(); }
            }
        }

        private static string JsonString(string s)
        {
            if (s == null) return "null";
            StringBuilder b = new StringBuilder("\"");
            foreach (char c in s) { if (c == '"' || c == '\\') b.Append('\\').Append(c); else if (c < 32) b.Append("\\u").Append(((int)c).ToString("x4")); else b.Append(c); }
            return b.Append('"').ToString();
        }

        private static string Bool(bool b) { return b ? "true" : "false"; }
        private static void Save(Ac3EncoderStatus s, string path)
        {
            s.UpdatedAtUtc = DateTime.UtcNow.ToString("o");
            string json = "{\"Status\":" + JsonString(s.Status) + ",\"Error\":" + JsonString(s.Error) +
                ",\"StartedAtUtc\":" + JsonString(s.StartedAtUtc) + ",\"UpdatedAtUtc\":" + JsonString(s.UpdatedAtUtc) +
                ",\"CaptureEndpointId\":" + JsonString(s.CaptureEndpointId) + ",\"RenderEndpointId\":" + JsonString(s.RenderEndpointId) +
                ",\"SourceMode\":" + JsonString(s.SourceMode) +
                ",\"SentFrames\":" + s.SentFrames + ",\"CapturedFrames\":" + s.CapturedFrames +
                ",\"OwnedMpvPid\":" + (s.OwnedMpvPid.HasValue ? s.OwnedMpvPid.Value.ToString(CultureInfo.InvariantCulture) : "null") +
                ",\"Ready\":" + Bool(s.Ready) + ",\"SixChannelPcmSourceValidated\":" + Bool(s.SixChannelPcmSourceValidated) +
                ",\"HdmiAc3CarrierOutputNegotiated\":" + Bool(s.HdmiAc3CarrierOutputNegotiated) +
                ",\"OriginalCompressedBitstreamPreserved\":false,\"UpmixApplied\":" + Bool(s.UpmixApplied) + ",\"EndpointVolumeChanged\":false,\"HidOperationsPerformed\":false" +
                ",\"StopRequested\":" + Bool(s.StopRequested) + ",\"CleanupComplete\":" + Bool(s.CleanupComplete) + "}";
            string temp = path + ".tmp"; File.WriteAllText(temp, json, Encoding.UTF8);
            if (File.Exists(path)) File.Replace(temp, path, null); else File.Move(temp, path);
        }

        public static Ac3EncoderStatus Run(string captureEndpointId, string renderEndpointId, string mpvPath,
            string configPath, string logPath, string stopPath, string statusPath, int bitrate, int startupTimeoutSeconds)
        {
            return Run(captureEndpointId, renderEndpointId, mpvPath, configPath, logPath, stopPath, statusPath, bitrate, startupTimeoutSeconds, "Auto");
        }

        public static Ac3EncoderStatus Run(string captureEndpointId, string renderEndpointId, string mpvPath,
            string configPath, string logPath, string stopPath, string statusPath, int bitrate, int startupTimeoutSeconds, string inputMode)
        {
            Ac3EncoderStatus status = new Ac3EncoderStatus { CaptureEndpointId = captureEndpointId, RenderEndpointId = renderEndpointId };
            ManualResetEvent shutdown = new ManualResetEvent(false); Thread watcher = null;
            try {
                VerifyRenderId(captureEndpointId); VerifyRenderId(renderEndpointId);
                status.SourceMode = NormalizeSourceMode(inputMode);
                status.UpmixApplied = status.SourceMode == "Stereo";
                if (captureEndpointId.Equals(renderEndpointId, StringComparison.OrdinalIgnoreCase)) throw new ArgumentException("Loopback input and HDMI output must differ.");
                if (startupTimeoutSeconds < 5 || startupTimeoutSeconds > 120) throw new ArgumentOutOfRangeException("startupTimeoutSeconds");
                RelayLoopbackLowLatency.VerifySourceFormat(captureEndpointId);
                status.SixChannelPcmSourceValidated = true;
                File.WriteAllText(configPath, BuildConfig(renderEndpointId, bitrate, status.SourceMode), Encoding.ASCII);
                // Prevent the legacy meter-activity heuristic from manufacturing
                // channels. Any actual mono/stereo upmix belongs upstream.
                File.WriteAllText(Path.Combine(Path.GetDirectoryName(logPath), "audio-sistema.nativo"), "native");
                Save(status, statusPath);
                watcher = new Thread(() => {
                    Stopwatch timer = Stopwatch.StartNew();
                    while (!shutdown.WaitOne(500)) {
                        try {
                            ObserveLogs(status, ReadTail(logPath), ReadTail(logPath + ".mpv.log"));
                            status.Status = status.Ready ? "running" : "waiting_for_hdmi_ac3";
                            if (!status.Ready && timer.Elapsed.TotalSeconds >= startupTimeoutSeconds)
                                throw new TimeoutException("No verified HDMI AC-3 carrier output within startup timeout.");
                            Save(status, statusPath);
                        } catch (Exception error) {
                            status.Error = error.GetBaseException().Message; status.Status = "failed";
                            try { Save(status, statusPath); } catch { }
                            try { File.WriteAllText(stopPath, "encoder_watchdog"); } catch { }
                            return;
                        }
                    }
                });
                watcher.IsBackground = true; watcher.Name = "AC-3 HDMI status monitor"; watcher.Start();
                RelayLoopbackLowLatency.Run(captureEndpointId, mpvPath, configPath, logPath, stopPath);
                status.StopRequested = File.Exists(stopPath) && status.Error == null;
                if (!status.StopRequested && status.Error == null) status.Error = "HDMI encoder exited without a controller stop request.";
            } catch (Exception error) { status.Error = error.GetBaseException().Message; }
            finally {
                shutdown.Set(); if (watcher != null && watcher.IsAlive && !watcher.Join(2000)) status.Error = "Encoder status monitor did not terminate.";
                shutdown.Dispose(); status.Ready = false;
                // The relay's Run method closes its owned child and joins workers
                // before returning. No unowned mpv/audio process is ever killed.
                status.CleanupComplete = watcher == null || !watcher.IsAlive;
                if (status.OwnedMpvPid.HasValue) {
                    try {
                        using (Process owned = Process.GetProcessById(status.OwnedMpvPid.Value)) {
                            if (!owned.HasExited) { status.CleanupComplete = false; status.Error = "Owned encoder mpv remains alive after relay cleanup."; }
                        }
                    } catch (ArgumentException) { /* Owned process has exited. */ }
                    catch (Exception error) { status.CleanupComplete = false; status.Error = "Cannot confirm owned encoder cleanup: " + error.Message; }
                }
                status.Status = status.Error == null ? "stopped" : "failed";
                try { Save(status, statusPath); } catch (Exception error) { status.Error = error.Message; status.Status = "failed"; status.CleanupComplete = false; }
            }
            return status;
        }
    }
}
