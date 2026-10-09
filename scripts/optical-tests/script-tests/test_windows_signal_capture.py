"""Coordinator regressions with fake processes/files; never access audio or HID."""
from __future__ import annotations

import argparse
import base64
import copy
from datetime import datetime, timezone
import hashlib
import importlib.util
import json
from pathlib import Path
import subprocess
import tempfile
import unittest
from unittest import mock


SOURCE = Path(__file__).resolve().parents[1] / "run-windows-signal-capture.py"
SPEC = importlib.util.spec_from_file_location("windows_signal_capture", SOURCE)
COORDINATOR = importlib.util.module_from_spec(SPEC)
SPEC.loader.exec_module(COORDINATOR)
RENDER_GUID = "c6f54926-6642-4aef-a4a1-942380c57f13"
CAPTURE_GUID = "7e729c77-2a58-45c2-8e4a-c88341a90744"
RENDER_ID = "{0.0.0.00000000}.{" + RENDER_GUID + "}"
CAPTURE_ID = "{0.0.1.00000000}.{" + CAPTURE_GUID + "}"


def valid_preflight():
    volume = {"Ok": True, "CleanupComplete": True, "EndpointMuted": False, "EndpointMasterVolumeScalar": 1.0}
    return {"render": {"Id": RENDER_ID, "Flow": "render", "State": 1, "FriendlyName": "SONY TV (NVIDIA High Definition Audio)"},
            "capture": {"Id": CAPTURE_ID, "Flow": "capture", "State": 1, "FriendlyName": "SPDIF Interface (USB Sound Device)"},
            "renderVolume": dict(volume), "captureVolume": dict(volume)}


class FakePlayer:
    def __init__(self, exit_code=0, ignore_terminate=False, finish_timeout=False):
        self.returncode = None
        self.final_exit_code = exit_code
        self.ignore_terminate = ignore_terminate
        self.finish_timeout = finish_timeout
        self.terminated = False
        self.killed = False

    def poll(self):
        return self.returncode

    def wait(self, timeout):
        if timeout == 35:
            if self.finish_timeout:
                raise subprocess.TimeoutExpired("fake mpv", timeout)
            self.returncode = self.final_exit_code
        elif self.killed:
            self.returncode = -9
        elif self.ignore_terminate:
            raise subprocess.TimeoutExpired("fake mpv", timeout)
        else:
            self.returncode = -15
        return self.returncode

    def terminate(self):
        self.terminated = True

    def kill(self):
        self.killed = True


