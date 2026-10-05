"""Offline verification. Outputs only to this audit directory; never opens audio hardware."""
from pathlib import Path
from array import array
import json
import math
import struct
import subprocess

AUDIT = Path(__file__).resolve().parent
ROOT = AUDIT.parent
MPV = ROOT / "mpv-portatil" / "mpv.exe"
RATE = 48000
CHANNELS = 6
SWR = "asetrate=48002,aresample=48000:filter_size=64:phase_shift=10:cutoff=0.97"
DELAY = "adelay=70|70|0|0|70|70"


def write_float_wave(path, samples):
    payload = samples.tobytes()
    fmt = struct.pack("<HHIIHHHHI", 0xFFFE, CHANNELS, RATE, RATE * 24, 24, 32, 22, 32, 0x3F)
    fmt += bytes.fromhex("0300000000001000800000aa00389b71")
    body = b"WAVEfmt " + struct.pack("<I", len(fmt)) + fmt
    body += b"data" + struct.pack("<I", len(payload)) + payload
    path.write_bytes(b"RIFF" + struct.pack("<I", len(body)) + body)


def read_wave(path):
    blob = path.read_bytes()
    pos = 12
    fmt = None
    data = None
    while pos + 8 <= len(blob):
        tag, length = struct.unpack_from("<4sI", blob, pos)
        chunk = blob[pos + 8:pos + 8 + length]
        if tag == b"fmt ":
            fmt = chunk
        elif tag == b"data":
            data = chunk
        pos += 8 + length + (length & 1)
    assert fmt is not None and data is not None
    codec, channels, rate, _, align, bits = struct.unpack_from("<HHIIHH", fmt)
    if codec == 0xFFFE:
        codec = struct.unpack_from("<H", fmt, 24)[0]
    if codec == 3 and bits == 32:
        samples = array("f")
        samples.frombytes(data)
    elif codec == 1 and bits == 16:
        raw = array("h")
        raw.frombytes(data)
        samples = array("f", (value / 32768 for value in raw))
    else:
        raise ValueError((codec, bits))
    assert align == channels * bits // 8
    return samples, channels, rate


def run_mpv(name, source, filters, pcm=False, extra=()):
    log = AUDIT / (name + ".log")
    output = AUDIT / (name + ".wav")
    args = [str(MPV), "--no-config", "--player-operation-mode=cplayer", "--idle=no",
            "--force-window=no", "--keep-open=no", "--no-video", "--terminal=no",
            "--osc=no", "--load-scripts=no", "--ytdl=no", "--media-controls=no",
            "--input-media-keys=no", "--audio-channels=5.1", "--audio-spdif=",
            "--ad-lavc-downmix=no", "--log-file=" + str(log), "--af=" + filters]
    if pcm:
        args.extend(["--ao=pcm", "--audio-format=float", "--ao-pcm-file=" + str(output)])
    else:
        args.extend(["--ao=null", "--ao-null-untimed"])
    args.extend(extra)
    args.append(str(source))
    process = subprocess.run(args, stdin=subprocess.DEVNULL, stdout=subprocess.DEVNULL,
                             stderr=subprocess.DEVNULL, timeout=30,
                             creationflags=subprocess.CREATE_NO_WINDOW)
    log_text = log.read_text(encoding="utf-8", errors="replace")
    errors = [line for line in log_text.splitlines() if "][e][" in line or "][f][" in line]
    if process.returncode or errors:
        raise RuntimeError((process.returncode, errors))
    assert "Exiting... (End of file)" in log_text, log_text[-2000:]
    return output, log


