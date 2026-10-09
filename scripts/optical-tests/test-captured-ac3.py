"""Offline negative/positive checks for capture validation; no audio device IO."""
import importlib.util
from pathlib import Path
import struct
import unittest

spec = importlib.util.spec_from_file_location("validator", Path(__file__).with_name("validate-captured-ac3.py"))
validator = importlib.util.module_from_spec(spec)
spec.loader.exec_module(validator)
fixture = validator.ROOT / "android-a34/app/src/debug/assets/fixtures/tones-51.ac3"


def frame():
    data = fixture.read_bytes()
    header = validator.iec.ac3_header(data)
    return data[:header["frameBytes"]]


def burst(payload, endian):
    prefix = b"\x72\xf8\x1f\x4e" if endian == "little" else b"\xf8\x72\x4e\x1f"
    header = prefix + struct.pack("<HH" if endian == "little" else ">HH", 1, len(payload) * 8)
    encoded = validator.iec.swap16(payload) if endian == "little" else payload
    return (header + encoded).ljust(6144, b"\0")


class ValidationTests(unittest.TestCase):
    def test_comparison_matches_window_crossing_loop_boundary(self):
        source = validator.np.random.default_rng(17).normal(size=(100, 6)).astype('float32')
        received = validator.np.concatenate((source[80:], source[:30]))
        result = validator.compare(source, received)
        self.assertEqual(result['commonAlignmentFrames'], 80)
        self.assertGreater(result['normalizedCorrelationAllChannels'], .999999)

    def test_standard_crc_vector(self):
        self.assertEqual(validator.crc16(b"123456789"), 0xFEE8)

    def test_real_fixture_and_both_word_orders(self):
        payload = frame()
        for endian in ("little", "big"):
            _, received = validator.extract(burst(payload, endian) * 2)
            self.assertEqual(received, [payload, payload])

    def test_damaged_payload_is_rejected(self):
        data = bytearray(burst(frame(), "little"))
        data[100] ^= 1
        with self.assertRaisesRegex(ValueError, "CRC"):
            validator.extract(bytes(data))

    def test_missing_burst_is_rejected(self):
        data = burst(frame(), "little")
        with self.assertRaisesRegex(ValueError, "gap"):
            validator.extract(data + b"\0" * 6144 + data)

    def test_silence_is_rejected(self):
        with self.assertRaisesRegex(ValueError, "No complete"):
            validator.extract(b"\0" * 6144)


if __name__ == "__main__":
    unittest.main()
