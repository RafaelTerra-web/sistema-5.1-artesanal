"""IEC61937 decode/gain/map tests using synthetic files and mpv's PCM file AO.

Never opens any audio endpoint. The expected six decoded channels are compared
to the optical runner's eight physical slots, including independent FC/LFE and
both provisional rear pairs. This is not an acoustic or TV passthrough test.
"""
import argparse
import importlib.util
import json
from pathlib import Path
import struct
import subprocess

import numpy as np

ROOT = Path(__file__).resolve().parents[2]
parser = argparse.ArgumentParser()
parser.add_argument('--mpv', required=True, type=Path)
args = parser.parse_args()
spec = importlib.util.spec_from_file_location('iec', ROOT / 'android-a34/scripts/analyze-iec61937.py')
iec = importlib.util.module_from_spec(spec)
spec.loader.exec_module(iec)
source = ROOT / 'android-a34/app/src/debug/assets/fixtures/tones-51.ac3'
out = ROOT / 'android-a34/artifacts/continuous-spdif-file-tests'
out.mkdir(parents=True, exist_ok=True)
ac3 = source.read_bytes()
bursts, offset = [], 0
while offset < len(ac3):
    header = iec.ac3_header(ac3[offset:])
    assert header['sampleRate'] == 48000 and header['channels'] == 6
    payload = ac3[offset:offset + header['frameBytes']]
    assert len(payload) == header['frameBytes']
    burst = b'\x72\xf8\x1f\x4e' + struct.pack('<HH', 1, len(payload)*8) + iec.swap16(payload)
    bursts.append(burst.ljust(6144, b'\0'))
    offset += len(payload)
carrier = out / 'synthetic-native6-carrier.pcm'
carrier.write_bytes(b''.join(bursts))


def render(name, source_path, channels, gain, carrier_input=False, graph=None):
    raw, log = out / (name + '.f32le'), out / (name + '.log')
    command = [str(args.mpv), '--no-config', '--no-video', '--no-terminal', '--load-scripts=no',
               '--ao=pcm', '--ao-pcm-waveheader=no', '--audio-format=float',
               '--audio-channels=' + channels, '--ao-pcm-file=' + str(raw),
               '--audio-spdif=', '--ad-lavc-downmix=no', '--ad-lavc-o=err_detect=crccheck+explode',
               '--volume=' + str(100 * gain**(1/3)), '--log-file=' + str(log)]
    if carrier_input:
        command += ['--demuxer=lavf', '--demuxer-lavf-format=spdif',
                    '--demuxer-lavf-analyzeduration=0.1', '--demuxer-lavf-probesize=8192']
    if graph:
        command += ['--af=lavfi=[' + graph + ']']
    command += [str(source_path)]
    done = subprocess.run(command, capture_output=True, timeout=30,
                          creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
    if done.returncode:
        raise RuntimeError(done.stderr.decode(errors='replace'))
    assert not np.any(~np.isfinite(np.fromfile(raw, dtype='<f4')))
    text = log.read_text(encoding='utf-8', errors='replace')
    assert '(ac3 6ch 48000 Hz' in text
    return np.fromfile(raw, dtype='<f4').reshape(-1, 8 if channels == '7.1' else 6)


native = render('reference-native6', source, '5.1', 1)
definitions = [ROOT / 'android-a34/scripts/windows-cm6206/CoreAudioFormatProbe.cs',
               ROOT / 'scripts/optical-tests/WindowsSpdifCapture.cs',
               ROOT / 'scripts/optical-tests/WindowsSpdifRelay.cs',
               ROOT / 'scripts/optical-tests/WindowsSpdifContinuousRelay.cs']
quoted = ','.join("'" + str(p).replace("'", "''") + "'" for p in definitions)
script = 'Add-Type -Path @(' + quoted + ');[Sistema51.Cm6206.WindowsSpdifRelay]::DefaultNativeDspFilter()'
actual_filter = subprocess.run(['powershell.exe', '-STA', '-NoProfile', '-Command', script],
                               check=True, capture_output=True, timeout=30,
                               creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0)).stdout.decode().strip()
