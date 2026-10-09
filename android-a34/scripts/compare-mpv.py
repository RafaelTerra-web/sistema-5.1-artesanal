"""Compare full DSP golden vectors with the repository's mpv PCM-file output.

No audio device is opened: --ao=pcm writes files only. The Android engine keeps
the input span; adelay in mpv also emits its 3686-frame tail. Both spans and the
unmatched tail are reported explicitly. Requires Python + NumPy + golden export.
"""
from __future__ import annotations

import argparse
import hashlib
import json
from pathlib import Path
import struct
import subprocess
import time

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
APP_ROOT = ROOT / "android-a34"


def write_float_wav(source: Path, destination: Path, channels: int) -> None:
    raw = source.read_bytes()
    if channels == 6:
        # WAVEFORMATEXTENSIBLE float subtype; index order FL FR FC LFE BL BR.
        # Last two channels are addressed by index in every filter.
        fmt = struct.pack("<HHIIHHHHI", 0xFFFE, channels, 48000,
                          48000 * channels * 4, channels * 4, 32, 22, 32, 0x3F)
        fmt += bytes.fromhex("0300000000001000800000aa00389b71")
    else:
        fmt = struct.pack("<HHIIHH", 3, channels, 48000,
                          48000 * channels * 4, channels * 4, 32)
    destination.write_bytes(b"RIFF" + struct.pack("<I", 4 + 8 + len(fmt) + 8 + len(raw))
                            + b"WAVEfmt " + struct.pack("<I", len(fmt)) + fmt
                            + b"data" + struct.pack("<I", len(raw)) + raw)


def bass_graph(vector: dict, exact_center_q: bool = False) -> str:
    surround = (
        "asplit=2[sbmain][sbsur];"
        "[sbmain]pan=5.1|c0=c0|c1=c1|c2=c2|c3=c3|c4=0*c4|c5=0*c5[sbkeep];"
        "[sbsur]pan=stereo|c0=c4|c1=c5,"
        f"acrossover=split={vector['surroundCutoffHz']}:order=4th:precision=double[sblo][sbhi];"
        "[sblo]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c0|"
        f"c3={vector['surroundSend']}*c0+{vector['surroundSend']}*c1|c4=0*c0|c5=0*c1[sblfe];"
        "[sbhi]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c0|c3=0*c0|c4=c0|c5=c1[sbtop];"
        "[sbkeep][sblfe][sbtop]amix=inputs=3:normalize=0:dropout_transition=0"
    )
    if vector["centerCopyEnabled"]:
        # Exact Windows graph syntax: lowpass uses FFmpeg's default Q (0.707),
        # whereas the Android LR4 uses sqrt(0.5); report this approximation.
        cutoff = vector["centerCutoffHz"]
        lowpass = (f"lowpass=f={cutoff}:t=q:w={np.sqrt(0.5)}:p=2:r=f64" if exact_center_q
                   else f"lowpass=f={cutoff}:p=2")
        surround += (
            ",asplit=2[cbmain][cbsub];[cbsub]pan=mono|c0=c2,"
            f"{lowpass},{lowpass},"
            "pan=5.1|c0=0*c0|c1=0*c0|c2=0*c0|"
            f"c3={vector['centerSend']}*c0|c4=0*c0|c5=0*c0[cblfe];"
            "[cbmain][cblfe]amix=inputs=2:normalize=0:dropout_transition=0"
        )
    return surround


def stereo_graph() -> str:
    # Explicit equivalent of the Android matrix, using exact LR4 Q and no
    # heuristic floor/fade/grace period from the old Windows fallback.
    q = np.sqrt(0.5)
    return (
        "asplit=2[stmain][stbass];"
        "[stmain]pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1|c3=0*c0|c4=0.5*c0|c5=0.5*c1[stkeep];"
        "[stbass]pan=mono|c0=0.25*c0+0.25*c1,"
        f"lowpass=f=120:t=q:w={q}:p=2:r=f64,lowpass=f=120:t=q:w={q}:p=2:r=f64,"
        "pan=5.1|c0=0*c0|c1=0*c0|c2=0*c0|c3=c0|c4=0*c0|c5=0*c0[stlfe];"
        "[stkeep][stlfe]amix=inputs=2:normalize=0:dropout_transition=0,"
    )


