"""Play an approved synthetic fixture to SONY HDMI and capture explicit SPDIF.

Preflight only reads active endpoint metadata and volume. No endpoint, volume or
HID register is changed. Capture returns PCM16 transport slots, whose content
may be IEC61937 rather than decoded PCM. Completion requires offline analysis
before making claims about optical provenance, AC-3 or six decoded channels.
"""
from __future__ import annotations

import argparse
import base64
from datetime import datetime, timezone
import hashlib
import json
from pathlib import Path
import re
import subprocess
import time
import uuid


ROOT = Path(__file__).resolve().parents[2]
GUID_TEXT = r"[0-9a-fA-F]{8}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{4}-[0-9a-fA-F]{12}"
FIXTURE_DIRECTORY = Path("android-a34/artifacts/optical-2026-10-09/vectors")
# These digests pin existing synthetic fixtures independently of mutable manifests.
FIXTURES = {
    "pcm-stereo-48k-minus36dbfs-5s.wav": {
        "mode": "pcm", "sha256": "d85c4aa0cc6dac66f366bb9dfc5b1bd4a85c31108431b2d209c2d927e6046ffa",
        "manifest": "manifest.json", "manifestKey": "pcm_stereo", "peakDbfs": -36.0,
    },
    "pcm-tv-speakers-minus24dbfs-5s.wav": {
        "mode": "pcm", "sha256": "2a3085c54fbcf801d8245c65222bc3959b4aa9fd3fdca737f2b221ff38620b48",
        "manifest": "pcm-tv-speakers-minus24dbfs-5s.json", "manifestKey": None, "peakDbfs": -24.0,
    },
    "tones-sequential-51.ac3": {
        "mode": "ac3", "sha256": "d8a9b917975b18c1357d3d9650fced77eaa8a262dd1412638204f9e5db6dfdd8",
        "manifest": "manifest.json", "manifestKey": "ac3_51", "peakDbfs": -20.0,
    },
}


def utc_now():
    return datetime.now(timezone.utc).isoformat()


def endpoint_ids(render_argument, capture_argument):
    render = re.fullmatch(r"wasapi/\{(" + GUID_TEXT + r")\}", render_argument)
    capture = re.fullmatch(r"\{0\.0\.1\.00000000\}\.\{(" + GUID_TEXT + r")\}", capture_argument)
    if not render or not capture:
        raise ValueError("Require explicit WASAPI render GUID and complete capture endpoint ID.")
    return ("{0.0.0.00000000}.{" + str(uuid.UUID(render.group(1))) + "}",
            "{0.0.1.00000000}.{" + str(uuid.UUID(capture.group(1))) + "}")


def validate_source(source, mode, root):
    source = Path(source).resolve()
    fixture = FIXTURES.get(source.name)
    if fixture is None or fixture["mode"] != mode:
        raise ValueError("Select an approved synthetic fixture matching --mode.")
    if source != (root / FIXTURE_DIRECTORY / source.name).resolve():
        raise ValueError("Source must be the existing fixture in this repository's private vector directory.")
    if not source.is_file() or not 0 < source.stat().st_size <= 2 * 1024 * 1024:
        raise ValueError("Synthetic source is missing, empty or exceeds its size bound.")
    digest = hashlib.sha256(source.read_bytes()).hexdigest()
    if digest != fixture["sha256"]:
        raise ValueError("Synthetic fixture SHA-256 differs from the approved signal.")
    manifest = source.parent / fixture["manifest"]
    manifest_checked = False
    if manifest.is_file():
        metadata = json.loads(manifest.read_text(encoding="utf-8-sig"))
        if fixture["manifestKey"] is not None:
            metadata = metadata[fixture["manifestKey"]]
        if metadata.get("file") != source.name or metadata.get("sha256", "").lower() != digest:
            raise ValueError("Fixture manifest disagrees with the approved file identity.")
        manifest_checked = True
    return {"path": str(source), "file": source.name, "sha256": digest,
            "bytes": source.stat().st_size, "syntheticFixtureRecognized": True,
            "manifestChecked": manifest_checked, "nominalSourcePeakDbfs": fixture["peakDbfs"]}


def ps_literal(value):
    return "'" + str(value).replace("'", "''") + "'"