def fitted_amplitude(samples, channels, rate, channel, frequency):
    frames = len(samples) // channels
    start, end = int(rate * 0.1), frames - int(rate * 0.1)
    ss = cc = sc = ys = yc = energy = 0.0
    omega = 2 * math.pi * frequency / rate
    for frame in range(start, end):
        y = samples[frame * channels + channel]
        s, c = math.sin(frame * omega), math.cos(frame * omega)
        ss += s * s
        cc += c * c
        sc += s * c
        ys += y * s
        yc += y * c
        energy += y * y
    determinant = ss * cc - sc * sc
    sin_gain = (ys * cc - yc * sc) / determinant
    cos_gain = (yc * ss - ys * sc) / determinant
    explained = sin_gain * ys + cos_gain * yc
    residual = max(0.0, energy - explained)
    return math.hypot(sin_gain, cos_gain), math.sqrt(residual / (end - start))


tones_path = AUDIT / "tons-1k-10k-20k-float32.wav"
frequencies = (1000, 10000, 20000, 1000, 10000, 20000)
tones = array("f")
for frame in range(RATE * 2):
    for frequency in frequencies:
        tones.append(0.5 * math.sin(2 * math.pi * frequency * frame / RATE))
write_float_wave(tones_path, tones)

baseline_path, _ = run_mpv("tons-baseline", tones_path, "lavfi=[anull]", pcm=True)
corrected_path, _ = run_mpv("tons-swr64", tones_path, "lavfi=[" + SWR + "]", pcm=True)
baseline, base_channels, base_rate = read_wave(baseline_path)
corrected, corrected_channels, corrected_rate = read_wave(corrected_path)
assert base_channels == corrected_channels == 6 and base_rate == corrected_rate == 48000
gains = []
for channel, frequency in enumerate(frequencies):
    base_amp, base_residual = fitted_amplitude(baseline, 6, RATE, channel, frequency)
    new_amp, new_residual = fitted_amplitude(corrected, 6, RATE, channel, frequency * 48002 / 48000)
    gains.append({"channel": channel, "input_frequency_hz": frequency,
                  "output_frequency_hz": frequency * 48002 / 48000,
                  "gain_db": 20 * math.log10(new_amp / base_amp),
                  "baseline_amplitude": base_amp, "corrected_amplitude": new_amp,
                  "baseline_residual_rms": base_residual, "corrected_residual_rms": new_residual})

impulses_path = AUDIT / "impulsos-6ch-float32.wav"
impulses = array("f", [0.0]) * (RATE * CHANNELS)
for channel in range(CHANNELS):
    impulses[4800 * CHANNELS + channel] = 0.5
write_float_wave(impulses_path, impulses)
impulse_output, _ = run_mpv("impulsos-swr64-delay70", impulses_path,
                          "lavfi=[" + SWR + "," + DELAY + "]", pcm=True)
impulse_samples, impulse_channels, impulse_rate = read_wave(impulse_output)
peaks = [max(range(len(impulse_samples) // impulse_channels),
             key=lambda frame: abs(impulse_samples[frame * impulse_channels + channel]))
         for channel in range(impulse_channels)]
relative_peaks = [frame - peaks[2] for frame in peaks]
assert relative_peaks == [3360, 3360, 0, 0, 3360, 3360], relative_peaks

_, full_log = run_mpv("fullchain-ac3-640-buffer32-confirmado", ROOT / "teste-longo-5.1.wav",
                     "lavfi=[" + SWR + "," + DELAY + "],lavcac3enc=tospdif=yes:bitrate=640:minch=6",
                     extra=("--audio-buffer=0.032", "--audio-fallback-to-null=no"))
assert "48000Hz stereo 2ch spdif-ac3" in full_log.read_text(encoding="utf-8", errors="replace")
results = {"source_frames": len(tones) // 6, "baseline_frames": len(baseline) // 6,
           "corrected_frames": len(corrected) // 6, "calibrated_input_rate_hz": 48002,
           "gain_measurements": gains, "impulse_peak_frames": peaks,
           "relative_delay_frames": relative_peaks,
           "relative_delay_ms": [value / RATE * 1000 for value in relative_peaks],
           "fullchain_log": str(full_log), "hardware_output_opened": False}
(AUDIT / "resultados.json").write_text(json.dumps(results, indent=2) + "\n", encoding="utf-8")
print(json.dumps(results, indent=2))
