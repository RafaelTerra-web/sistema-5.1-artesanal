"""Signal checks for the generated PCM graph; file output only, never speakers."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import re
import numpy as np

p = argparse.ArgumentParser()
p.add_argument('--mpv', required=True, type=Path)
p.add_argument('--config', required=True, type=Path)
args = p.parse_args()
graph = next(line[len('af=lavfi=['):-1] for line in args.config.read_text().splitlines()
             if line.startswith('af=lavfi=['))
configured_volume=float(next((line.split('=',1)[1] for line in args.config.read_text().splitlines() if line.startswith('volume=')), '100'))
master = float(re.search(r'volume=([0-9.]+):precision=double', graph).group(1)) * (configured_volume/100)**3
assert 0 < master <= 1
stereo=graph.startswith('pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1')
center_trim=float(re.search(r'pan=5\.1\|c0=c0\|c1=c1\|c2=([0-9.]+)\*c2',graph).group(1))
swapped='pan=7.1|c0=c0|c1=c1|c2=c3|c3=c2' in graph
out = args.config.parent / 'file-validation'
out.mkdir(parents=True, exist_ok=True)
rate = 48000
fmt = struct.pack('<HHIIHHHHI', 0xfffe, 6, rate, rate*24, 24, 32, 22, 32, 0x3f)
fmt += bytes.fromhex('0300000000001000800000aa00389b71')

def render(name, channels, frequency):
    x = np.zeros((rate, 6), dtype='<f4')
    tone = .5*np.sin(2*np.pi*frequency*np.arange(rate)/rate)
    x[:, channels] = tone[:, None]
    data = x.tobytes()
    wav, raw = out/(name+'.wav'), out/(name+'.f32le')
    wav.write_bytes(b'RIFF'+struct.pack('<I', 20+len(fmt)+len(data))+b'WAVEfmt '
                    +struct.pack('<I',len(fmt))+fmt+b'data'+struct.pack('<I',len(data))+data)
    command = [str(args.mpv), '--no-config', '--no-video', '--no-terminal', '--ao=pcm',
               '--ao-pcm-waveheader=no', '--audio-format=float', '--audio-channels=7.1',
               '--volume='+str(configured_volume),'--ao-pcm-file='+str(raw), '--af=lavfi=['+graph+']', str(wav)]
    result = subprocess.run(command, capture_output=True, timeout=20)
    if result.returncode:
        raise RuntimeError(result.stderr.decode(errors='replace'))
    y = np.fromfile(raw,dtype='<f4').reshape(-1,8)
    if swapped:y=y[:,[0,1,3,2,4,5,6,7]]
    assert len(y)>=rate and np.isfinite(y).all() and np.abs(y).max()<1
    assert np.array_equal(y[:,4],y[:,6]) and np.array_equal(y[:,5],y[:,7])
    gain = np.sqrt(np.mean(y[rate//2:rate].astype('float64')**2,axis=0))/(.5/np.sqrt(2))
    return gain.tolist()

bass=render('front40',[0],40)
assert bass[0]<.04*master and bass[3]>.15*master
if not stereo: assert max(bass[1:3]+bass[4:])==0
if not stereo and 'c3=c0+c1+0.5*c2+c3+c4' in graph:
    center_bass=render('center40',[2],40)
    assert abs(center_bass[3]/bass[3]-.5)<1e-5, 'Center bass must enter LFE at half the frontal send, once.'
high=render('front1000',[0],1000)
assert high[0]>.999*master and high[3]<(.00004 if stereo else .00002)*master
center=render('center1000',[2],1000)
if stereo: assert max(center)==0
else: assert .999*center_trim*master<center[2]<1.001*center_trim*master
rear=render('rear1000',[4],1000)
if stereo:
    assert max(rear)==0
    mono=render('mono1000',[0,1],1000)
    assert .999*center_trim*master<mono[2]<1.001*center_trim*master and mono[4]>.499*master
else: assert rear[4]>.999*master and rear[6]>.999*master
summed=render('all40',list(range(6)),40)
assert summed[3]<1
report={'ok':True,'fileOutputOnly':True,'inputMode':'Stereo' if stereo else 'Native','centerLfeSwapped':swapped,'front40':bass,'front1000':high,
        'center1000':center,'rear1000':rear,'all40':summed,
        'scope':'Filter/headroom/slot checks in files; no APO execution, DAC or amplifier validation.'}
(out/'report.json').write_text(json.dumps(report,indent=2))
print(json.dumps(report,indent=2))
