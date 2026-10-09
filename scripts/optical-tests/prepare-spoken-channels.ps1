# Offline synthesis and file encoding only. No playback/capture client is opened.
[CmdletBinding()]
param(
    [Parameter(Mandatory = $true)]
    [string]$MpvPath,
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '../../android-a34/artifacts/spoken-2026-10-09'),
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$Name = ('spoken-channels-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')),
    [string]$VoiceName = 'Microsoft Maria Desktop',
    [ValidateRange(-3, 3)]
    [int]$SpeechRate = 0
)
$ErrorActionPreference = 'Stop'
$destination = [IO.Path]::GetFullPath($OutputDirectory)
if ($destination -notmatch '(?i)[\\/]android-a34[\\/]artifacts[\\/]') {
    throw 'Generated audio must stay in the private android-a34/artifacts directory.'
}
$encoderPath = [IO.Path]::GetFullPath($MpvPath)
if (-not [IO.File]::Exists($encoderPath)) { throw 'Existing mpv file encoder is missing.' }
$wavPath = Join-Path $destination ($Name + '.wav')
$ac3Path = Join-Path $destination ($Name + '.ac3')
$manifestPath = Join-Path $destination ($Name + '-manifest.json')
$speechFolder = Join-Path $destination ($Name + '-speech')
$encoderLog = Join-Path $destination ($Name + '-encoder.log')
$encoderOut = Join-Path $destination ($Name + '-encoder-console.txt')
$encoderErr = Join-Path $destination ($Name + '-encoder-errors.txt')
foreach ($target in @($wavPath, $ac3Path, $manifestPath, $speechFolder, $encoderLog, $encoderOut, $encoderErr)) {
    if (Test-Path -LiteralPath $target) { throw ('Preserve existing evidence; choose another Name: ' + $target) }
}
Add-Type -AssemblyName System.Speech
if (-not ('Sistema51.SpokenFixtures.SpokenChannelWave' -as [type])) {
    Add-Type -TypeDefinition @'
using System;
using System.Collections.Generic;
using System.IO;
using System.Text;

namespace Sistema51.SpokenFixtures
{
    public sealed class SpeechInfo
    {
        public string File;
        public int Frames;
        public int OriginalPeakInteger;
        public double Seconds;
        public double NormalizationGain;
    }
    public sealed class BlockInfo
    {
        public string LogicalChannel;
        public string Text;
        public int Index;
        public int StartFrame;
        public int SpeechFrames;
        public int SpeechSlotFrames;
        public int ToneStartFrame;
        public int ToneEndFrameExclusive;
        public int EndFrameExclusive;
        public int ToneFrequencyHz;
        public int[] SpeechOutputChannelIndices;
        public double SpeechPeakPerOutputChannel;
    }
    public sealed class WaveResult
    {
        public int SampleRate = 48000;
        public int Channels = 6;
        public int BitsPerSample = 16;
        public string ChannelMask = "0x60F";
        public string[] ChannelOrder = { "FL", "FR", "FC", "LFE", "SL", "SR" };
        public int InitialSilenceFrames = 144000;
        public int FinalSilenceFrames = 48000;
        public int Frames;
        public double Seconds;
        public double VoiceTargetPeak = 0.25;
        public double ToneTargetPeak = 0.20;
        public int RampFrames = 480;
        public int[] PeakIntegerPerChannel = new int[6];
        public long[] NonzeroSamplesPerChannel = new long[6];
        public List<SpeechInfo> Speech = new List<SpeechInfo>();
        public List<BlockInfo> Timeline = new List<BlockInfo>();
    }
    public static class SpokenChannelWave
    {
        private const int Rate = 48000;
        private static short[] ReadSpeech(string path)
        {
            byte[] bytes = File.ReadAllBytes(path);
            if (bytes.Length < 44 || Encoding.ASCII.GetString(bytes, 0, 4) != "RIFF" ||
                Encoding.ASCII.GetString(bytes, 8, 4) != "WAVE") throw new InvalidDataException("Speech must be RIFF/WAVE.");
            byte[] format = null, data = null;
            int offset = 12;
            while (offset + 8 <= bytes.Length)
            {
                int length = checked((int)BitConverter.ToUInt32(bytes, offset + 4));
                int first = offset + 8;
                if (length < 0 || length > bytes.Length - first) throw new InvalidDataException("Truncated speech chunk.");
                string name = Encoding.ASCII.GetString(bytes, offset, 4);
                if (name == "fmt " || name == "data")
                {
                    byte[] chunk = new byte[length];
                    Buffer.BlockCopy(bytes, first, chunk, 0, length);
                    if (name == "fmt ") format = chunk; else data = chunk;
                }
                offset = checked(first + length + (length & 1));
            }
            if (format == null || format.Length < 16 || data == null || (data.Length & 1) != 0 ||
                BitConverter.ToUInt16(format, 0) != 1 || BitConverter.ToUInt16(format, 2) != 1 ||
                BitConverter.ToInt32(format, 4) != Rate || BitConverter.ToUInt16(format, 12) != 2 ||
                BitConverter.ToUInt16(format, 14) != 16)
                throw new InvalidDataException("Speech must be mono PCM16 at 48000 Hz.");
            short[] result = new short[data.Length / 2];
            Buffer.BlockCopy(data, 0, result, 0, data.Length);
            return result;
        }

        private static void WriteWave(string path, short[] samples)
        {
            int bytes = checked(samples.Length * 2);
            using (FileStream stream = new FileStream(path, FileMode.CreateNew, FileAccess.Write, FileShare.None))
            using (BinaryWriter writer = new BinaryWriter(stream))
            {
                writer.Write(Encoding.ASCII.GetBytes("RIFF")); writer.Write(bytes + 60);
                writer.Write(Encoding.ASCII.GetBytes("WAVEfmt ")); writer.Write(40);
                writer.Write((ushort)0xFFFE); writer.Write((ushort)6); writer.Write(Rate);
                writer.Write(Rate * 12); writer.Write((ushort)12); writer.Write((ushort)16);
                writer.Write((ushort)22); writer.Write((ushort)16); writer.Write(0x60F);
                writer.Write(new Guid("00000001-0000-0010-8000-00AA00389B71").ToByteArray());
                writer.Write(Encoding.ASCII.GetBytes("data")); writer.Write(bytes);
                byte[] pcm = new byte[bytes]; Buffer.BlockCopy(samples, 0, pcm, 0, bytes); writer.Write(pcm);
            }
        }

        public static WaveResult Build(string[] speechPaths, string[] phrases, string wavePath)
        {
            if (speechPaths.Length != 6 || phrases.Length != 6) throw new ArgumentException("Six speech files are required.");
            WaveResult result = new WaveResult();
            List<short[]> speech = new List<short[]>();
            int cursor = result.InitialSilenceFrames;
            for (int channel = 0; channel < 6; channel++)
            {
                short[] voice = ReadSpeech(speechPaths[channel]);
                int peak = 0;
                foreach (short sample in voice) peak = Math.Max(peak, Math.Abs((int)sample));
                if (peak == 0 || voice.Length > Rate * 4) throw new InvalidDataException("Silent or unexpectedly long speech.");
                speech.Add(voice);
                result.Speech.Add(new SpeechInfo { File = Path.GetFileName(speechPaths[channel]), Frames = voice.Length,
                    Seconds = voice.Length / (double)Rate, OriginalPeakInteger = peak, NormalizationGain = 8192.0 / peak });
                int slot = Math.Max(108000, voice.Length); // 2.25 seconds; never truncate a word.
                BlockInfo block = new BlockInfo { LogicalChannel = result.ChannelOrder[channel], Text = phrases[channel],
                    Index = channel, StartFrame = cursor, SpeechFrames = voice.Length, SpeechSlotFrames = slot,
                    ToneStartFrame = cursor + slot, ToneEndFrameExclusive = cursor + slot + 24000,
                    EndFrameExclusive = cursor + slot + 48000, ToneFrequencyHz = channel == 3 ? 60 : 500,
                    SpeechOutputChannelIndices = channel == 3 ? new int[] { 0, 1 } : new int[] { channel },
                    SpeechPeakPerOutputChannel = channel == 3 ? 0.25 / Math.Sqrt(2.0) : 0.25 };
                result.Timeline.Add(block); cursor = block.EndFrameExclusive;
            }
            result.Frames = cursor + result.FinalSilenceFrames;
            result.Seconds = result.Frames / (double)Rate;
            if (result.Seconds > 29) throw new InvalidDataException("Spoken fixture exceeds its bounded 29-second duration.");
            short[] output = new short[checked(result.Frames * 6)];
            for (int channel = 0; channel < 6; channel++)
            {
                BlockInfo block = result.Timeline[channel];
                short[] voice = speech[channel];
                double gain = result.Speech[channel].NormalizationGain * (channel == 3 ? 1.0 / Math.Sqrt(2.0) : 1.0);
                for (int frame = 0; frame < voice.Length; frame++)
                {
                    short sample = checked((short)Math.Round(voice[frame] * gain));
                    foreach (int target in block.SpeechOutputChannelIndices) output[(block.StartFrame + frame) * 6 + target] = sample;
                }
                int count = block.ToneEndFrameExclusive - block.ToneStartFrame;
                for (int frame = 0; frame < count; frame++)
                {
                    double ramp = Math.Min(1.0, Math.Min(frame / 480.0, (count - 1 - frame) / 480.0));
                    double tone = 0.20 * ramp * Math.Sin(2.0 * Math.PI * block.ToneFrequencyHz * frame / Rate);
                    output[(block.ToneStartFrame + frame) * 6 + channel] = checked((short)Math.Round(tone * 32768.0));
                }
            }
            for (int frame = 0; frame < result.Frames; frame++)
                for (int channel = 0; channel < 6; channel++)
                {
                    int sample = output[frame * 6 + channel];
                    if (sample != 0) result.NonzeroSamplesPerChannel[channel]++;
                    result.PeakIntegerPerChannel[channel] = Math.Max(result.PeakIntegerPerChannel[channel], Math.Abs(sample));
                }
            WriteWave(wavePath, output);
            return result;
        }
    }
}
'@
}
$synth = New-Object System.Speech.Synthesis.SpeechSynthesizer
$speechFiles = @()
$phrases = @('Frontal esquerda.', 'Frontal direita.', 'Canal central.', 'Subwoofer.', 'Surround esquerda.', 'Surround direita.')
$voice = $null
try {
    $installed = @($synth.GetInstalledVoices() | Where-Object { $_.Enabled -and $_.VoiceInfo.Culture.Name -eq 'pt-BR' })
    $chosen = @($installed | Where-Object { $_.VoiceInfo.Name -eq $VoiceName })
    if ($chosen.Count -ne 1) { throw ('Installed pt-BR voice required: ' + $VoiceName) }
    $voice = $chosen[0].VoiceInfo
    $synth.SelectVoice($voice.Name)
    $synth.Rate = $SpeechRate
    $synth.Volume = 100
    [IO.Directory]::CreateDirectory($destination) | Out-Null
    [IO.Directory]::CreateDirectory($speechFolder) | Out-Null
    $speechFormat = New-Object System.Speech.AudioFormat.SpeechAudioFormatInfo (48000,
        [System.Speech.AudioFormat.AudioBitsPerSample]::Sixteen, [System.Speech.AudioFormat.AudioChannel]::Mono)
    for ($index = 0; $index -lt $phrases.Count; $index++) {
        $speechPath = Join-Path $speechFolder ('{0:00}-speech.wav' -f $index)
        $synth.SetOutputToWaveFile($speechPath, $speechFormat)
        $synth.Speak($phrases[$index])
        $synth.SetOutputToNull()
        $speechFiles += $speechPath
    }
} finally { $synth.Dispose() }
$wave = [Sistema51.SpokenFixtures.SpokenChannelWave]::Build([string[]]$speechFiles, [string[]]$phrases, $wavPath)
$arguments = @('--no-config', '--no-video', '--no-terminal', '--audio-channels=5.1',
    '--oac=ac3', '--oacopts=b=640000', '--of=ac3', ('--o="{0}"' -f $ac3Path),
    ('--log-file="{0}"' -f $encoderLog), ('"{0}"' -f $wavPath))
