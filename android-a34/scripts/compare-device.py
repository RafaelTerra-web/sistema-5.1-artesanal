"""Compare the installed app's full DSP with mpv using its actual decoded PCM input."""
import argparse
import importlib.util
import json
from pathlib import Path
import subprocess
import numpy as np

root = Path(__file__).resolve().parents[1]
parser = argparse.ArgumentParser()
parser.add_argument("--device-dir", type=Path, default=root / "artifacts/device")
parser.add_argument("--mpv", type=Path, default=root.parent / "configuracao-pc/mpv-portatil/mpv.com")
args = parser.parse_args()
spec = importlib.util.spec_from_file_location("mpv_compare", root / "scripts/compare-mpv.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
report = json.loads((args.device_dir / "report.json").read_text(encoding="utf-8-sig"))
profile = report["offlinePipeline"]["profile"]
vector = {"inputChannels": 6, "delaysSamples": profile["delaySamples"], "surroundCutoffHz": profile["surroundCutoffHz"],
          "surroundSend": profile["surroundBassSend"], "centerCopyEnabled": profile["centerBassCopyEnabled"],
          "centerCutoffHz": profile["centerBassCutoffHz"], "centerSend": profile["centerBassSend"],
          "effectiveLfeHeadroom": profile["effectiveLfeHeadroom"], "eqFrequencyHz": profile["lfeEqFrequenciesHz"],
          "eqGainDb": profile["lfeEqGainDb"], "eqQ": profile["lfeEqQ"][0], "masterGain": profile["masterGain"]}
reference = args.device_dir / "mpv-reference.f32le"
graph = module.graph_for(vector, exact_center_q=True)
command = [str(args.mpv), "--no-config", "--no-video", "--no-terminal", "--audio-channels=5.1", "--ao=pcm",
           "--ao-pcm-waveheader=no", "--audio-format=float", f"--ao-pcm-file={reference}",
           f"--af=lavfi=[{graph}]", str(args.device_dir / "decoded.wav")]
subprocess.run(command, check=True, capture_output=True, timeout=45)
# Writer always emits the fixed extensible float32 header, verified by the app tests.
raw = (args.device_dir / "processed.wav").read_bytes()
if raw[:4] != b"RIFF" or raw[60:64] != b"data":
    raise ValueError("Unexpected app WAV header")
actual = np.frombuffer(raw[68:], dtype="<f4").reshape(-1, 6)
expected = np.fromfile(reference, dtype="<f4").reshape(-1, 6)
if len(actual) != len(expected) or len(actual) != report["offlinePipeline"]["outputFrames"]:
    raise ValueError("DSP output lengths differ")
channels = []
for c in range(6):
    error = actual[:, c].astype(np.float64) - expected[:, c]
    power = np.mean(expected[:, c].astype(np.float64)**2)
    mse = np.mean(error**2)
    channels.append({"channel": c, "peakError": float(np.max(np.abs(error))), "rmsError": float(np.sqrt(mse)),
                     "snrDb": float(10*np.log10(power/mse)) if mse and power else None})
passed = bool(np.isfinite(actual).all() and np.isfinite(expected).all() and all(x["snrDb"] is None or x["snrDb"] >= 100 for x in channels))
result = {"pass": passed, "framesCompared": len(actual), "completeLength": True, "channels": channels,
          "scope": "Full DSP from the installed app UID on the A34 versus mpv for the SAME actual decoded PCM input; decoder fidelity excluded.", "command": command}
(args.device_dir / "comparison.json").write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
if not passed:
    raise SystemExit(1)