def graph_for(vector: dict, exact_center_q: bool = False) -> str:
    graph = stereo_graph() if vector["inputChannels"] == 2 else ""
    graph += bass_graph(vector, exact_center_q)
    graph += ",adelay=" + "|".join(f"{n}S" for n in vector["delaysSamples"])
    scale = vector["effectiveLfeHeadroom"]
    graph += f",pan=5.1|c0=c0|c1=c1|c2=c2|c3={scale}*c3|c4=c4|c5=c5"
    for frequency, gain in zip(vector["eqFrequencyHz"], vector["eqGainDb"], strict=True):
        graph += f",equalizer=f={frequency}:t=q:w={vector['eqQ']}:g={gain}:c=LFE:b=0:r=f64"
    graph += f",volume=volume={vector['masterGain']}:precision=double"
    return graph


def comparison(vector: dict, golden_dir: Path, validation_dir: Path, mpv: Path,
               expected_frames: int, exact_center_q: bool = False) -> dict:
    display_name = vector["name"] + ("-exact-lr4-control" if exact_center_q else "")
    wav = validation_dir / f"{display_name}-input.wav"
    write_float_wav(golden_dir / vector["input"], wav, vector["inputChannels"])
    reference = validation_dir / f"{display_name}-mpv.f32le"
    graph = graph_for(vector, exact_center_q)
    command = [str(mpv), "--no-config", "--no-video", "--no-terminal", "--idle=no",
               "--audio-channels=5.1", "--ao=pcm", "--ao-pcm-waveheader=no",
               "--audio-format=float", f"--ao-pcm-file={reference}",
               f"--log-file={validation_dir / (display_name + '-mpv.log')}",
               f"--af=lavfi=[{graph}]", str(wav)]
    start = time.perf_counter()
    process = subprocess.run(command, input=b"", capture_output=True, timeout=45)
    if process.returncode or not reference.exists():
        raise RuntimeError(json.dumps({"command": command, "returncode": process.returncode,
                                       "stdout": process.stdout.decode(errors="replace"),
                                       "stderr": process.stderr.decode(errors="replace")}, indent=2))
    expected = np.fromfile(golden_dir / vector["output"], dtype="<f4").reshape(-1, 6)
    actual = np.fromfile(reference, dtype="<f4").reshape(-1, 6)
    shared = min(len(expected), len(actual))
    error = actual[:shared].astype(np.float64) - expected[:shared].astype(np.float64)
    per_channel = []
    for channel, label in enumerate(["FL", "FR", "FC", "LFE", "SL", "SR"]):
        diff = error[:, channel]
        signal = expected[:shared, channel].astype(np.float64)
        error_power = float(np.mean(diff * diff))
        signal_power = float(np.mean(signal * signal))
        snr = 10 * np.log10(signal_power / error_power) if error_power and signal_power else None
        per_channel.append({"channel": label, "peak_error": float(np.max(np.abs(diff))),
                            "rms_error": float(np.sqrt(error_power)),
                            "snr_db": float(snr) if snr is not None else None,
                            "unequal_samples": int(np.count_nonzero(diff)),
                            "golden_peak": float(np.max(np.abs(signal))),
                            "mpv_peak": float(np.max(np.abs(actual[:shared, channel]))),
                            "golden_samples_at_limit": int(np.count_nonzero(np.abs(signal) >= 1)),
                            "mpv_samples_at_limit": int(np.count_nonzero(np.abs(actual[:shared, channel]) >= 1))})
    tail = actual[shared:]
    has_center = vector["centerCopyEnabled"]
    # Native and equivalent stereo need >=100dB numerical agreement; the center
    # PC default-Q approximation is separately accepted only above75dB at LFE.
    snr_ok = all(x["snr_db"] is None or x["snr_db"] >= (75 if has_center and not exact_center_q and x["channel"] == "LFE" else 100)
                 for x in per_channel)
    expected_tail = max(vector["delaysSamples"])
    lengths_ok = len(expected) == expected_frames and len(actual) == expected_frames + expected_tail
    finite_ok = bool(np.isfinite(expected).all() and np.isfinite(actual).all())
    clipping_ok = vector["clippedSamples"] == 0 and all(x["golden_samples_at_limit"] == 0 and x["mpv_samples_at_limit"] == 0 for x in per_channel)
    return {"name": display_name, "pass": snr_ok and lengths_ok and finite_ok and clipping_ok,
            "input_frames": expected_frames, "engine_output_frames": len(expected),
            "mpv_output_frames": len(actual), "shared_frames_compared": shared,
            "mpv_extra_tail_frames": len(tail), "expected_extra_tail_frames": expected_tail,
            "mpv_extra_tail_peak": float(np.max(np.abs(tail))) if len(tail) else 0,
            "tail_policy": "Engine processes the supplied span only; mpv adelay appends3686frames. Every shared frame is compared; the unmatched tail is explicitly reported.",
            "finite": finite_ok, "no_clipping": clipping_ok, "lengths_expected": lengths_ok,
            "channels": per_channel, "command": command, "graph": graph,
            "mpv_output_sha256": hashlib.sha256(reference.read_bytes()).hexdigest(),
            "wall_seconds": round(time.perf_counter() - start, 4),
            "center_q_note": ("Reference explicitly sets the Android LR4 Qsqrt(0.5), isolating the PC default-Q approximation"
                              if exact_center_q else "PC lowpass default Q0.707 vs Android exact LR4 Qsqrt(0.5)") if has_center else None}


