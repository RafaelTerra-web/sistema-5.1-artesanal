"""Validate a private IEC61937 capture against the project's own synthetic AC-3.

Never opens an audio device. Extracts complete bursts, checks CRC as in FFmpeg
ac3dec.c (ANSI16 over the syncframe excluding its syncword), and decodes to a
private six-channel PCM file with mpv's file output. A successful decode does
not establish acoustic routing or unchanged compressed payloads.
"""
import argparse
import hashlib
import importlib.util
import json
from pathlib import Path
import re
import subprocess

import numpy as np
from scipy.signal import correlate

ROOT = Path(__file__).resolve().parents[2]
REFERENCE_SHA256 = "d8a9b917975b18c1357d3d9650fced77eaa8a262dd1412638204f9e5db6dfdd8"
spec = importlib.util.spec_from_file_location("iec61937", ROOT / "android-a34/scripts/analyze-iec61937.py")
iec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(iec)


def crc16(data):
    value = 0
    for byte in data:
        value ^= byte << 8
        for _ in range(8):
            value = ((value << 1) ^ (0x8005 if value & 0x8000 else 0)) & 0xffff
    return value


def extract(raw):
    report = iec.analyze(raw)
    bursts = report["bursts"]
    candidates = [b for b in bursts if b["status"] == "header_consistent_ac3_candidate"]
    if not candidates:
        raise ValueError("No complete header-consistent AC-3 bursts found")
    for before, after in zip(candidates, candidates[1:]):
        if after["byteOffset"] - before["byteOffset"] != 6144:
            raise ValueError("AC-3 burst gap: cannot treat capture as a continuous segment")
    payloads = []
    for burst in candidates:
        if burst["ac3Header"]["sampleRate"] != 48000 or burst["ac3Header"]["channels"] != 6:
            raise ValueError("This validation requires 48kHz six-channel AC-3")
        first = burst["byteOffset"] + 8
        payload = raw[first:first + burst["payloadBytes"]]
        if burst["wordEndian"] == "little":
            payload = iec.swap16(payload)
        if crc16(payload[2:]):
            raise ValueError("AC-3 frame CRC mismatch")
        payloads.append(payload)
    return report, payloads


