"""Verify actual configured channel delays against a zero-delay file reference.

No output device is opened: mpv writes only float PCM files. Crossovers, bass
redirect and master remain identical in both renders; only adelay is removed.
"""
import argparse
import json
from pathlib import Path
import re
import struct
import subprocess
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('--mpv', type=Path, required=True)
p.add_argument('--config', type=Path, required=True)
args = p.parse_args()
text = args.config.read_text(encoding='utf-8-sig')
graph = next(x[len('af=lavfi=['):-1] for x in text.splitlines() if x.startswith('af=lavfi=['))
match = re.search(r'adelay=(\d+S(?:\|\d+S){5}),', graph)
if not match: raise ValueError('Six explicit sample delays missing from active graph')
delays = [int(x[:-1]) for x in match.group(1).split('|')]
expected = [3686,3686,278,278,3408,3408]
if delays != expected: raise ValueError('Configured delays differ from the user request')
zero = graph[:match.start()] + graph[match.end():]
volume = next((x.split('=',1)[1] for x in text.splitlines() if x.startswith('volume=')), '100')
out = args.config.parent/'delay-validation'
out.mkdir(parents=True, exist_ok=True)
samples = np.zeros((48000,6), dtype='<f4')
samples[4096,:] = .1
fmt = struct.pack('<HHIIHHHHI',0xfffe,6,48000,48000*24,24,32,22,32,0x3f)
fmt += bytes.fromhex('0300000000001000800000aa00389b71')
data = samples.tobytes()
body = b'WAVEfmt '+struct.pack('<I',len(fmt))+fmt+b'data'+struct.pack('<I',len(data))+data
source = out/'impulses.wav'
source.write_bytes(b'RIFF'+struct.pack('<I',len(body))+body)

def render(name, af):
    target = out/(name+'.f32le')
    subprocess.run([str(args.mpv),'--no-config','--no-video','--no-terminal','--ao=pcm',
        '--ao-pcm-waveheader=no','--audio-format=float','--audio-channels=7.1',
        '--volume='+volume,'--af=lavfi=['+af+']','--ao-pcm-file='+str(target),str(source)],
        capture_output=True, check=True, timeout=30)
    return np.fromfile(target,dtype='<f4').reshape(-1,8)

baseline, actual = render('zero-delay',zero), render('configured-delay',graph)
physical_delays = delays+[delays[4],delays[5]]
if 'pan=7.1|c0=c0|c1=c1|c2=c3|c3=c2' in graph:
    physical_delays[2],physical_delays[3] = physical_delays[3],physical_delays[2]
results=[]
for c, delay in enumerate(physical_delays):
    assert np.isfinite(actual[:,c]).all()
    assert np.max(np.abs(actual[:delay,c]),initial=0)==0
    length=min(len(baseline),len(actual)-delay)
    error=float(np.max(np.abs(actual[delay:delay+length,c]-baseline[:length,c])))
    if error>2e-7: raise AssertionError(f'USB slot {c}: delay mismatch, error={error}')
    observed=int(np.argmax(abs(actual[:,c]))-np.argmax(abs(baseline[:,c])))
    if observed!=delay: raise AssertionError(f'USB slot {c}: expected {delay}, observed {observed}')
    results.append({'usbSlot':c,'samples':delay,'observedSamples':observed,
                    'effectiveMs':delay/48,'peakShiftError':error})
report={'ok':True,'fileOutputOnly':True,'requestedMs':[76.8,76.8,5.8,5.8,71,71],
        'configuredSamples':delays,'channels':results}
(out/'report.json').write_text(json.dumps(report,indent=2),encoding='utf-8')
print(json.dumps(report,indent=2))
