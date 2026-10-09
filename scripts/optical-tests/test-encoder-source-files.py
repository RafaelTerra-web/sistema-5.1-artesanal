"""File-only AC-3 source-mode checks; no WASAPI/HDMI/USB audio is opened."""
import argparse
import json
from pathlib import Path
import struct
import subprocess
import numpy as np

ROOT=Path(__file__).resolve().parents[2]
p=argparse.ArgumentParser();p.add_argument('--mpv',required=True,type=Path);args=p.parse_args()
out=ROOT/'android-a34/artifacts/ac3-encoder-source-file-tests';out.mkdir(parents=True,exist_ok=True)
definitions=[ROOT/'configuracao-pc/StereoUpmix.cs',ROOT/'configuracao-pc/RelayLoopback.cs',
             ROOT/'configuracao-pc/RelayLoopbackLowLatency.cs',ROOT/'scripts/optical-tests/WindowsAc3Encoder.cs']
quoted=','.join("'"+str(f).replace("'","''")+"'" for f in definitions)
script='Add-Type -Path @('+quoted+');$id="{0.0.0.00000000}.{d83adb3c-7863-4a73-bfd8-b01de1fb9842}";'
script+='@{Auto=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($id,640,"Auto");Native=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($id,640,"Native");Stereo=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($id,640,"Stereo")}|ConvertTo-Json'
done=subprocess.run(['powershell.exe','-STA','-NoProfile','-Command',script],check=True,capture_output=True,timeout=30,
                    creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
configs=json.loads(done.stdout.decode())
assert configs['Auto']==configs['Native']
rate=48000;t=np.arange(rate*2)/rate
x=np.column_stack((.2*np.sin(2*np.pi*250*t)+.05*np.sin(2*np.pi*60*t),
                   .2*np.sin(2*np.pi*500*t)+.05*np.sin(2*np.pi*90*t),
                   .2*np.sin(2*np.pi*900*t),.2*np.sin(2*np.pi*60*t),
                   .2*np.sin(2*np.pi*1200*t),.2*np.sin(2*np.pi*1500*t))).astype('<f4')
fmt=struct.pack('<HHIIHHHHI',0xfffe,6,rate,rate*24,24,32,22,32,0x3f)+bytes.fromhex('0300000000001000800000aa00389b71')
data=x.tobytes();source=out/'source-six-distinct.wav'
source.write_bytes(b'RIFF'+struct.pack('<I',20+len(fmt)+len(data))+b'WAVEfmt '+struct.pack('<I',len(fmt))+fmt+b'data'+struct.pack('<I',len(data))+data)

def run(command):
    result=subprocess.run(command,check=True,capture_output=True,timeout=30,
                          creationflags=getattr(subprocess,'CREATE_NO_WINDOW',0))
    return result

def encode_decode(mode):
    af=next(line[3:] for line in configs[mode].splitlines() if line.startswith('af='))
    carrier=out/(mode+'.carrier');pcm=out/(mode+'.f32le');log=out/(mode+'.decode.log')
    run([str(args.mpv),'--no-config','--no-video','--no-terminal','--load-scripts=no',
         '--ao=pcm','--ao-pcm-waveheader=no','--ao-pcm-file='+str(carrier),'--audio-channels=5.1',
         '--volume=100','--af='+af,str(source)])
    run([str(args.mpv),'--no-config','--no-video','--no-terminal','--load-scripts=no','--ao=pcm',
         '--ao-pcm-waveheader=no','--ao-pcm-file='+str(pcm),'--audio-format=float','--audio-channels=5.1',
         '--demuxer=lavf','--demuxer-lavf-format=spdif','--ad-lavc-downmix=no',
         '--ad-lavc-o=err_detect=crccheck+explode','--log-file='+str(log),str(carrier)])
    text=log.read_text(encoding='utf-8',errors='replace')
    assert '(ac3 6ch 48000 Hz' in text and 'CRC mismatch' not in text
    return np.fromfile(pcm,dtype='<f4').reshape(-1,6)

native=encode_decode('Native');auto=encode_decode('Auto');stereo=encode_decode('Stereo')
assert np.array_equal(auto,native),'Auto must not change quiet/native six-channel signals.'
# AC-3 is lossy. Compare a central steady segment using correlation rather than
# claiming exact PCM equality; independently injected native center/rears must
# survive Native and disappear under the explicitly confirmed Stereo override.
segment=slice(24000,48000)
def align_codec_priming(decoded):
    reference=x[segment,0].astype('float64')
    scores=[]
    for offset in range(1537):
        received=decoded[segment.start+offset:segment.stop+offset,0].astype('float64')
        scores.append(float(np.dot(reference,received)/(np.linalg.norm(reference)*np.linalg.norm(received))))
    offset=int(np.argmax(scores))
    assert scores[offset]>.999
    return decoded[offset:offset+len(x)],offset
native_aligned,native_priming=align_codec_priming(native)
stereo_aligned,stereo_priming=align_codec_priming(stereo)
native_corr=[float(np.corrcoef(native_aligned[segment,ch],x[segment,ch])[0,1]) for ch in range(6)]
assert min(native_corr)>.999,native_corr
expected_stereo=np.column_stack((x[:,0],x[:,1],.5*x[:,0]+.5*x[:,1],.25*x[:,0]+.25*x[:,1],.5*x[:,0],.5*x[:,1]))
stereo_corr=[float(np.corrcoef(stereo_aligned[segment,ch],expected_stereo[segment,ch])[0,1]) for ch in (0,1,2,4,5)]
assert min(stereo_corr)>.999,stereo_corr
assert abs(float(np.corrcoef(stereo_aligned[segment,2],x[segment,2])[0,1]))<.01
assert abs(float(np.corrcoef(stereo_aligned[segment,4],x[segment,4])[0,1]))<.01
report={'ok':True,'fileOutputOnly':True,'autoEqualsNative':True,'decodedAc3Channels':6,
        'nativePerChannelCorrelation':native_corr,'stereoSatelliteCorrelation':stereo_corr,
        'receiverDelayAppliedInEncoder':False,'framesNative':len(native),'framesStereo':len(stereo),
        'observedCodecPrimingFramesNative':native_priming,'observedCodecPrimingFramesStereo':stereo_priming,
        'scope':'Synthetic source-mode encoding and CRC-checked decoding in files; no original bitstream preservation or hardware validation.'}
(out/'report.json').write_text(json.dumps(report,indent=2));print(json.dumps(report,indent=2))
