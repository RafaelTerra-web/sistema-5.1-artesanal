"""Scoped DRIVERON/relay regressions with fake HID and processes; no hardware or network."""
from __future__ import annotations

import contextlib
import hashlib
import importlib.util
import io
import json
from pathlib import Path
import sys
import tempfile
import types
import unittest
from unittest import mock


SOURCE = Path(__file__).resolve().parents[1] / "test-analog-driver.py"
SPEC = importlib.util.spec_from_file_location("analog_driver_coordinator", SOURCE)
COORDINATOR = importlib.util.module_from_spec(SPEC)
# Never import a real HID binding, including on machines where hid is not installed.
FAKE_HID_MODULE = types.ModuleType("hid")
with mock.patch.dict(sys.modules, {"hid": FAKE_HID_MODULE}):
    SPEC.loader.exec_module(COORDINATOR)

RENDER = "wasapi/{87276929-efec-4166-b6b5-7fdde08a6a6e}"
FIXTURE = b"private synthetic mock fixture"
FIXTURE_SHA = hashlib.sha256(FIXTURE).hexdigest()


class FakeHidDevice:
    def __init__(self, original, journal):
        self.registers = [0, 0, original, 0, 0, 0]
        self.journal = journal
        self.writes = []
        self.closed = False

    def open_path(self, path):
        assert path == b"mock-cm6206"

    def write(self, data):
        data = bytes(data)
        self.writes.append(data)
        assert data[1] == 0x20 and data[4] == 2, "Only REG2 writes are permitted"
        journal = json.loads(self.journal.read_text(encoding="utf-8"))
        assert journal["registerWriteSubmitted"] is True, "Restore obligation must be saved before submission"
        self.registers[2] = data[2] | (data[3] << 8)
        return 5

    def close(self):
        self.closed = True


