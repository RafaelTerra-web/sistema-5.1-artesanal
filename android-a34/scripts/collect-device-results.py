"""Collect only files created by the debug instrumentation in this app's UID."""
import argparse
from pathlib import Path
import subprocess

parser = argparse.ArgumentParser()
parser.add_argument("--serial", required=True)
parser.add_argument("--adb", default="adb")
parser.add_argument("--output", type=Path, default=Path(__file__).resolve().parents[1] / "artifacts/device")
args = parser.parse_args()
args.output.mkdir(parents=True, exist_ok=True)
for filename in ("report.json", "decoded.wav", "processed.wav"):
    result = subprocess.run([args.adb, "-s", args.serial, "exec-out", "run-as", "br.com.sistema51.a34", "cat", "files/instrumentation/" + filename], capture_output=True, check=True)
    (args.output / filename).write_bytes(result.stdout)
    print(f"{filename}: {len(result.stdout)} bytes")
