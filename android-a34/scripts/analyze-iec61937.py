"""Analyze PRIVATE stereo PCM16 capture for IEC61937 AC-3 candidates, without playback.

This intentionally does not prove the optical source, original codec, unchanged USB
bits, CRC correctness, or decodable audio. A preamble alone is never labelled AC-3.
Pc/Pd, payload byte order and AC-3 burst spacing follow the primary references:
https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/spdifdec.c
https://github.com/FFmpeg/FFmpeg/blob/master/libavformat/spdif.h
AC-3 sync/header and frame-size data:
https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/ac3_parser.c
https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/ac3tab.c

Usage: python analyze-iec61937.py PRIVATE/capture.pcm [--output report.json]
       python analyze-iec61937.py --self-test
Raw captures and extracted media must remain private and ignored by Git.
"""

import argparse
import hashlib
import json
import math
from pathlib import Path
import struct
import unittest

PREAMBLES = {b"\x72\xf8\x1f\x4e": "little", b"\xf8\x72\x4e\x1f": "big"}
BITRATES = (32, 40, 48, 56, 64, 80, 96, 112, 128, 160, 192, 224, 256, 320, 384, 448, 512, 576, 640)
WORDS_44100 = (69, 87, 104, 121, 139, 174, 208, 243, 278, 348, 417, 487, 557, 696, 835, 975, 1114, 1253, 1393)
CHANNEL_COUNTS = (2, 1, 2, 3, 3, 4, 4, 5)
MAX_BYTES = 16 * 1024 * 1024


def swap16(data):
    if len(data) % 2:
        raise ValueError("16-bit payload has an odd byte count")
    result = bytearray(len(data))
    result[0::2], result[1::2] = data[1::2], data[0::2]
    return bytes(result)