class AnalogDriverTests(unittest.TestCase):
    def run_session(self, name, relay=False, relay_exit=0, original=0x6004, changed_other_bits=0,
                    relay_six=False, native_overrides=None, spoken_six=False, volume=20):
        temp = tempfile.TemporaryDirectory()
        self.addCleanup(temp.cleanup)
        root = Path(temp.name)
        day = COORDINATOR.datetime.now(COORDINATOR.timezone.utc).strftime("%Y-%m-%d")
        vectors = root / "android-a34/artifacts/optical-2026-10-09/vectors"
        vectors.mkdir(parents=True)
        for filename in ("pcm-tv-speakers-minus24dbfs-5s.wav", "tones-sequential-51.ac3"):
            (vectors / filename).write_bytes(FIXTURE)
        spoken = root / "android-a34/artifacts/spoken-2026-10-09/spoken-channels-ready-02.ac3"
        spoken.parent.mkdir(parents=True)
        spoken.write_bytes(FIXTURE)
        six_channel_mode = relay_six or spoken_six
        journal = root / "android-a34/artifacts" / ("analog-driver-" + day) / name / "session.json"
        device = FakeHidDevice(original, journal)
        children = []

        def read_register(target, register):
            self.assertIs(target, device)
            return {"register": register, "value": target.registers[register], "reply": [], "headphoneSense": True}

        def make_process(command, **kwargs):
            test = self

            class FakeProcess:
                def __init__(self):
                    self.pid = 100 + len(children)
                    self.returncode = None
                    self.terminated = False

                def poll(self):
                    return self.returncode

                def wait(self, timeout):
                    if command[0] == "powershell.exe":
                        test.assertEqual(timeout, 45 if spoken_six else 32)
                        test.assertTrue(device.registers[2] & COORDINATOR.MASK)
                        self.returncode = relay_exit
                        if relay_exit == 0:
                            destination = root / "android-a34/artifacts" / ("windows-optical-relay-" + day)
                            destination.mkdir(parents=True)
                            output_name = command[command.index("-OutputName") + 1]
                            native_report = {
                                "Ok": True, "FrontsOnlyOutput": not six_channel_mode,
                                "SixChannelsAudibleOutput": six_channel_mode, "PlaybackMuted": False,
                                "PlaybackLinearGain": volume / 100.0, "CleanupComplete": True,
                            }
                            native_report.update(native_overrides or {})
                            (destination / (output_name + "-report.json")).write_text(json.dumps(native_report), encoding="utf-8")
                    else:
                        test.assertIn(timeout, (22, 35))
                        device.registers[2] |= changed_other_bits
                        self.returncode = 0
                    return self.returncode

                def terminate(self):
                    self.terminated = True
                    self.returncode = -15

                def kill(self):
                    self.returncode = -9

            process = FakeProcess()
            children.append((command, process))
            return process

        argv = [str(SOURCE), "--name", name, "--render-endpoint", RENDER]
        if relay or six_channel_mode:
            mode = "--spoken-six" if spoken_six else "--relay-six" if relay_six else "--relay-fronts"
            argv += [mode, "--volume", str(volume)]
        with mock.patch.object(COORDINATOR, "ROOT", root), \
                mock.patch.object(COORDINATOR, "SOURCE_SHA", FIXTURE_SHA), \
                mock.patch.object(COORDINATOR, "AC3_SOURCE_SHA", FIXTURE_SHA), \
                mock.patch.object(COORDINATOR, "SPOKEN_SOURCE_SHA", FIXTURE_SHA), \
                mock.patch.object(COORDINATOR, "read_register", read_register), \
                mock.patch.object(COORDINATOR.hid, "device", return_value=device, create=True), \
                mock.patch.object(COORDINATOR.hid, "enumerate", return_value=[{
                    "interface_number": 3, "path": b"mock-cm6206"}], create=True), \
                mock.patch.object(COORDINATOR.subprocess, "Popen", side_effect=make_process), \
                mock.patch.object(COORDINATOR.time, "sleep"), \
                mock.patch.object(sys, "argv", argv), contextlib.redirect_stdout(io.StringIO()):
            exit_code = COORDINATOR.main()
        report = json.loads(journal.read_text(encoding="utf-8"))
        self.assertTrue(report["restorationVerified"])
        self.assertIsNone(report["physicalAudioHeard"])
        self.assertTrue(device.closed)
        self.assertEqual(device.registers[2], original | changed_other_bits)
        self.assertTrue(all(write[4] == 2 for write in device.writes))
        return exit_code, report, device, children

    def test_direct_usb_default_is_preserved(self):
        code, report, device, children = self.run_session("direct")
        self.assertEqual(code, 0)
        self.assertEqual(len(children), 1)
        self.assertIn("--af=volume=0.32", children[0][0])
        self.assertIn("--audio-channels=stereo", children[0][0])
        self.assertEqual(len(device.writes), 2)
        self.assertFalse(report["relayFronts"])

    def test_optical_relay_is_verified_before_restoring_driver(self):
        code, report, device, children = self.run_session("relay", relay=True)
        self.assertEqual(code, 0)
        self.assertEqual(len(children), 2)
        self.assertIn("--audio-device=" + COORDINATOR.HDMI_ENDPOINT, children[0][0])
        self.assertIn("--audio-spdif=ac3", children[0][0])
        self.assertIn("-AudibleFronts", children[1][0])
        self.assertEqual(children[1][0][children[1][0].index("-Seconds") + 1], "15")
        self.assertEqual(children[1][0][children[1][0].index("-Volume") + 1], "20")
        self.assertTrue(report["relayVerified"])
        self.assertEqual(report["relayExitCode"], 0)
        self.assertEqual(report["afterRelay"]["value"], 0xE004)
        self.assertEqual(len(device.writes), 2)

    def test_native_relay_failure_is_not_hidden_by_source_success(self):
        code, report, device, children = self.run_session("relay-failed", relay=True, relay_exit=7)
        self.assertEqual(code, 1)
        self.assertEqual(report["relayExitCode"], 7)
        self.assertIn("Native optical-to-USB relay failed", report["error"])
        self.assertTrue(children[0][1].terminated)
        self.assertEqual(len(device.writes), 2)

    def test_six_channel_relay_requires_correct_native_mode(self):
        code, report, device, children = self.run_session("relay-six", relay_six=True)
        self.assertEqual(code, 0)
        self.assertTrue(report["relaySix"])
        self.assertFalse(report["relayFronts"])
        self.assertTrue(report["allAmplifiersConnectedByUser"])
        self.assertIn("-AudibleSix", children[1][0])
        self.assertNotIn("-AudibleFronts", children[1][0])
        self.assertTrue(report["relayVerified"])
        self.assertEqual(report["relayExitCode"], 0)
        self.assertEqual(len(device.writes), 2)

    def test_six_channel_request_rejects_native_fronts_only(self):
        code, report, device, children = self.run_session("wrong-fronts-only", relay_six=True,
            native_overrides={"FrontsOnlyOutput": True, "SixChannelsAudibleOutput": False})
        self.assertEqual(code, 1)
        self.assertFalse(report["relayVerified"])
        self.assertEqual(report["relayExitCode"], 0)
        self.assertIn("did not confirm successful transport and output", report["error"])
        self.assertTrue(children[0][1].terminated)
        self.assertEqual(len(device.writes), 2)

    def test_six_channel_request_rejects_missing_positive_six_flag(self):
        code, report, device, _ = self.run_session("six-flag-false", relay_six=True,
            native_overrides={"SixChannelsAudibleOutput": False})
        self.assertEqual(code, 1)
        self.assertFalse(report["relayVerified"])
        self.assertEqual(len(device.writes), 2)

    def test_spoken_six_uses_fixed_voice_source_and_thirty_second_relay(self):
        code, report, device, children = self.run_session("spoken-six", spoken_six=True, volume=50)
        self.assertEqual(code, 0)
        self.assertTrue(report["spokenSix"])
        self.assertTrue(report["relaySix"])
        self.assertFalse(report["relayFronts"])
        self.assertEqual(report["relayLinearVolumePercent"], 50)
        self.assertEqual(report["relaySeconds"], 30)
        self.assertIn("--loop-file=1", children[0][0])
        self.assertNotIn("--loop-file=2", children[0][0])
        self.assertEqual(Path(children[0][0][-1]).name, "spoken-channels-ready-02.ac3")
        self.assertIn("-AudibleSix", children[1][0])
        self.assertEqual(children[1][0][children[1][0].index("-Seconds") + 1], "30")
        self.assertEqual(children[1][0][children[1][0].index("-Volume") + 1], "50")
        self.assertTrue(report["relayVerified"])
        self.assertEqual(len(device.writes), 2)

    def test_spoken_six_rejects_native_gain_mismatch_and_restores_driver(self):
        code, report, device, children = self.run_session("spoken-gain-mismatch", spoken_six=True,
            volume=50, native_overrides={"PlaybackLinearGain": 0.2})
        self.assertEqual(code, 1)
        self.assertFalse(report["relayVerified"])
        self.assertEqual(report["relayExitCode"], 0)
        self.assertIn("did not confirm successful transport and output", report["error"])
        self.assertTrue(children[0][1].terminated)
        self.assertEqual(len(device.writes), 2)

    def test_preexisting_driver_bit_and_new_other_bits_are_preserved(self):
        code, report, device, _ = self.run_session("already-on", relay=True, original=0xE004, changed_other_bits=0x0400)
        self.assertEqual(code, 0)
        self.assertEqual(device.registers[2], 0xE404)
        self.assertEqual(device.writes, [])
        self.assertFalse(report["registerWriteSubmitted"])

    def test_restoration_changes_only_driver_bit_after_external_change(self):
        code, report, device, _ = self.run_session("new-other-bit", relay=True, changed_other_bits=0x0400)
        self.assertEqual(code, 0)
        self.assertEqual(len(device.writes), 2)
        self.assertEqual(device.registers[2], 0x6404)
        self.assertEqual(report["restoredRegister"]["value"], 0x6404)


if __name__ == "__main__":
    unittest.main()
