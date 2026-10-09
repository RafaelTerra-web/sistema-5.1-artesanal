"""Prepare a private direct-USB 7.1 pair comparison. Does not play audio."""
from pathlib import Path
import argparse
import hashlib
import json
import struct
import wave
import numpy as np


def main():
    parser = argparse.ArgumentParser(description=__doc__)
    parser.add_argument('--artifact-directory', required=True, type=Path)
    parser.add_argument('--all-channels', action='store_true', help='Also include fronts, center and a very quiet LFE tone')
    args = parser.parse_args()
    base = args.artifact_directory.resolve()
    if 'android-a34' not in base.parts or 'artifacts' not in base.parts:
        parser.error('Use the private bench artifacts directory')
    manifest = json.loads((base / 'spoken-channels-ready-02-manifest.json').read_text(encoding='utf-8-sig'))
    speech = []
    for index in range(6):
        path = base / 'spoken-channels-ready-02-speech' / f'{index:02}-speech.wav'
        expected = manifest['synthesisFiles'][index]['sha256']
        if hashlib.sha256(path.read_bytes()).hexdigest() != expected:
            raise ValueError('Speech source hash changed')
        with wave.open(str(path), 'rb') as source:
            assert source.getnchannels() == 1 and source.getframerate() == 48000 and source.getsampwidth() == 2
            data = np.frombuffer(source.readframes(source.getnframes()), dtype='<i2').astype(np.float64)
        data = np.rint(data * 8192 / np.max(np.abs(data))).astype('<i2')
        speech.append(data)
    parts = [np.zeros((144000, 8), dtype='<i2')]
    cursor = 144000
    timeline = []
    if args.all_channels:
        for slot, label in ((0, 'FL / frontal esquerda'), (1, 'FR / frontal direita'), (2, 'FC / central')):
            voice = speech[slot]
            block = np.zeros((len(voice) + 48000, 8), dtype='<i2')
            # Lower the center because its amplifier's relative gain is uncalibrated.
            block[:len(voice), slot] = np.rint(voice.astype(np.float64) * (.2 if slot == 2 else 1)).astype('<i2')
            timeline.append({'usbSlotIndex': slot, 'position': label, 'startSeconds': cursor/48000,
                             'speechEndSeconds': (cursor+len(voice))/48000})
            cursor += len(block)
            parts.append(block)
        voice = speech[3]
        block = np.zeros((len(voice) + 96000, 8), dtype='<i2')
        block[:len(voice), 0] = np.rint(voice.astype(np.float64)/np.sqrt(2)).astype('<i2')
        block[:len(voice), 1] = block[:len(voice), 0]
        start = len(voice) + 24000
        indices = np.arange(24000)
        ramp = np.minimum(1, np.minimum(indices/480, (23999-indices)/480))
        block[start:start+24000, 3] = np.rint(32768*.005*ramp*np.sin(2*np.pi*60*indices/48000)).astype('<i2')
        timeline.append({'usbSlotIndex':3,'position':'LFE / anúncio nas frontais e grave muito reduzido',
                         'startSeconds':cursor/48000,'toneStartSeconds':(cursor+start)/48000,
                         'tonePeak':.005})
        cursor += len(block)
        parts.append(block)
    for slot, label, voice in ((4, 'BL / primeiro par esquerdo', speech[4]),
                              (5, 'BR / primeiro par direito', speech[5]),
                              (6, 'SL / segundo par esquerdo', speech[4]),
                              (7, 'SR / segundo par direito', speech[5])):
        block = np.zeros((len(voice) + 48000, 8), dtype='<i2')
        block[:len(voice), slot] = voice
        timeline.append({'usbSlotIndex': slot, 'position': label, 'startSeconds': cursor / 48000,
                         'speechEndSeconds': (cursor + len(voice)) / 48000})
        cursor += len(block)
        parts.append(block)
    parts.append(np.zeros((48000, 8), dtype='<i2'))
    samples = np.concatenate(parts)
    if not args.all_channels:
        assert np.count_nonzero(samples[:, :4]) == 0
    else:
        assert np.max(np.abs(samples[:,3].astype(np.int32))) <= 164
    payload = samples.tobytes()
    fmt = struct.pack('<HHIIHHHHI', 0xfffe, 8, 48000, 768000, 16, 16, 22, 16, 0x63f)
    fmt += bytes.fromhex('0100000000001000800000aa00389b71')
    body = b'WAVEfmt ' + struct.pack('<I', 40) + fmt + b'data' + struct.pack('<I', len(payload)) + payload
    out = base / ('spoken-all-map-7point1-05.wav' if args.all_channels else 'spoken-rear-map-7point1-04.wav')
    if out.exists():
        raise ValueError('Preserve existing fixture')
    out.write_bytes(b'RIFF' + struct.pack('<I', len(body)) + body)
    result = {'source': str(out), 'sha256': hashlib.sha256(out.read_bytes()).hexdigest(),
              'channels': 8, 'mask': '0x63F', 'order': ['FL','FR','FC','LFE','BL','BR','SL','SR'],
              'seconds': len(samples) / 48000, 'centerLfeFrontsZero': not args.all_channels,
              'lfePeak':.005 if args.all_channels else 0, 'centerPeak':.05 if args.all_channels else 0, 'peak': .25,
              'timeline': timeline, 'playbackPerformed': False}
    out.with_suffix('.json').write_text(json.dumps(result, indent=2), encoding='utf-8')
    print(json.dumps(result))


if __name__ == '__main__':
    main()