def preflight_endpoints(root, render_id, capture_id):
    """Separate STA process: no stream initialization or endpoint setters."""
    definitions = root / "android-a34/scripts/windows-cm6206/CoreAudioFormatProbe.cs"
    capture_source = root / "scripts/optical-tests/WindowsSpdifCapture.cs"
    script = "\n".join([
        "$ErrorActionPreference = 'Stop'",
        "$OutputEncoding = [Text.UTF8Encoding]::new($false)",
        "[Console]::OutputEncoding = $OutputEncoding",
        "Add-Type -Path @(" + ps_literal(definitions) + ", " + ps_literal(capture_source) + ")",
        "$endpoints = @([Sistema51.Cm6206.FormatProbe]::Run())",
        "$render = @($endpoints | Where-Object { $_.Id -ieq " + ps_literal(render_id) + " })",
        "$capture = @($endpoints | Where-Object { $_.Id -ieq " + ps_literal(capture_id) + " })",
        "if ($render.Count -ne 1 -or $capture.Count -ne 1) { throw 'Explicit endpoints are not uniquely active.' }",
        "$result = [ordered]@{",
        "render = $render[0]; capture = $capture[0]",
        "renderVolume = [Sistema51.Cm6206.WindowsSpdifCapture]::ReadVolumeOnly(" + ps_literal(render_id) + ")",
        "captureVolume = [Sistema51.Cm6206.WindowsSpdifCapture]::ReadVolumeOnly(" + ps_literal(capture_id) + ")",
        "streamStarted = $false; volumeChanged = $false; defaultEndpointChanged = $false",
        "}",
        "$result | ConvertTo-Json -Depth 12 -Compress",
    ])
    encoded = base64.b64encode(script.encode("utf-16-le")).decode("ascii")
    checked = subprocess.run(["powershell.exe", "-STA", "-NoProfile", "-EncodedCommand", encoded],
                             capture_output=True, text=True, encoding="utf-8", errors="replace",
                             timeout=20, creationflags=getattr(subprocess, "CREATE_NO_WINDOW", 0))
    if checked.returncode:
        raise RuntimeError("Read-only endpoint preflight failed: " + checked.stderr.strip()[-1200:])
    result = json.loads(checked.stdout)
    validate_preflight(result, render_id, capture_id)
    return result


def validate_preflight(result, render_id, capture_id):
    for key, expected, flow in (("render", render_id, "render"), ("capture", capture_id, "capture")):
        device = result.get(key) or {}
        if (device.get("Id", "").lower() != expected.lower() or
                device.get("Flow") != flow or device.get("State") != 1):
            raise ValueError("Preflight does not identify the explicit active " + flow + " endpoint.")
    render_name = result["render"].get("FriendlyName", "").upper()
    capture_name = result["capture"].get("FriendlyName", "").upper()
    if "SONY" not in render_name:
        raise ValueError("Render endpoint must be the connected SONY TV; inspect endpoint metadata.")
    if "SPDIF" not in capture_name or "USB SOUND DEVICE" not in capture_name:
        raise ValueError("Capture endpoint must be the CM6206 SPDIF USB Sound Device input.")
    for key in ("renderVolume", "captureVolume"):
        volume = result.get(key) or {}
        if not volume.get("Ok") or not volume.get("CleanupComplete"):
            raise ValueError("Read-only endpoint volume inspection is incomplete: " + key)
        if volume.get("EndpointMuted") is not False or not (volume.get("EndpointMasterVolumeScalar") or 0) > 0:
            raise ValueError("Endpoint is muted or at zero volume; configure it before the test: " + key)


def ensure_new_name(output, name):
    # Include HID folders and all derived logs, rather than selected suffixes.
    for existing in output.iterdir():
        if existing.name == name or existing.name.startswith((name + ".", name + "-")):
            raise ValueError("Use a new output name to preserve all earlier evidence.")


def stop_player(process, result):
    if process is None:
        return
    try:
        if process.poll() is None:
            result["transmitterTerminationRequested"] = True
            process.terminate()
            try:
                process.wait(timeout=5)
            except subprocess.TimeoutExpired:
                result["transmitterKillRequested"] = True
                process.kill()
                process.wait(timeout=5)
        result["transmitterFinalExitCode"] = process.returncode
    except Exception as error:
        result["cleanupErrors"].append(str(error))
        # A failed terminate call must still get a final bounded kill attempt.
        try:
            if process.poll() is None:
                result["transmitterKillRequested"] = True
                process.kill()
                process.wait(timeout=5)
        except Exception as final_error:
            result["cleanupErrors"].append(str(final_error))


