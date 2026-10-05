"""Offline DSP checks; never opens an audio output device."""
import array
import json
import math
from pathlib import Path
import re
import struct
import subprocess

root = Path(__file__).resolve().parent
mpv = root / 'mpv-portatil' / 'mpv.exe'
eq = (root / 'equalizador-lfe-filtro-teste.txt').read_text().strip()
eq = eq.split('],lavcac3enc=', 1)[0] + ']'
base = re.sub(r',(?:pan@lfeheadroom|equalizer@lfe\d+)=[^,\]]+', '', eq)
rate = 48000
frames = rate * 5
samples = array.array('f')
for i in range(frames):
    # Equal tones on every channel, then a quiet interval and an impulse.
    if i < rate * 3:
        hz = (30, 40, 60)[i // rate]
        value = round(2000 * math.sin(2 * math.pi * hz * i / rate))
    else:
        value = 8000 if i == rate * 4 else 0
    samples.extend([value / 32768.0] * 6)
data = samples.tobytes()
fmt = struct.pack('<HHIIHHHHI', 65534, 6, rate, rate * 24, 24, 32, 22, 32, 0x3f)
fmt += bytes.fromhex('0300000000001000800000aa00389b71')
wave = b'WAVEfmt ' + struct.pack('<I', len(fmt)) + fmt + b'data' + struct.pack('<I', len(data)) + data
input_file = root / 'equalizador-lfe-sinal-teste.wav'
input_file.write_bytes(b'RIFF' + struct.pack('<I', len(wave)) + wave)
outputs = []
for name, af in [('original', base), ('equalizado', eq)]:
    output = root / f'equalizador-lfe-{name}.raw'
    log = root / f'equalizador-lfe-{name}.log'
    result = subprocess.run([
        str(mpv), '--no-config', '--load-scripts=no', '--no-video', '--idle=no', '--keep-open=no', '--ao=pcm',
        '--ao-pcm-waveheader=no', f'--ao-pcm-file={output}', '--audio-format=float',
        '--audio-channels=5.1', f'--af={af}', '--no-terminal', f'--log-file={log}', str(input_file)
    ], timeout=30)
    assert result.returncode == 0, log
    assert '48000Hz 5.1 6ch float' in log.read_text(errors='replace'), 'Unexpected PCM output format'
    values = array.array('f'); values.frombytes(output.read_bytes()); outputs.append(values)
original, equalized = outputs
assert len(original) == len(equalized), 'EQ changed the stream length'
other_errors = {}
for channel in (0, 1, 2, 4, 5):
    error = max(abs(a - b) for a, b in zip(original[channel::6], equalized[channel::6]))
    other_errors[str(channel)] = error
    assert error < 1e-7, (channel, error)
gains = {}
for segment, hz in enumerate((30, 40, 60)):
    start = segment * rate + rate // 2
    stop = segment * rate + rate * 9 // 10
    a = original[start * 6 + 3:stop * 6:6]
    b = equalized[start * 6 + 3:stop * 6:6]
    db = 10 * math.log10(sum(x*x for x in b) / sum(x*x for x in a))
    gains[str(hz)] = round(db, 3)
assert gains['30'] > gains['60'] + 3 and gains['40'] > gains['60'] + 3
first_impulse = []
for values in outputs:
    first_impulse.append(next(i for i in range(rate*4-100,rate*4+100) if abs(values[i*6+3]) > 1e-7))
assert first_impulse[0] == first_impulse[1], 'EQ added an onset delay'
for values in outputs:
    cen_peak = max(range(rate*4-100,rate*4+100),key=lambda i: abs(values[i*6+2]))
    fl_peak = max(range(rate*4+3200,rate*4+3500),key=lambda i: abs(values[i*6]))
    assert fl_peak - cen_peak == 3360, (fl_peak,cen_peak)
summary = {'other_channel_max_error':other_errors,'lfe_gain_including_6db_headroom':gains,
           'lfe_impulse_onset_samples':first_impulse,'extra_fixed_delay_samples':0,
           'front_surround_correction_ms':70,'output_frames':len(original)//6}
(root / 'equalizador-lfe-verificacao.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary,indent=2))