def decode(mpv, source, pcm, log):
    command = [str(mpv), "--no-config", "--no-video", "--load-scripts=no",
               "--audio-spdif=", "--audio-channels=5.1", "--audio-format=float",
               "--ao=pcm", "--ao-pcm-waveheader=no", "--ao-pcm-file=" + str(pcm),
               "--ad-lavc-o=err_detect=crccheck+explode", "--log-file=" + str(log), str(source)]
    done = subprocess.run(command, capture_output=True, timeout=30,
                          creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    text = log.read_text(encoding="utf-8", errors="replace") if log.exists() else ""
    if done.returncode or "48000Hz 5.1 6ch float" not in text or re.search(
            r"frame CRC mismatch|frame sync error|error decoding|incomplete frame", text, re.I):
        raise RuntimeError("Strict six-channel decode failed; inspect private log")
    samples = np.fromfile(pcm, dtype="<f4")
    if len(samples) % 6 or not len(samples) or not np.isfinite(samples).all():
        raise RuntimeError("Invalid decoded six-channel PCM")
    return samples.reshape(-1, 6), command


def compare(source, received):
    # Transmission loops. Even a shorter captured window may cross the loop end.
    copies = max(2, int(np.ceil(len(received) / len(source))) + 1)
    source = np.tile(source, (copies, 1))
    ref = source.astype(np.float64)
    cap = received.astype(np.float64)
    score = sum(correlate(ref[:, i], cap[:, i], mode="valid", method="fft") for i in range(6))
    energy = np.concatenate(([0], np.cumsum(np.sum(ref * ref, axis=1))))
    window_energy = energy[len(cap):] - energy[:-len(cap)]
    denominator = np.sqrt(np.maximum(window_energy, 0) * np.sum(cap * cap))
    normalized = np.divide(score, denominator, out=np.zeros_like(score), where=denominator > 0)
    start = int(np.argmax(normalized))
    aligned = ref[start:start + len(cap)]
    channels = []
    for i, name in enumerate(("FL", "FR", "FC", "LFE", "SL", "SR")):
        a, b = aligned[:, i], cap[:, i]
        active = float(np.max(np.abs(a))) > 0.001
        aa, bb = float(a @ a), float(b @ b)
        channels.append({"channel": name, "activeInCapturedWindow": active,
                         "correlation": float(a @ b / np.sqrt(aa * bb)) if aa and bb else None,
                         "gain": float(a @ b / aa) if aa else None,
                         "peakError": float(np.max(np.abs(a - b))),
                         "receivedPeak": float(np.max(np.abs(b)))})
    return {"commonAlignmentFrames": start, "commonAlignmentSeconds": start / 48000,
            "normalizedCorrelationAllChannels": float(normalized[start]), "channels": channels}


def channel_activity(samples):
    # The approved fixture plays each channel alone. Count 20ms windows in which
    # its RMS exceeds .01 while all other channels remain below .001.
    windows = len(samples) // 960
    rms = np.sqrt(np.mean(samples[:windows * 960].reshape(windows, 960, 6).astype(np.float64) ** 2, axis=1))
    rows = []
    for i, name in enumerate(("FL", "FR", "FC", "LFE", "SL", "SR")):
        isolated = (rms[:, i] > .01) & (np.max(np.delete(rms, i, axis=1), axis=1) < .001)
        rows.append({"channel": name, "isolatedWindows20ms": int(np.sum(isolated))})
    return rows


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", type=Path)
    parser.add_argument("--reference", type=Path, required=True)
    parser.add_argument("--output", type=Path, required=True)
    parser.add_argument("--mpv", type=Path, default=ROOT / "configuracao-pc/mpv-portatil/mpv.com")
    args = parser.parse_args()
    artifact_root = (ROOT / "android-a34/artifacts").resolve()
    out = args.output.resolve()
    if not out.is_relative_to(artifact_root):
        parser.error("Output must remain in private android-a34/artifacts")
    reference = args.reference.read_bytes()
    if hashlib.sha256(reference).hexdigest() != REFERENCE_SHA256:
        parser.error("Only the project's approved own synthetic AC-3 reference is permitted")
    if out.exists():
        parser.error("Use a new output directory; previous evidence is preserved")
    raw = args.capture.read_bytes()
    report, frames = extract(raw)
    out.mkdir(parents=True)
    extracted = b"".join(frames)
    media = out / "captured.ac3"
    media.write_bytes(extracted)
    received, command = decode(args.mpv, media, out / "captured.f32", out / "decode.log")
    if len(received) != len(frames) * 1536:
        raise RuntimeError("Decoded length differs from the complete AC-3 frame count")
    source, _ = decode(args.mpv, args.reference.resolve(), out / "reference.f32", out / "reference-decode.log")
    exact_offset = (reference + reference).find(extracted)
    result = {"capture": str(args.capture.resolve()), "referenceSha256": REFERENCE_SHA256,
              "burstCount": len(frames), "crcValidatedAllFrames": True,
              "continuousBurstSpacingBytes": 6144, "decodedChannels": 6,
              "sampleRate": 48000, "decodedFrames": len(received),
              "completeBurstBytes": len(extracted), "captureSha256": hashlib.sha256(raw).hexdigest(),
              "capturedPayloadSha256": hashlib.sha256(extracted).hexdigest(),
              "exactCompressedSourceMatch": exact_offset >= 0,
              "sourceMatchByteOffset": exact_offset if exact_offset >= 0 else None,
              "strictDecoderCommand": command, "comparison": compare(source, received),
              "independentChannelEvidence": channel_activity(received),
              "analogOutputsValidated": False, "androidCaptureValidated": False,
              "limitations": ["Comparison covers the captured window, not long-term operation or acoustic latency.",
                              "Different compressed bytes do not identify which processing the TV performed.",
                              "Six decoded channels do not establish physical connector order."]}
    result["allSixChannelsIsolatedInDecodedPcm"] = all(
        row["isolatedWindows20ms"] >= 10 for row in result["independentChannelEvidence"])
    (out / "validation.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
    print(json.dumps({k: result[k] for k in ("burstCount", "crcValidatedAllFrames", "decodedChannels",
                                           "decodedFrames", "exactCompressedSourceMatch", "comparison")}))


if __name__ == "__main__":
    main()
