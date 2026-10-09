"""Prepare deterministic optical test signals offline; never open an audio device.

Requires NumPy. The optional AC-3 fallback uses the repository's mpv encoder
with --o/--oac, writing a file instead of using a playback audio output.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import shutil
import struct
import subprocess

import numpy as np


ROOT = Path(__file__).resolve().parents[2]
RATE = 48000
PCM_PEAK_DBFS = -36.0
SOURCE = ROOT / "a34-testes" / "2026-10-07" / "vectors"
DEFAULT_OUT = ROOT / "android-a34" / "artifacts" / "optical-2026-10-09" / "vectors"
CHANNELS_51 = ["FL", "FR", "FC", "LFE", "SL", "SR"]


def digest(data: bytes) -> str:
    return hashlib.sha256(data).hexdigest()


def write_pcm16(path: Path, samples: np.ndarray, mask: int) -> dict:
    pcm = np.clip(np.rint(samples * 32768), -32768, 32767).astype("<i2")
    count = pcm.shape[1]
    payload = pcm.tobytes()
    guid = bytes.fromhex("0100000000001000800000aa00389b71")
    fmt = struct.pack("<HHIIHHHHI", 0xFFFE, count, RATE, RATE * count * 2,
                      count * 2, 16, 22, 16, mask) + guid
    body = b"WAVEfmt " + struct.pack("<I", len(fmt)) + fmt
    body += b"data" + struct.pack("<I", len(payload)) + payload
    encoded = b"RIFF" + struct.pack("<I", len(body)) + body
    path.write_bytes(encoded)
    peak = float(np.abs(pcm.astype(np.float64)).max()) / 32768
    return {
        "file": path.name, "container": "WAVE_FORMAT_EXTENSIBLE",
        "encoding": "signed PCM16 little-endian", "sample_rate_hz": RATE,
        "channels": count, "channel_mask": mask, "channel_mask_hex": hex(mask),
        "bits_per_sample": 16, "frames": len(pcm), "seconds": len(pcm) / RATE,
        "peak": peak, "peak_dbfs": float(20 * np.log10(peak)) if peak else None,
        "bytes": len(encoded), "sha256": digest(encoded),
        "pcm_payload_sha256": digest(payload),
    }


def tone(frequency: float, seconds: float, amplitude: float) -> np.ndarray:
    count = round(RATE * seconds)
    result = amplitude * np.sin(2 * np.pi * frequency * np.arange(count) / RATE)
    ramp = np.linspace(0.0, 1.0, round(RATE * 0.01))
    result[:len(ramp)] *= ramp
    result[-len(ramp):] *= ramp[::-1]
    return result


def stereo_vector(out: Path) -> dict:
    samples = np.zeros((RATE * 5, 2), dtype=np.float64)
    windows = []
    amplitude = 10 ** (PCM_PEAK_DBFS / 20)
    for channel, frequency, start, end in [
        (0, 400, 0.5, 1.5), (1, 700, 1.75, 2.75),
        (0, 400, 3.0, 4.0), (1, 700, 3.0, 4.0),
    ]:
        first, last = round(start * RATE), round(end * RATE)
        samples[first:last, channel] = tone(frequency, end - start, amplitude)
        windows.append({
            "channel": ["L", "R"][channel], "channel_index": channel,
            "frequency_hz": frequency, "start_seconds": start, "end_seconds": end,
            "start_frame": first, "end_frame_exclusive": last,
        })
    result = write_pcm16(out / "pcm-stereo-48k-minus36dbfs-5s.wav", samples, 3)
    result.update({"channels_order": ["L", "R"], "target_peak_dbfs": PCM_PEAK_DBFS,
                   "ramps_ms": 10, "tone_windows": windows,
                   "silence_intervals_seconds": [[0, 0.5], [1.5, 1.75],
                                                 [2.75, 3.0], [4.0, 5.0]]})
    return result


class BitReader:
    def __init__(self, data: bytes, start: int = 0):
        self.data, self.offset = data, start

    def read(self, count: int) -> int:
        value = 0
        for _ in range(count):
            value = (value << 1) | ((self.data[self.offset // 8] >>
                                    (7 - self.offset % 8)) & 1)
            self.offset += 1
        return value


def inspect_known_ac3(data: bytes) -> dict:
    """Validate every fixed-format 48 kHz/640 kbit/s AC-3 syncframe header.

    This is deliberately not a general decoder. It rejects other sample rates,
    bitrate codes, channel modes, and truncated frames rather than guessing.
    """
    frame_bytes = 2560
    if not data or len(data) % frame_bytes:
        raise ValueError("AC-3 length is not a sequence of 2560-byte frames")
    for offset in range(0, len(data), frame_bytes):
        frame = data[offset:offset + frame_bytes]
        if frame[:2] != b"\x0b\x77":
            raise ValueError(f"AC-3 syncword missing at byte {offset}")
        bits = BitReader(frame, 32)
        fscod, frmsizecod = bits.read(2), bits.read(6)
        bsid, _bsmod, acmod = bits.read(5), bits.read(3), bits.read(3)
        if (acmod & 1) and acmod != 1:
            bits.read(2)  # cmixlev
        if acmod & 4:
            bits.read(2)  # surmixlev
        if acmod == 2:
            bits.read(2)  # dsurmod
        lfeon = bits.read(1)
        if (fscod, frmsizecod // 2, acmod, lfeon) != (0, 18, 7, 1) or bsid > 10:
            raise ValueError(f"Unexpected AC-3 format at byte {offset}: "
                             f"{(fscod, frmsizecod, bsid, acmod, lfeon)}")
    count = len(data) // frame_bytes
    return {"sample_rate_hz": RATE, "bitrate_bps": 640000,
            "declared_channels": 6, "acmod": 7, "lfeon": True,
            "frame_bytes": frame_bytes, "samples_per_frame": 1536,
            "frame_count": count, "decoded_frames": count * 1536,
            "encoded_seconds": count * 1536 / RATE,
            "syncframe_headers_validated": count,
            "frame_sha256": [digest(data[i:i + frame_bytes])
                             for i in range(0, len(data), frame_bytes)]}


def ac3_vector(out: Path) -> dict:
    original = SOURCE / "tones-sequential-51.ac3"
    target = out / "tones-sequential-51.ac3"
    source_manifest = SOURCE / "manifest.json"
    metadata = json.loads(source_manifest.read_text(encoding="utf-8")) if source_manifest.exists() else {}
    windows = metadata.get("tones", {}).get("windows", [])
    provenance: dict = {"repository_source": str(original.relative_to(ROOT))}
    if original.exists():
        shutil.copyfile(original, target)
        provenance["action"] = "byte-for-byte copy of previous validated synthetic vector"
    else:
        # Reproduce the prior synthetic source, not a copyrighted media excerpt.
        samples = np.zeros((RATE * 9, 6), dtype=np.float64)
        windows = []
        for channel in range(6):
            first = round(RATE * (0.5 + channel * 1.25))
            freq = 60 if channel == 3 else 500
            samples[first:first + RATE, channel] = tone(freq, 1.0, 0.1)
            windows.append({"channel": CHANNELS_51[channel], "index": channel,
                            "frequency_hz": freq, "start_frame": first,
                            "end_frame": first + RATE})
        source_wav = out / "tones-sequential-51-source.wav"
        write_pcm16(source_wav, samples, 0x60F)
        mpv = ROOT / "configuracao-pc" / "mpv-portatil" / "mpv.com"
        if not mpv.is_file():
            raise FileNotFoundError(f"Need the existing AC-3 vector or offline encoder: {mpv}")
        command = [str(mpv), "--no-config", "--no-video", "--no-terminal",
                   "--audio-channels=5.1", "--oac=ac3", "--oacopts=b=640000",
                   "--of=ac3", f"--o={target}", str(source_wav)]
        process = subprocess.run(command, capture_output=True, text=True, timeout=60)
        if process.returncode:
            raise RuntimeError(f"Offline AC-3 encoding failed: {process.stderr}")
        provenance = {"action": "offline encoding of synthetic source", "command": command}
    payload = target.read_bytes()
    parsed = inspect_known_ac3(payload)
    return {"file": target.name, "encoding": "AC-3 elementary stream",
            "bytes": len(payload), "sha256": digest(payload),
            "original_signal_seconds": 9.0, "original_signal_peak": 0.1,
            "original_signal_peak_dbfs": -20.0, "ramps_ms": 10,
            "channels_order_source_wav": CHANNELS_51,
            "source_channel_mask": 0x60F, "tone_windows_source_wav": windows,
            "encoder_delay_samples": "not measured; align captured signal before comparisons",
            "provenance": provenance, **parsed}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--out", type=Path, default=DEFAULT_OUT)
    args = parser.parse_args()
    out = args.out.resolve()
    out.mkdir(parents=True, exist_ok=True)
    manifest = {
        "purpose": "Offline known signals for HDMI -> TV optical -> CM6206 -> A34 tests",
        "generated_by": str(Path(__file__).resolve().relative_to(ROOT)),
        "physical_speaker_mapping": "not assumed; logical source channels only",
        "playback_performed": False,
        "pcm_stereo": stereo_vector(out), "ac3_51": ac3_vector(out),
        "test_constraints": [
            "Keep amplifiers disconnected during initial optical capture validation.",
            "Select the verified HDMI audio endpoint explicitly before any playback.",
            "AC-3 needs encoded passthrough; decoded PCM over optical is a different test.",
            "A TV may resample PCM or alter encoded transport; align timing before interpreting results.",
        ],
    }
    path = out / "manifest.json"
    path.write_text(json.dumps(manifest, indent=2, ensure_ascii=False) + "\n", encoding="utf-8")
    print(json.dumps({"manifest": str(path), "pcm_sha256": manifest["pcm_stereo"]["sha256"],
                      "ac3_sha256": manifest["ac3_51"]["sha256"],
                      "ac3_frames": manifest["ac3_51"]["frame_count"],
                      "playback_performed": False}, indent=2))


if __name__ == "__main__":
    main()
