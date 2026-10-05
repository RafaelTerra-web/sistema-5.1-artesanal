"""Offline surround-to-LFE routing verification; never opens an audio device."""
import array
import json
import math
from pathlib import Path
import re
import struct
import subprocess

root = Path(__file__).resolve().parent
af = (root / 'surround-graves-filtro-teste.txt').read_text().strip()
base = re.search(r'^af=(.*)$', (root / 'mpv-sistema-dolby.conf').read_text(), re.M).group(1).strip()
rate = 48000
# Separate source channels/frequencies reveal incorrect remapping or missing bass.
segments = [(4,30),(5,30),(4,80),(5,300),(3,50)]
samples = array.array('f')
for segment, (channel,hz) in enumerate(segments):
    for i in range(rate):
        frame = [0.0]*6
        frame[channel] = 0.08*math.sin(2*math.pi*hz*i/rate)
        # Fronts and center must pass through the crossover unchanged.
        frame[0] += 0.01*math.sin(2*math.pi*230*i/rate)
        frame[1] += 0.01*math.sin(2*math.pi*410*i/rate)
        frame[2] += 0.01*math.sin(2*math.pi*530*i/rate)
        samples.extend(frame)
for i in range(rate):
    frame = [0.0]*6
    if i == rate//2: frame = [0.2,0.2,0.2,0.2,0.0,0.0]
    samples.extend(frame)
data = samples.tobytes()
fmt = struct.pack('<HHIIHHHHI',65534,6,rate,rate*24,24,32,22,32,0x3f)+bytes.fromhex('0300000000001000800000aa00389b71')
wave = b'WAVEfmt '+struct.pack('<I',len(fmt))+fmt+b'data'+struct.pack('<I',len(data))+data
source = root / 'surround-graves-sinal-teste.wav'
source.write_bytes(b'RIFF'+struct.pack('<I',len(wave))+wave)
outputs = []
for name, filters in [('referencia',base),('crossover',af)]:
    filters = filters.rsplit('],lavcac3enc=',1)[0]+']'
    output = root / ('surround-graves-'+name+'.raw')
    log = root / ('surround-graves-'+name+'.log')
    run = subprocess.run([str(root/'mpv-portatil'/'mpv.exe'),'--no-config','--load-scripts=no',
        '--no-video','--idle=no','--keep-open=no','--ao=pcm','--ao-pcm-waveheader=no',
        '--audio-format=float','--audio-channels=5.1',f'--ao-pcm-file={output}',
        f'--af={filters}','--no-terminal',f'--log-file={log}',str(source)],timeout=30)
    assert run.returncode == 0, log
    assert '48000Hz 5.1 6ch float' in log.read_text(errors='replace'), log
    values=array.array('f'); values.frombytes(output.read_bytes()); outputs.append(values)
reference, managed=outputs
assert len(reference)==len(managed), (len(reference),len(managed))
errors={}
for channel in (0,1,2):
    errors[str(channel)]=max(abs(a-b) for a,b in zip(reference[channel::6],managed[channel::6]))
    assert errors[str(channel)]<1e-7,errors
def rms(values,segment,channel):
    start=(segment*rate+rate//2)*6+channel
    stop=(segment*rate+rate*9//10)*6
    part=values[start:stop:6]
    return math.sqrt(sum(x*x for x in part)/len(part))
measurements=[]
for segment,(channel,hz) in enumerate(segments):
    input_rms=rms(reference,segment,channel)
    gains={str(ch):20*math.log10(max(rms(managed,segment,ch),1e-20)/input_rms) for ch in (3,4,5)}
    measurements.append({'input_channel':channel,'hz':hz,'output_db':gains})
    if channel in (4,5):
        other=5 if channel==4 else 4
        assert gains[str(other)] < -100, 'Surround channels leaked into each other'
        if hz==30:
            assert gains[str(channel)] < -30, 'Low bass not removed from surround'
            assert -14 < gains['3'] < -10, 'Surround bass missing from LFE'
        if hz==80:
            assert -6.2 < gains[str(channel)] < -5.8, 'Crossover frequency incorrect'
        if hz==300:
            assert -0.2 < gains[str(channel)] < 0.1, 'Treble removed from surround'
            assert gains['3'] < -50, 'High frequencies leaked into LFE'
    else:
        assert -6.2 < gains['3'] < -5.9, 'Original LFE not preserved with headroom'
center=5*rate+rate//2
peaks=[]
for channel in (2,0):
    peaks.append(max(range(center-100,center+3500),key=lambda i:abs(managed[i*6+channel])))
assert peaks[1]-peaks[0]==3360, '70 ms correction changed'
# Verify the complete chain still produces a six-channel Dolby Digital stream.
spdif=root/'surround-graves-teste.spdif'
run=subprocess.run([str(root/'mpv-portatil'/'mpv.exe'),'--no-config','--no-video','--idle=no',
    '--keep-open=no','--load-scripts=no','--ao=pcm','--ao-pcm-waveheader=no','--audio-channels=5.1',
    f'--ao-pcm-file={spdif}',f'--af={af}','--no-terminal',
    f'--log-file={root / "surround-graves-ac3.log"}',str(source)],timeout=30)
assert run.returncode==0 and spdif.stat().st_size>0
summary={'front_center_max_error':errors,'measurements':measurements,'correction_ms':70,
         'output_frames':len(managed)//6,'ac3_spdif_bytes':spdif.stat().st_size}
(root/'surround-graves-verificacao.json').write_text(json.dumps(summary,indent=2))
print(json.dumps(summary,indent=2))