def run_test(args, root=ROOT):
    render_id, capture_id = endpoint_ids(args.endpoint, args.capture_endpoint)
    if not re.fullmatch(r"[A-Za-z0-9][A-Za-z0-9._-]{0,63}", args.name):
        raise ValueError("Invalid private output name.")
    seconds = getattr(args, "capture_seconds", 3)
    if isinstance(seconds, bool) or not isinstance(seconds, int) or not 1 <= seconds <= 10:
        raise ValueError("Capture duration must be an integer between 1 and 10 seconds.")
    source = validate_source(args.source, args.mode, root)
    output = root / "android-a34/artifacts" / ("windows-optical-" + datetime.now(timezone.utc).strftime("%Y-%m-%d"))
    output.mkdir(parents=True, exist_ok=True)
    ensure_new_name(output, args.name)
    result = {
        "schemaVersion": 2, "mode": args.mode, "source": source,
        "renderEndpoint": render_id, "captureEndpoint": capture_id, "captureSeconds": seconds,
        "startedAtUtc": utc_now(), "ok": False, "error": None,
        "defaultEndpointChanged": False, "endpointVolumeChanged": False, "hidRegistersChanged": False,
        "amplifierUsed": None, "amplifierConnectionVerified": False,
        "opticalValidated": False, "bitPerfectValidated": False, "decodedAudioValidated": False,
        "captureTransportFormat": "PCM16LE stereo 48000 Hz slots; PCM or IEC61937 content requires offline analysis",
        "transmitterTerminationRequested": False, "transmitterKillRequested": False,
        "cleanupErrors": [],
    }
    process = None
    phase = "preflight"
    hidden = getattr(subprocess, "CREATE_NO_WINDOW", 0)
    try:
        result["preflight"] = preflight_endpoints(root, render_id, capture_id)
        # Verify identity again after preflight and before the player opens it.
        validate_source(args.source, args.mode, root)
        command = [str(root / "configuracao-pc/mpv-portatil/mpv.com"),
                   "--no-config", "--no-video", "--ao=wasapi", "--audio-exclusive=yes",
                   "--audio-device=" + args.endpoint, "--loop-file=2",
                   "--log-file=" + str(output / (args.name + "-mpv.log")),
                   "--msg-level=all=info,ao/wasapi=debug"]
        if args.mode == "pcm":
            command += ["--audio-channels=stereo", "--audio-samplerate=48000", "--audio-format=s16", "--volume=100"]
        else:
            command += ["--audio-spdif=ac3", "--audio-channels=5.1"]
        command.append(source["path"])
        result["command"] = command
        phase = "transmitter_start"
        with (output / (args.name + "-console.txt")).open("xb") as console:
            process = subprocess.Popen(command, stdout=console, stderr=subprocess.STDOUT, creationflags=hidden)
            time.sleep(0.5)
            if process.poll() is not None:
                raise RuntimeError("HDMI transmitter exited before capture.")

            def read_hid(observation):
                folder = output / (args.name + "-hid-" + observation)
                sampled = subprocess.run(["powershell.exe", "-STA", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                          "-File", str(root / "android-a34/scripts/windows-cm6206/Read-HidRegisters.ps1"),
                                          "-OutputDirectory", str(folder)], capture_output=True, timeout=12,
                                         creationflags=hidden)
                (output / (args.name + "-hid-" + observation + "-console.txt")).write_bytes(sampled.stdout + sampled.stderr)
                result["hid" + observation.title() + "ExitCode"] = sampled.returncode
                if sampled.returncode != 0:
                    raise RuntimeError("Read-only HID observation failed; inspect private log.")

            if args.read_hid:
                phase = "hid_before"
                read_hid("before")
            phase = "capture"
            result["transmitterAliveBeforeCapture"] = process.poll() is None
            if not result["transmitterAliveBeforeCapture"]:
                raise RuntimeError("HDMI transmitter ended during pre-capture inspection.")
            result["captureStartedAtUtc"] = utc_now()
            captured = subprocess.run(["powershell.exe", "-STA", "-NoProfile", "-ExecutionPolicy", "Bypass",
                                      "-File", str(root / "scripts/optical-tests/capture-windows-spdif.ps1"),
                                      "-CaptureEndpointId", capture_id, "-OutputName", args.name, "-Seconds", str(seconds)],
                                     capture_output=True, timeout=seconds + 22, creationflags=hidden)
            result["captureFinishedAtUtc"] = utc_now()
            (output / (args.name + "-capture-console.txt")).write_bytes(captured.stdout + captured.stderr)
            result["captureProcessExitCode"] = captured.returncode
            result["transmitterAliveAfterCapture"] = process.poll() is None
            if captured.returncode != 0:
                raise RuntimeError("Windows capture failed; inspect private capture log.")
            if not result["transmitterAliveAfterCapture"]:
                raise RuntimeError("HDMI transmitter ended during capture; timing is inconclusive.")
            phase = "capture_report"
            capture_report_path = output / (args.name + "-report.json")
            capture_report = json.loads(capture_report_path.read_text(encoding="utf-8-sig"))
            result["captureReport"] = str(capture_report_path)
            result["captureSummary"] = {key: capture_report.get(key) for key in
                                        ("Ok", "EndpointId", "Frames", "Bytes", "SilentPackets",
                                         "DiscontinuityPackets", "TimestampErrorPackets", "CleanupComplete", "Outcome")}
            if (not capture_report.get("Ok") or not capture_report.get("CleanupComplete") or
                    capture_report.get("EndpointId", "").lower() != capture_id.lower() or
                    not (capture_report.get("Frames") or 0) > 0):
                raise RuntimeError("Capture report does not establish completed acquisition on the selected endpoint.")
            raw_path = output / (args.name + ".pcm")
            if (Path(capture_report.get("PcmFile", "")).resolve() != raw_path.resolve() or
                    not raw_path.is_file() or raw_path.stat().st_size != capture_report.get("Bytes") or
                    capture_report.get("Bytes") != capture_report.get("Frames") * 4):
                raise RuntimeError("Capture file and report disagree on raw transport identity or frame count.")
            result["captureRawSha256"] = hashlib.sha256(raw_path.read_bytes()).hexdigest()
            if args.read_hid:
                phase = "hid_after"
                read_hid("after")
            phase = "transmitter_finish"
            process.wait(timeout=35)
            result["transmitterExitCode"] = process.returncode
            if process.returncode != 0:
                raise RuntimeError("HDMI transmitter failed; inspect private mpv log.")
            result["ok"] = True
            result["outcome"] = "transport_test_completed_content_requires_offline_analysis"
    except Exception as error:
        result["error"] = str(error)
        result["errorType"] = type(error).__name__
        result["failurePhase"] = phase
        result["outcome"] = "failed"
    finally:
        stop_player(process, result)
        result["cleanupComplete"] = not result["cleanupErrors"]
        if not result["cleanupComplete"]:
            result["ok"] = False
            result["outcome"] = "failed"
            if result["error"] is None:
                result["error"] = "HDMI transmitter cleanup failed."
                result["failurePhase"] = "cleanup"
        result["finishedAtUtc"] = utc_now()
        session_path = output / (args.name + "-session.json")
        # Preserve sessions even in a concurrent same-name run.
        with session_path.open("x", encoding="utf-8") as session:
            session.write(json.dumps(result, indent=2) + "\n")
        result["sessionReport"] = str(session_path)
    return result


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("--endpoint", required=True, help="Explicit SONY TV WASAPI render GUID")
    parser.add_argument("--capture-endpoint", required=True)
    parser.add_argument("--mode", choices=("pcm", "ac3"), required=True)
    parser.add_argument("--source", required=True, type=Path)
    parser.add_argument("--name", required=True)
    parser.add_argument("--capture-seconds", type=int, choices=range(1, 11), default=3)
    parser.add_argument("--read-hid", action="store_true", help="Read CM6206 registers before/after capture; no register writes.")
    args = parser.parse_args()
    try:
        result = run_test(args)
    except (ValueError, OSError, KeyError) as error:
        parser.error(str(error))
    print(json.dumps(result))
    return 0 if result["ok"] else 1


if __name__ == "__main__":
    raise SystemExit(main())
