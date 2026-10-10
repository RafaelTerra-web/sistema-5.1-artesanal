"""Inspect private Fire TV captures without playback or exporting program media."""
import argparse
from collections import Counter
import importlib.util
import json
from pathlib import Path

ROOT = Path(__file__).resolve().parents[2]
spec = importlib.util.spec_from_file_location('iec', ROOT / 'android-a34/scripts/analyze-iec61937.py')
iec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(iec)

def crc16(data):
    value = 0
    for byte in data:
        value ^= byte << 8
        for _ in range(8):
            value = ((value << 1) ^ (0x8005 if value & 0x8000 else 0)) & 0xffff
    return value

def inspect(raw):
    parsed = iec.analyze(raw)
    candidates = [b for b in parsed['bursts'] if b['status'] == 'header_consistent_ac3_candidate']
    valid = []
    for burst in candidates:
        start = burst['byteOffset'] + 8
        payload = raw[start:start + burst['payloadBytes']]
        if burst['wordEndian'] == 'little':
            payload = iec.swap16(payload)
        if crc16(payload[2:]) == 0:
            valid.append(burst)
    gaps = Counter(b['byteOffset'] - a['byteOffset'] for a, b in zip(candidates, candidates[1:]))
    return {
        'bytes': len(raw), 'carrierFrames': parsed['frames'],
        'preambles': parsed['preambleCount'], 'completeCandidates': len(candidates),
        'crcValidFrames': len(valid), 'crcInvalidFrames': len(candidates) - len(valid),
        'channelsInCrcValidFrames': dict(Counter(str(b['ac3Header']['channels']) for b in valid)),
        'sampleRatesInCrcValidFrames': dict(Counter(str(b['ac3Header']['sampleRate']) for b in valid)),
        'burstSpacingBytes': dict(sorted(gaps.items())),
        'continuousAc3Segment': len(candidates) > 1 and len(valid) == len(candidates) and set(gaps) == {6144},
        'scope': 'Capture integrity only. No acoustic, Android transport or source 5.1 approval.',
    }

if __name__ == '__main__':
    parser = argparse.ArgumentParser()
    parser.add_argument('capture', type=Path)
    parser.add_argument('--output', type=Path)
    args = parser.parse_args()
    if args.capture.stat().st_size > iec.MAX_BYTES:
        parser.error('Capture exceeds 16 MiB')
    report = inspect(args.capture.read_bytes())
    serialized = json.dumps(report, indent=2)
    if args.output:
        args.output.write_text(serialized + '\n', encoding='utf-8')
    print(serialized)
