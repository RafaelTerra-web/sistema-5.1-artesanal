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
parser.add_argument("--pipeline", choices=("offlinePipeline", "bassManagementPipeline"), default="offlinePipeline")
args = parser.parse_args()
spec = importlib.util.spec_from_file_location("mpv_compare", root / "scripts/compare-mpv.py")
module = importlib.util.module_from_spec(spec)
spec.loader.exec_module(module)
report = json.loads((args.device_dir / "report.json").read_text(encoding="utf-8-sig"))
pipeline = report[args.pipeline]
profile = pipeline["profile"]
vector = {"inputChannels": 6, "delaysSamples": profile["delaySamples"], "surroundCutoffHz": profile["surroundCutoffHz"],
          "surroundSend": profile["surroundBassSend"], "centerCopyEnabled": profile["centerBassCopyEnabled"],
          "centerCutoffHz": profile["centerBassCutoffHz"], "centerSend": profile["centerBassSend"],
          "effectiveLfeHeadroom": profile["effectiveLfeHeadroom"], "eqFrequencyHz": profile["lfeEqFrequenciesHz"],
          "eqGainDb": profile["lfeEqGainDb"], "eqQ": profile["lfeEqQ"][0], "masterGain": profile["masterGain"]}
reference = args.device_dir / (args.pipeline + "-mpv-reference.f32le")
graph = module.graph_for(vector, exact_center_q=True)
if args.pipeline == "bassManagementPipeline":
    # Independent FFmpeg reference for this instrumentation profile. Reject
    # other settings instead of silently comparing against the wrong graph.
    if not (profile["frontCrossoverEnabled"] and profile["surroundCrossoverEnabled"]
            and profile["frontCutoffHz"] == profile["surroundCutoffHz"]
            and profile["frontBassSend"] == profile["surroundBassSend"] == 1
            and not profile["lfeEqEnabled"] and not any(profile["delaySamples"])):
        raise ValueError("Unsupported bass-management reference profile")
    graph = (
        "asplit=2[main][sats];"
        "[main]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c2|c3=c3|c4=0*c4|c5=0*c5[lfe];"
        "[sats]pan=5c|c0=c0|c1=c1|c2=c2|c3=c4|c4=c5,"
        f"acrossover=split={profile['frontCutoffHz']}:order=4th:precision=double[low][high];"
        "[low]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c2|c3=c0+c1+c2+c3+c4|c4=0*c3|c5=0*c4[bass];"
        "[high]pan=5.1|c0=c0|c1=c1|c2=c2|c3=0*c0|c4=c3|c5=c4[top];"
        "[lfe][bass][top]amix=inputs=3:normalize=0:dropout_transition=0,"
        f"pan=5.1|c0=c0|c1=c1|c2=c2|c3={profile['effectiveLfeHeadroom']}*c3|c4=c4|c5=c5"
    )
    if profile["lfeSubsonicEnabled"]:
        graph += f",highpass=f={profile['lfeSubsonicHz']}:p=2:t=q:w={np.sqrt(0.5)}:c=LFE:r=f64"
    graph += ",pan=5.1|" + "|".join(f"c{c}={gain}*c{c}" for c, gain in enumerate(profile["channelTrim"]))
    graph += f",volume={profile['masterGain']}:precision=double"
command = [str(args.mpv), "--no-config", "--no-video", "--no-terminal", "--audio-channels=5.1", "--ao=pcm",
           "--ao-pcm-waveheader=no", "--audio-format=float", f"--ao-pcm-file={reference}",
           f"--af=lavfi=[{graph}]", str(args.device_dir / "decoded.wav")]
subprocess.run(command, check=True, capture_output=True, timeout=45)
# Writer always emits the fixed extensible float32 header, verified by the app tests.
raw = (args.device_dir / ("bass-processed.wav" if args.pipeline == "bassManagementPipeline" else "processed.wav")).read_bytes()
if raw[:4] != b"RIFF" or raw[60:64] != b"data":
    raise ValueError("Unexpected app WAV header")
actual = np.frombuffer(raw[68:], dtype="<f4").reshape(-1, 6)
expected = np.fromfile(reference, dtype="<f4").reshape(-1, 6)
if len(actual) != len(expected) or len(actual) != pipeline["outputFrames"]:
    raise ValueError("DSP output lengths differ")
channels = []
for c in range(6):
    error = actual[:, c].astype(np.float64) - expected[:, c]
    power = np.mean(expected[:, c].astype(np.float64)**2)
    mse = np.mean(error**2)
    channels.append({"channel": c, "peakError": float(np.max(np.abs(error))), "rmsError": float(np.sqrt(mse)),
                     "snrDb": float(10*np.log10(power/mse)) if mse and power else None})
passed = bool(np.isfinite(actual).all() and np.isfinite(expected).all() and all(x["snrDb"] is None or x["snrDb"] >= 100 for x in channels))
result = {"pass": passed, "pipeline": args.pipeline, "framesCompared": len(actual), "completeLength": True, "channels": channels,
          "scope": "Full DSP from the installed app UID on the A34 versus mpv for the SAME actual decoded PCM input; decoder fidelity excluded.", "command": command}
(args.device_dir / (args.pipeline + "-comparison.json")).write_text(json.dumps(result, indent=2), encoding="utf-8")
print(json.dumps(result, indent=2))
if not passed:
    raise SystemExit(1)