def main() -> None:
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--golden-dir", type=Path, default=APP_ROOT / "app/build/dsp-golden-vectors")
    parser.add_argument("--output-dir", type=Path, default=APP_ROOT / "app/build/validation/mpv-full-graph")
    parser.add_argument("--mpv", type=Path, default=ROOT / "configuracao-pc/mpv-portatil/mpv.com")
    args = parser.parse_args()
    args.output_dir.mkdir(parents=True, exist_ok=True)
    manifest = json.loads((args.golden_dir / "manifest.json").read_text(encoding="utf-8"))
    results = [comparison(vector, args.golden_dir, args.output_dir, args.mpv,
                          manifest["inputFrames"]) for vector in manifest["vectors"]]
    center_vector = next(vector for vector in manifest["vectors"] if vector["centerCopyEnabled"])
    center_control = comparison(center_vector, args.golden_dir, args.output_dir, args.mpv,
                                manifest["inputFrames"], exact_center_q=True)
    report = {"pass": all(result["pass"] for result in results) and center_control["pass"],
              "scope": "Offline numerical processing only; no audio device, USB route, decoder, acoustic measurement or physical latency.",
              "results": results, "center_exact_lr4_control": center_control}
    report_path = args.output_dir / "comparison.json"
    report_path.write_text(json.dumps(report, indent=2), encoding="utf-8")
    print(json.dumps({"pass": report["pass"], "report": str(report_path),
                      "vectors": [{"name": x["name"], "pass": x["pass"],
                                   "engine_frames": x["engine_output_frames"], "mpv_frames": x["mpv_output_frames"],
                                   "tail_frames": x["mpv_extra_tail_frames"],
                                   "peak_error": max(c["peak_error"] for c in x["channels"]),
                                   "minimum_snr_db": min((c["snr_db"] for c in x["channels"] if c["snr_db"] is not None), default=None)}
                                  for x in [*results, center_control]]}, indent=2))
    raise SystemExit(0 if report["pass"] else 1)


if __name__ == "__main__":
    main()