assert actual_filter.startswith('lavfi=[') and actual_filter.endswith(']')
graph = actual_filter[len('lavfi=['):-1]
delays = [3686, 3686, 278, 278, 3408, 3408]
expected_delayed = np.zeros((len(native) + max(delays), 6), dtype='<f4')
for ch, delay in enumerate(delays):
    expected_delayed[delay:delay+len(native), ch] = native[:, ch]
checks = []
for gain in (1, .1, 0):
    mapped = render('carrier-gain-' + str(gain), carrier, '7.1', gain, True, graph)
    expected = expected_delayed[:, [0, 1, 2, 3, 4, 5, 4, 5]] * gain
    assert mapped.shape == expected.shape
    error = float(np.abs(mapped-expected).max())
    assert error < 1e-6, error
    assert np.array_equal(mapped[:, 4], mapped[:, 6]) and np.array_equal(mapped[:, 5], mapped[:, 7])
    for ch in range(6) if gain else ():
        # Every original channel remains separate; a slot swap is detectable.
        assert float(np.abs(mapped[:, ch]).max()) > 1e-4 * gain
    if gain == 0:
        assert not np.any(mapped), 'Zero gain must preserve decoded duration while outputting silence.'
    checks.append({'gainLinear': gain, 'frames': len(mapped), 'maximumAbsoluteError': error})

# Independent impulses verify the actual default graph's sample offsets and
# that both USB rear copies receive one delay, not two consecutive delays.
impulses = np.zeros((48000, 6), dtype='<f4')
positions = [1024, 2048, 3072, 4096, 5120, 6144]
for ch, position in enumerate(positions):
    impulses[position, ch] = .25
data = impulses.tobytes()
fmt = struct.pack('<HHIIHHHHI', 0xfffe, 6, 48000, 48000*24, 24, 32, 22, 32, 0x3f)
fmt += bytes.fromhex('0300000000001000800000aa00389b71')
impulse_path = out / 'isolated-native6-impulses.wav'
impulse_path.write_bytes(b'RIFF'+struct.pack('<I', 20+len(fmt)+len(data))+b'WAVEfmt '
                        +struct.pack('<I', len(fmt))+fmt+b'data'+struct.pack('<I',len(data))+data)
raw = out / 'isolated-delayed8.f32le'
command = [str(args.mpv), '--no-config', '--no-video', '--no-terminal', '--load-scripts=no',
           '--ao=pcm', '--ao-pcm-waveheader=no', '--audio-format=float', '--audio-channels=7.1',
           '--ao-pcm-file='+str(raw), '--af=lavfi=['+graph+']', str(impulse_path)]
subprocess.run(command, check=True, capture_output=True, timeout=30,
               creationflags=getattr(subprocess, 'CREATE_NO_WINDOW', 0))
delayed_impulses = np.fromfile(raw, dtype='<f4').reshape(-1, 8)
mapping = [0, 1, 2, 3, 4, 5, 4, 5]
expected_positions = [positions[ch]+delays[ch] for ch in mapping]
observed_positions = np.argmax(np.abs(delayed_impulses), axis=0).tolist()
assert observed_positions == expected_positions
assert np.count_nonzero(delayed_impulses) == 8
assert delayed_impulses[positions[3]+278, 3] == .25, 'LFE must have the new 278-sample delay.'
report = {'ok': True, 'fileOutputOnly': True, 'syntheticCarrierBurstCount': len(bursts),
          'channels': 'FL FR FC LFE SL SR SL SR', 'delaySamplesLogical6': delays,
          'impulsePositionsUsb8': observed_positions, 'checks': checks,
          'scope': 'Native AC-3 decode, linear gain, exact delays and physical slot mapping in files; no TV, USB or DAC validation.'}
(out / 'native-optical-file-report.json').write_text(json.dumps(report, indent=2))
print(json.dumps(report, indent=2))
