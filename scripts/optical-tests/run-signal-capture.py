"""Private, bounded HDMI signal/capture test; never changes system defaults.

The Android debug capture checks the physical USB device and actual input route.
This coordinator does not validate the optical source or audio payload by itself.
All captures belong in the ignored android-a34/artifacts directory.
"""

import argparse
import json
from pathlib import Path
import subprocess
import time


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--adb", required=True, type=Path)
    parser.add_argument("--serial", required=True)
    parser.add_argument("--mpv", required=True, type=Path)
    parser.add_argument("--endpoint", required=True,
                        help="Explicit WASAPI endpoint, never 'auto'.")
    parser.add_argument("--source", type=Path)
    parser.add_argument("--mode", choices=("pcm", "ac3", "baseline"), required=True)
    parser.add_argument("--output", required=True, type=Path)
    args = parser.parse_args()
    root = Path(__file__).resolve().parents[2]
    private_root = (root / "android-a34" / "artifacts").resolve()
    output = args.output.resolve()
    if not output.is_relative_to(private_root):
        parser.error("Output must be in ignored android-a34/artifacts.")
    if not args.endpoint.startswith("wasapi/{") or not args.endpoint.endswith("}"):
        parser.error("An explicit WASAPI endpoint GUID is required.")
    if args.mode != "baseline" and (args.source is None or not args.source.is_file()):
        parser.error("A local known signal file is required.")
    if output.exists():
        parser.error("Use a new output directory to preserve previous evidence.")
    output.mkdir(parents=True)
    adb = [str(args.adb), "-s", args.serial]
    instrument = "br.com.sistema51.a34/br.com.sistema51.a34.AppTestInstrumentation"
    hidden = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    before = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e", "hardware",
                                   "snapshot", instrument], capture_output=True,
                            timeout=20, creationflags=hidden)
    (output / "snapshot-console.txt").write_bytes(before.stdout + before.stderr)
    report_line = next((line.split("report=", 1)[1] for line in
                        before.stdout.decode("utf-8", errors="replace").splitlines()
                        if line.startswith("INSTRUMENTATION_RESULT: report=")), None)
    if before.returncode != 0 or report_line is None:
        raise RuntimeError("Android snapshot failed; playback was not started.")
    snapshot = json.loads(report_line)
    (output / "snapshot.json").write_text(json.dumps(snapshot, ensure_ascii=False, indent=2), encoding="utf-8")
    devices = snapshot.get("usb", {}).get("usbDevices", [])
    if not snapshot.get("ok") or len(devices) != 1 or devices[0].get("usbId") != "0d8c:0102" or not devices[0].get("permission"):
        raise RuntimeError("A unique, authorized CM6206 is required; playback was not started.")

    command = None
    process = None
    result = {"mode": args.mode, "endpoint": args.endpoint,
              "startedAtUnixMs": int(time.time() * 1000),
              "defaultEndpointChanged": False, "amplifierUsed": False,
              "opticalValidated": False, "bitPerfectValidated": False}
    try:
        with (output / "mpv-console.txt").open("wb") as console:
            if args.mode != "baseline":
                command = [str(args.mpv), "--no-config", "--no-video", "--ao=wasapi",
                           "--audio-device=" + args.endpoint, "--audio-exclusive=yes",
                           "--loop-file=2", "--log-file=" + str(output / "mpv.log"),
                           "--msg-level=all=info,ao/wasapi=debug"]
                if args.mode == "pcm":
                    command += ["--audio-channels=stereo", "--audio-samplerate=48000",
                                "--audio-format=s16", "--volume=100"]
                else:
                    command += ["--audio-spdif=ac3", "--audio-channels=5.1"]
                command.append(str(args.source.resolve()))
                process = subprocess.Popen(command, stdout=console, stderr=subprocess.STDOUT,
                                           creationflags=hidden)
                time.sleep(0.5)
                if process.poll() is not None:
                    raise RuntimeError("HDMI transmitter exited before capture; inspect mpv log.")
            captured = subprocess.run(adb + ["shell", "am", "instrument", "-w", "-e",
                                            "hardware", "capture", instrument],
                                      capture_output=True, timeout=20, creationflags=hidden)
            (output / "capture-console.txt").write_bytes(captured.stdout + captured.stderr)
            report_line = next((line.split("report=", 1)[1] for line in
                                captured.stdout.decode("utf-8", errors="replace").splitlines()
                                if line.startswith("INSTRUMENTATION_RESULT: report=")), None)
            if captured.returncode != 0 or report_line is None:
                raise RuntimeError("Android capture did not return a report.")
            report = json.loads(report_line)
            (output / "capture-report.json").write_text(json.dumps(report, ensure_ascii=False, indent=2), encoding="utf-8")
            result["captureOk"] = bool(report.get("ok"))
            if not report.get("ok"):
                raise RuntimeError("USB capture failed: " + str(report.get("error", "unknown")))
            pulled = subprocess.run(adb + ["exec-out", "run-as", "br.com.sistema51.a34",
                                          "cat", "files/hardware/capture.pcm"],
                                    capture_output=True, timeout=15, check=True, creationflags=hidden)
            if len(pulled.stdout) != report["rawBytes"]:
                raise RuntimeError("Private PCM pull size does not match Android report.")
            (output / "capture.pcm").write_bytes(pulled.stdout)
            result["frames"] = report["frames"]
            if process is not None:
                result["transmitterAliveAfterCapture"] = process.poll() is None
                process.wait(timeout=35)
                result["transmitterExitCode"] = process.returncode
    finally:
        if process is not None and process.poll() is None:
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                process.kill()
                process.wait(timeout=5)
        result["command"] = command
        result["finishedAtUnixMs"] = int(time.time() * 1000)
        (output / "session.json").write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding="utf-8")
        print(json.dumps(result, ensure_ascii=False))


if __name__ == "__main__":
    main()