class CoordinatorTests(unittest.TestCase):
    def setUp(self):
        self.temp = tempfile.TemporaryDirectory()
        self.addCleanup(self.temp.cleanup)
        self.root = Path(self.temp.name)
        self.fixture_name = "pcm-stereo-48k-minus36dbfs-5s.wav"
        self.fixture_bytes = b"offline synthetic test fixture; no audio device is involved"
        self.fixtures = copy.deepcopy(COORDINATOR.FIXTURES)
        self.fixtures[self.fixture_name]["sha256"] = hashlib.sha256(self.fixture_bytes).hexdigest()
        self.patch = mock.patch.object(COORDINATOR, "FIXTURES", self.fixtures)
        self.patch.start()
        self.addCleanup(self.patch.stop)
        self.source = self.root / COORDINATOR.FIXTURE_DIRECTORY / self.fixture_name
        self.source.parent.mkdir(parents=True)
        self.source.write_bytes(self.fixture_bytes)
        self.args = argparse.Namespace(endpoint="wasapi/{" + RENDER_GUID + "}", capture_endpoint=CAPTURE_ID,
                                       mode="pcm", source=self.source, name="offline-test", read_hid=False,
                                       capture_seconds=3)
        self.output = self.root / "android-a34/artifacts" / ("windows-optical-" + datetime.now(timezone.utc).strftime("%Y-%m-%d"))

    def session(self):
        return json.loads((self.output / (self.args.name + "-session.json")).read_text(encoding="utf-8"))

    def fake_capture(self, command, **kwargs):
        self.assertIn("-STA", command)
        self.assertIn("-Seconds", command)
        raw = self.output / (self.args.name + ".pcm")
        raw.write_bytes(bytes(400))
        report = {"Ok": True, "CleanupComplete": True, "EndpointId": CAPTURE_ID,
                  "Frames": 100, "Bytes": 400, "PcmFile": str(raw), "SilentPackets": 1,
                  "Outcome": "samples_captured_source_unvalidated"}
        (self.output / (self.args.name + "-report.json")).write_text(json.dumps(report), encoding="utf-8")
        return subprocess.CompletedProcess(command, 0, b"fake capture", b"")

    def execute(self, player=None, capture=None, preflight=None):
        player = player or FakePlayer()
        with mock.patch.object(COORDINATOR, "preflight_endpoints", return_value=preflight or valid_preflight()), \
             mock.patch.object(COORDINATOR.subprocess, "Popen", return_value=player), \
             mock.patch.object(COORDINATOR.subprocess, "run", side_effect=capture or self.fake_capture), \
             mock.patch.object(COORDINATOR.time, "sleep"):
            result = COORDINATOR.run_test(self.args, self.root)
        return result, player

    def test_pinned_hash_manifest_and_mode(self):
        identity = COORDINATOR.validate_source(self.source, "pcm", self.root)
        self.assertTrue(identity["syntheticFixtureRecognized"])
        self.assertFalse(identity["manifestChecked"])
        manifest = self.source.parent / "manifest.json"
        manifest.write_text(json.dumps({"pcm_stereo": {"file": self.fixture_name, "sha256": identity["sha256"]}}), encoding="utf-8")
        self.assertTrue(COORDINATOR.validate_source(self.source, "pcm", self.root)["manifestChecked"])
        manifest.write_text(json.dumps({"pcm_stereo": {"file": self.fixture_name, "sha256": "0" * 64}}), encoding="utf-8")
        with self.assertRaisesRegex(ValueError, "manifest"):
            COORDINATOR.validate_source(self.source, "pcm", self.root)
        with self.assertRaisesRegex(ValueError, "matching"):
            COORDINATOR.validate_source(self.source, "ac3", self.root)

    def test_tampered_or_relocated_source_never_starts_process(self):
        self.source.write_bytes(b"changed fixture")
        with mock.patch.object(COORDINATOR.subprocess, "Popen") as player, \
             mock.patch.object(COORDINATOR.subprocess, "run") as runner:
            with self.assertRaisesRegex(ValueError, "SHA-256"):
                COORDINATOR.run_test(self.args, self.root)
            player.assert_not_called()
            runner.assert_not_called()
        relocated = self.root / self.fixture_name
        relocated.write_bytes(self.fixture_bytes)
        with self.assertRaisesRegex(ValueError, "private vector directory"):
            COORDINATOR.validate_source(relocated, "pcm", self.root)

    def test_endpoint_guid_identity_and_active_sony_spdif(self):
        self.assertEqual(COORDINATOR.endpoint_ids(self.args.endpoint, CAPTURE_ID), (RENDER_ID, CAPTURE_ID))
        for bad in ("wasapi/{oops}", "wasapi/{" + RENDER_GUID + "}; Start-Process x", "auto"):
            with self.assertRaises(ValueError):
                COORDINATOR.endpoint_ids(bad, CAPTURE_ID)
        COORDINATOR.validate_preflight(valid_preflight(), RENDER_ID, CAPTURE_ID)
        for section, field, value in (("render", "FriendlyName", "USB Sound Device"),
                                      ("render", "State", 4), ("capture", "FriendlyName", "Microphone"),
                                      ("captureVolume", "EndpointMuted", True),
                                      ("renderVolume", "EndpointMasterVolumeScalar", 0)):
            candidate = valid_preflight()
            candidate[section][field] = value
            with self.subTest(section=section, field=field), self.assertRaises(ValueError):
                COORDINATOR.validate_preflight(candidate, RENDER_ID, CAPTURE_ID)

    def test_preflight_only_reads_in_sta_encoded_powershell(self):
        returned = subprocess.CompletedProcess([], 0, json.dumps(valid_preflight()), "")
        with mock.patch.object(COORDINATOR.subprocess, "run", return_value=returned) as runner:
            COORDINATOR.preflight_endpoints(self.root, RENDER_ID, CAPTURE_ID)
        command = runner.call_args.args[0]
        self.assertIn("-STA", command)
        script = base64.b64decode(command[-1]).decode("utf-16-le")
        self.assertIn("::ReadVolumeOnly", script)
        self.assertIn("FormatProbe]::Run()", script)
        self.assertNotIn(".Initialize(", script)
        self.assertNotIn(".Start(", script)
        self.assertNotIn("SetMute", script)
        self.assertEqual(runner.call_args.kwargs["timeout"], 20)

    def test_success_preserves_unknown_evidence_and_requested_duration(self):
        self.args.capture_seconds = 9
        with mock.patch.object(COORDINATOR, "preflight_endpoints", return_value=valid_preflight()), \
             mock.patch.object(COORDINATOR.subprocess, "Popen", return_value=FakePlayer()), \
             mock.patch.object(COORDINATOR.subprocess, "run", side_effect=self.fake_capture) as runner, \
             mock.patch.object(COORDINATOR.time, "sleep"):
            result = COORDINATOR.run_test(self.args, self.root)
        self.assertTrue(result["ok"])
        self.assertTrue(result["cleanupComplete"])
        self.assertIsNone(result["amplifierUsed"])
        self.assertFalse(result["opticalValidated"])
        self.assertFalse(result["decodedAudioValidated"])
        self.assertFalse(result["bitPerfectValidated"])
        self.assertEqual(runner.call_args.args[0][-2:], ["-Seconds", "9"])
        self.assertEqual(result["captureRawSha256"], hashlib.sha256(bytes(400)).hexdigest())
        self.assertTrue(self.session()["ok"])
        self.assertIn("finishedAtUtc", self.session())

    def test_player_failure_is_reported_despite_successful_capture(self):
        result, player = self.execute(FakePlayer(exit_code=7))
        self.assertFalse(result["ok"])
        self.assertEqual(result["transmitterExitCode"], 7)
        self.assertEqual(result["failurePhase"], "transmitter_finish")
        self.assertIn("transmitter failed", self.session()["error"])
        self.assertFalse(player.terminated)

    def test_capture_failure_terminates_player_and_saves_error(self):
        captured = lambda command, **kwargs: subprocess.CompletedProcess(command, 1, b"", b"driver failure")
        result, player = self.execute(capture=captured)
        self.assertFalse(result["ok"])
        self.assertTrue(player.terminated)
        self.assertTrue(result["cleanupComplete"])
        self.assertEqual(self.session()["failurePhase"], "capture")

    def test_transmitter_ending_during_capture_does_not_pass(self):
        player = FakePlayer()
        def capture(command, **kwargs):
            reported = self.fake_capture(command, **kwargs)
            player.returncode = 0
            return reported
        result, _ = self.execute(player, capture)
        self.assertFalse(result["ok"])
        self.assertFalse(result["transmitterAliveAfterCapture"])
        self.assertIn("timing is inconclusive", self.session()["error"])

    def test_timeout_gets_bounded_terminate_then_kill(self):
        player = FakePlayer(ignore_terminate=True)
        def capture(command, **kwargs):
            raise subprocess.TimeoutExpired(command, kwargs["timeout"])
        result, _ = self.execute(player, capture)
        self.assertFalse(result["ok"])
        self.assertTrue(player.terminated)
        self.assertTrue(player.killed)
        self.assertTrue(result["cleanupComplete"])
        self.assertEqual(self.session()["errorType"], "TimeoutExpired")

    def test_inconsistent_raw_file_report_does_not_pass(self):
        def capture(command, **kwargs):
            returned = self.fake_capture(command, **kwargs)
            (self.output / (self.args.name + ".pcm")).write_bytes(bytes(8))
            return returned
        result, player = self.execute(capture=capture)
        self.assertFalse(result["ok"])
        self.assertTrue(player.terminated)
        self.assertEqual(self.session()["failurePhase"], "capture_report")

    def test_preflight_failure_has_session_and_never_plays(self):
        with mock.patch.object(COORDINATOR, "preflight_endpoints", side_effect=ValueError("SONY missing")), \
             mock.patch.object(COORDINATOR.subprocess, "Popen") as player:
            result = COORDINATOR.run_test(self.args, self.root)
        player.assert_not_called()
        self.assertFalse(result["ok"])
        self.assertEqual(self.session()["failurePhase"], "preflight")
        self.assertIn("startedAtUtc", self.session())
        self.assertIn("finishedAtUtc", self.session())

    def test_existing_hid_or_capture_logs_and_invalid_duration_are_preserved(self):
        self.output.mkdir()
        existing = self.output / (self.args.name + "-capture-console.txt")
        existing.write_bytes(b"earlier evidence")
        with mock.patch.object(COORDINATOR.subprocess, "Popen") as player:
            with self.assertRaisesRegex(ValueError, "preserve"):
                COORDINATOR.run_test(self.args, self.root)
        player.assert_not_called()
        self.assertEqual(existing.read_bytes(), b"earlier evidence")
        for seconds in (0, 11, True, 1.5):
            self.args.capture_seconds = seconds
            with self.subTest(seconds=seconds), self.assertRaisesRegex(ValueError, "duration"):
                COORDINATOR.run_test(self.args, self.root)


if __name__ == "__main__":
    unittest.main(verbosity=2)