class Bits:
    def __init__(self, data, offset=0):
        self.data, self.offset = data, offset

    def read(self, count):
        if self.offset + count > len(self.data) * 8:
            raise ValueError("truncated AC-3 header")
        value = 0
        for _ in range(count):
            value = (value << 1) | ((self.data[self.offset // 8] >> (7 - self.offset % 8)) & 1)
            self.offset += 1
        return value


def ac3_header(payload):
    if len(payload) < 7 or payload[:2] != b"\x0b\x77":
        raise ValueError("AC-3 syncword/header absent")
    bits = Bits(payload, 32)
    fscod, frmsizecod, bsid, bsmod, acmod = (bits.read(n) for n in (2, 6, 5, 3, 3))
    if fscod == 3 or frmsizecod > 37 or bsid > 10:
        raise ValueError("unsupported or invalid AC-3 header")
    if acmod & 1 and acmod != 1:
        bits.read(2)
    if acmod & 4:
        bits.read(2)
    if acmod == 2:
        bits.read(2)
    lfeon = bits.read(1)
    index = frmsizecod // 2
    bitrate = BITRATES[index]
    words = (bitrate * 2, WORDS_44100[index] + (frmsizecod & 1), bitrate * 3)[fscod]
    rate_shift = max(bsid, 8) - 8
    return {"fscod": fscod, "frmsizecod": frmsizecod, "bsid": bsid, "bsmod": bsmod,
            "acmod": acmod, "lfeon": lfeon, "channels": CHANNEL_COUNTS[acmod] + lfeon,
            "sampleRate": (48000, 44100, 32000)[fscod] >> rate_shift,
            "bitrate": (bitrate * 1000) >> rate_shift, "frameBytes": words * 2,
            "crcValidated": False}


def parse_burst(data, offset, endian):
    result = {"byteOffset": offset, "wordEndian": endian, "stereoFrameAligned": offset % 4 == 0,
              "status": "invalid", "ac3Validated": False, "crcValidated": False}
    if offset + 8 > len(data):
        return result | {"reason": "truncated_burst_header"}
    pc, pd = struct.unpack_from("<HH" if endian == "little" else ">HH", data, offset + 4)
    data_type = pc & 0xff  # Matches FFmpeg spdif_get_offset_and_codec().
    result.update({"pc": pc, "pd": pd, "dataType": data_type, "errorFlag": bool(pc & 0x80)})
    if data_type != 1:
        return result | {"status": "unsupported", "reason": "not_ac3_type_1"}
    if pd == 0 or pd % 16:
        return result | {"reason": "ac3_length_not_positive_16bit_boundary"}
    payload_bytes = pd // 8
    result["payloadBytes"] = payload_bytes
    if payload_bytes + 8 > 1536 * 4:
        return result | {"reason": "payload_exceeds_ac3_burst_period"}
    if offset + 8 + payload_bytes > len(data):
        return result | {"reason": "truncated_payload"}
    payload = data[offset + 8:offset + 8 + payload_bytes]
    if endian == "little":
        payload = swap16(payload)
    try:
        header = ac3_header(payload)
    except ValueError as error:
        return result | {"reason": str(error)}
    result["ac3Header"] = header
    if header["frameBytes"] != payload_bytes:
        return result | {"reason": "pd_does_not_match_ac3_frame_size"}
    result.update({"status": "header_consistent_ac3_candidate", "payloadSha256": hashlib.sha256(payload).hexdigest(),
                   "reason": "header_and_length_consistent_crc_and_decode_not_validated"})
    return result


def analyze(data, sample_rate=48000):
    if len(data) > MAX_BYTES:
        raise ValueError("capture exceeds bounded analyzer input (16 MiB)")
    frames = len(data) // 4
    channels = []
    for channel in range(2):
        samples = [struct.unpack_from("<h", data, i * 4 + channel * 2)[0] for i in range(frames)]
        channels.append({"channelIndex": channel, "samples": frames,
                         "min": min(samples) if samples else None, "max": max(samples) if samples else None,
                         "peakInteger": max(map(abs, samples), default=0),
                         "rmsInteger": math.sqrt(sum(x * x for x in samples) / frames) if frames else 0})
    markers = []
    for preamble, endian in PREAMBLES.items():
        start = 0
        while True:
            offset = data.find(preamble, start)
            if offset < 0:
                break
            markers.append(parse_burst(data, offset, endian))
            start = offset + 1
    markers.sort(key=lambda marker: marker["byteOffset"])
    candidates = [m for m in markers if m["status"] == "header_consistent_ac3_candidate"]
    periodic_pairs = []
    for previous, current in zip(candidates, candidates[1:]):
        spacing = current["byteOffset"] - previous["byteOffset"]
        periodic_pairs.append({"fromByteOffset": previous["byteOffset"], "toByteOffset": current["byteOffset"],
                               "spacingBytes": spacing, "spacingStereoFrames": spacing / 4,
                               "matches1536Frames": spacing == 6144 and previous["wordEndian"] == current["wordEndian"]})
    return {"rawBytes": len(data), "frames": frames, "trailingBytes": len(data) % 4,
            "pcmFormat": {"encoding": "signed_pcm16_little_endian", "channels": 2, "sampleRate": sample_rate},
            "durationSeconds": frames / sample_rate, "pcmStatistics": channels,
            "preambleCount": len(markers), "headerConsistentAc3Candidates": len(candidates), "bursts": markers,
            "periodicity": periodic_pairs, "repeatedAc3BurstStructureObserved": any(p["matches1536Frames"] for p in periodic_pairs),
            "crcValidated": False, "ac3Validated": False, "opticalSourceValidated": False,
            "bitPerfectValidated": False,
            "interpretation": "Header-consistent IEC61937 candidates require CRC and decode validation. Without known source, PCM statistics cannot prove stereo origin, a downmix, or optical routing. No preambles does not rule out an unavailable or transformed encoded transport."}


def synthetic_frame(fscod=0, frmsizecod=10, acmod=7, lfeon=1, bsid=8):
    # Headers only: synthetic fixture intentionally is NOT a decodable/CRC-valid AC-3 frame.
    fields = [(fscod, 2), (frmsizecod, 6), (bsid, 5), (0, 3), (acmod, 3)]
    if acmod & 1 and acmod != 1:
        fields.append((0, 2))
    if acmod & 4:
        fields.append((0, 2))
    if acmod == 2:
        fields.append((0, 2))
    fields.append((lfeon, 1))
    bits = "".join(f"{value:0{count}b}" for value, count in fields)
    bits += "0" * (-len(bits) % 8)
    header = b"\x0b\x77\x00\x00" + int(bits, 2).to_bytes(len(bits) // 8, "big")
    metadata = ac3_header(header)
    return header + bytes(metadata["frameBytes"] - len(header))


def synthetic_burst(frame, endian="little", pd=None):
    preamble = next(p for p, e in PREAMBLES.items() if e == endian)
    header = preamble + struct.pack("<HH" if endian == "little" else ">HH", 1, len(frame) * 8 if pd is None else pd)
    payload = swap16(frame) if endian == "little" else frame
    burst = header + payload
    return burst + bytes(max(0, 6144 - len(burst)))


class ParserTests(unittest.TestCase):
    def test_both_endians_and_periodicity(self):
        for endian in ("little", "big"):
            with self.subTest(endian=endian):
                frame = synthetic_frame()
                report = analyze(synthetic_burst(frame, endian) * 3)
                self.assertEqual(report["headerConsistentAc3Candidates"], 3)
                self.assertTrue(report["repeatedAc3BurstStructureObserved"])
                self.assertEqual(report["bursts"][0]["ac3Header"]["channels"], 6)
                self.assertEqual(report["bursts"][0]["ac3Header"]["sampleRate"], 48000)
                self.assertFalse(report["ac3Validated"])

    def test_marker_alone_never_validates_codec(self):
        report = analyze(b"\x72\xf8\x1f\x4e")
        self.assertEqual(report["headerConsistentAc3Candidates"], 0)
        self.assertEqual(report["bursts"][0]["reason"], "truncated_burst_header")

    def test_length_mismatch_and_truncation(self):
        frame = synthetic_frame()
        mismatch = analyze(synthetic_burst(frame, pd=(len(frame) - 2) * 8))
        self.assertEqual(mismatch["headerConsistentAc3Candidates"], 0)
        self.assertEqual(mismatch["bursts"][0]["reason"], "pd_does_not_match_ac3_frame_size")
        truncated = analyze(synthetic_burst(frame)[:30])
        self.assertEqual(truncated["bursts"][0]["reason"], "truncated_payload")
        partial_word = analyze(synthetic_burst(frame, pd=17))
        self.assertEqual(partial_word["bursts"][0]["reason"], "ac3_length_not_positive_16bit_boundary")

    def test_ac3_header_modes_rates_and_frame_sizes(self):
        for code, expected in ((0, 48000), (1, 44100), (2, 32000)):
            header = ac3_header(synthetic_frame(fscod=code, acmod=2, lfeon=0))
            self.assertEqual(header["sampleRate"], expected)
            self.assertEqual(header["channels"], 2)
        even = ac3_header(synthetic_frame(fscod=1, frmsizecod=10))["frameBytes"]
        odd = ac3_header(synthetic_frame(fscod=1, frmsizecod=11))["frameBytes"]
        self.assertEqual(odd - even, 2)
        self.assertEqual(ac3_header(synthetic_frame(bsid=10))["sampleRate"], 12000)

    def test_pcm_and_extreme_sample(self):
        report = analyze(struct.pack("<hhhh", -32768, 0, 32767, 10))
        self.assertEqual(report["preambleCount"], 0)
        self.assertEqual(report["pcmStatistics"][0]["peakInteger"], 32768)
        self.assertFalse(report["opticalSourceValidated"])

    def test_unaligned_candidate_is_reported_and_period_is_measured(self):
        frame = synthetic_frame()
        report = analyze(b"x" + synthetic_burst(frame) + b"xx" + synthetic_burst(frame))
        self.assertFalse(report["bursts"][0]["stereoFrameAligned"])
        self.assertFalse(report["periodicity"][0]["matches1536Frames"])


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument("capture", nargs="?", type=Path)
    parser.add_argument("--sample-rate", type=int, choices=(44100, 48000), default=48000)
    parser.add_argument("--output", type=Path)
    parser.add_argument("--self-test", action="store_true")
    args = parser.parse_args()
    if args.self_test:
        suite = unittest.defaultTestLoader.loadTestsFromTestCase(ParserTests)
        raise SystemExit(0 if unittest.TextTestRunner(verbosity=2).run(suite).wasSuccessful() else 1)
    if args.capture is None:
        parser.error("capture path required unless --self-test is used")
    if args.capture.stat().st_size > MAX_BYTES:
        parser.error("capture exceeds bounded analyzer input (16 MiB)")
    report = analyze(args.capture.read_bytes(), args.sample_rate)
    output = json.dumps(report, indent=2, ensure_ascii=False)
    if args.output:
        args.output.write_text(output + "\n", encoding="utf-8")
    print(output)


if __name__ == "__main__":
    main()