$encoder = Start-Process -FilePath $encoderPath -ArgumentList $arguments -WindowStyle Hidden -PassThru `
    -RedirectStandardOutput $encoderOut -RedirectStandardError $encoderErr
$encoder.Handle | Out-Null
try {
    if (-not $encoder.WaitForExit(60000)) { throw 'Offline file encoder exceeded its bounded deadline.' }
    $encoder.Refresh()
    if ($encoder.ExitCode -ne 0 -or -not [IO.File]::Exists($ac3Path)) { throw 'Offline AC-3 file encoding failed; inspect private logs.' }
} finally {
    $encoder.Refresh()
    if (-not $encoder.HasExited) { $encoder.Kill(); $encoder.WaitForExit(5000) | Out-Null }
    $encoder.Dispose()
}
$manifest = [ordered]@{
    schemaVersion = 1
    generatedAtUtc = [DateTime]::UtcNow.ToString('o')
    purpose = 'Own synthesized channel names and sequential tones for a six-channel listening test'
    playbackPerformed = $false
    capturePerformed = $false
    voice = [ordered]@{ name = $voice.Name; culture = $voice.Culture.Name; gender = $voice.Gender.ToString();
        age = $voice.Age.ToString(); description = $voice.Description; rate = $SpeechRate;
        engine = 'Windows System.Speech / installed local SAPI voice'; imitationOfRealPerson = $false }
    source = [ordered]@{ file = [IO.Path]::GetFileName($wavPath); sha256 = (Get-FileHash -LiteralPath $wavPath -Algorithm SHA256).Hash.ToLowerInvariant();
        bytes = (Get-Item -LiteralPath $wavPath).Length; format = $wave }
    encoded = [ordered]@{ file = [IO.Path]::GetFileName($ac3Path); sha256 = (Get-FileHash -LiteralPath $ac3Path -Algorithm SHA256).Hash.ToLowerInvariant();
        bytes = (Get-Item -LiteralPath $ac3Path).Length; codec = 'AC-3'; bitrate = 640000; sampleRate = 48000;
        logicalChannels = 6; encoder = 'existing mpv offline file encoder'; arguments = $arguments; exitCode = 0 }
    synthesisFiles = @($speechFiles | ForEach-Object { [ordered]@{ file = [IO.Path]::GetFileName($_);
        sha256 = (Get-FileHash -LiteralPath $_ -Algorithm SHA256).Hash.ToLowerInvariant() } })
    scriptSha256 = (Get-FileHash -LiteralPath $PSCommandPath -Algorithm SHA256).Hash.ToLowerInvariant()
    limitations = @('A generated file does not validate physical speaker wiring or playback.',
        'The subwoofer name is spoken only through FL and FR; its following 60 Hz tone occupies LFE alone.',
        'All other names occupy only their declared channel. No upmix, reverb, limiter or cross-channel gain is applied.',
        'Voice target peak is -12.04 dBFS; each FL/FR subwoofer announcement peaks at -15.05 dBFS.',
        'AC-3 is encoded from the PCM fixture; decoded sample peaks may differ slightly.')
}
$manifest | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $manifestPath -Encoding UTF8
[pscustomobject]@{ WavFile = $wavPath; Ac3File = $ac3Path; ManifestFile = $manifestPath;
    DurationSeconds = $wave.Seconds; Voice = $voice.Name; VoicePeak = $wave.VoiceTargetPeak;
    TonePeak = $wave.ToneTargetPeak; Ac3Sha256 = $manifest.encoded.sha256; PlaybackPerformed = $false }
