"""Read-only, muted Fire TV transport soak. Never changes the profile or playback controls."""
import argparse, json, time
from pathlib import Path
import xml.etree.ElementTree as ET
from importlib.util import spec_from_file_location, module_from_spec

spec=spec_from_file_location('lan',Path(__file__).with_name('test-lan-control.py'))
lan=module_from_spec(spec);spec.loader.exec_module(lan)
p=argparse.ArgumentParser()
p.add_argument('--url',default='http://192.168.1.101:8051')
p.add_argument('--prefs',type=Path,default=Path(__file__).parent.parent/'artifacts/remote-2026-10-09/connection-private.xml')
p.add_argument('--output',type=Path,default=Path(__file__).parent.parent/'artifacts/live-2026-10-09/optical-soak.json')
p.add_argument('--seconds',type=float,default=20)
p.add_argument('--low-gain',action='store_true',help='Permit unmuted playback only when the local master gain is at most 1 percent.')
p.add_argument('--original-profile',action='store_true',help='Permit unmuted playback up to the pre-test saved master gain.')
p.add_argument('--observe',action='store_true',help='Observe the session already started by the user, without changing gain, mute or playback.')
a=p.parse_args()
if not 2<=a.seconds<=300: raise ValueError('Duration must be 2..300 seconds')
token=ET.parse(a.prefs).find("string[@name='token']").text
observations=[];started=time.monotonic();session=None
while True:
    code,s=lan.request(a.url.rstrip('/')+'/api/state',token)
    if code!=200: raise RuntimeError('State request failed: '+str(code))
    session=s.get('controlSession')
    if not s.get('running') or not s.get('sourceChannels'):
        raise RuntimeError('Optical transport is not receiving a decoded source: '+str({k:s.get(k) for k in ['state','detail','runtimeError']}))
    profile=s.get('profile',{})
    if a.observe:
        pass
    elif a.low_gain:
        if profile.get('muted') or s.get('runtimeMuted') or float(profile.get('masterGain',1))>0.010001:
            raise RuntimeError('Low-gain run requires unmuted signal and master gain at most 1%.')
    elif a.original_profile:
        saved=json.loads((a.output.parent/'profile-before-live.json').read_text(encoding='utf-8'))
        if profile.get('muted') or s.get('runtimeMuted') or float(profile.get('masterGain',1))>float(saved['masterGain'])+1e-6:
            raise RuntimeError('Run requires unmuted playback no louder than the pre-test saved master profile.')
    elif not profile.get('muted') or not s.get('runtimeMuted'):
        raise RuntimeError('Soak is restricted to a confirmed muted profile and USB gate.')
    usb=s.get('nativeUsb') or {}
    failures={k:usb.get(k) for k in ['errno','inputPacketErrors','outputPacketErrors','inputSequenceGaps','outputSequenceGaps','restorationError'] if usb.get(k) not in (0,None,'')}
    if failures or s.get('runtimeError'):
        raise RuntimeError('Transport reported an error: '+str(failures or s.get('runtimeError')))
    observations.append({k:s.get(k) for k in ['sourceCodec','sourceChannels','upmixActive','decodedPeaks','clockCorrectionPpm','resamplingRatio','capturedFrames','reproducedFrames','clippingSamples','underruns']}
        | {'inputBytes':usb.get('inputBytes'),'outputBytes':usb.get('outputBytes'),'outputQueuedFrames':usb.get('outputQueuedFrames'),
           'outputPriming':usb.get('outputPriming'),'gatedSilenceFrames':usb.get('gatedSilenceFrames'),'outputPackets':usb.get('outputPackets')})
    if time.monotonic()-started>=a.seconds: break
    time.sleep(.5)
channels={item['sourceChannels'] for item in observations}
if len(channels)!=1: raise RuntimeError('Source channel count changed during muted soak: '+str(sorted(channels)))
for key in ('capturedFrames','reproducedFrames','inputBytes','outputBytes','outputPackets','gatedSilenceFrames'):
    values=[item.get(key) for item in observations]
    if values[-1]<values[0]: raise RuntimeError('Counter moved backwards: '+key)
new_underruns=observations[-1]['underruns']-observations[0]['underruns']
if new_underruns: raise RuntimeError('New output underruns during observation: '+str(new_underruns))
if observations[-1]['clippingSamples']>observations[0]['clippingSamples']: raise RuntimeError('New clipped samples during observation')
report={'ok':True,'seconds':time.monotonic()-started,'samples':len(observations),'sourceChannels':next(iter(channels)),
    'sourceCodec':observations[-1]['sourceCodec'],'muted':None if a.observe else not (a.low_gain or a.original_profile),
    'maxMasterGain':0.01 if a.low_gain else (json.loads((a.output.parent/'profile-before-live.json').read_text(encoding='utf-8'))['masterGain'] if a.original_profile else None),
    'first':observations[0],'last':observations[-1],
    'outputQueueMin':min(x['outputQueuedFrames'] for x in observations),'outputQueueMax':max(x['outputQueuedFrames'] for x in observations),
    'maxUnderruns':max(x['underruns'] or 0 for x in observations),'newUnderruns':new_underruns,'maxClipping':max(x['clippingSamples'] or 0 for x in observations),
    'scope':'Read-only observation of the user-started session; no playback or profile commands.' if a.observe else 'Read-only telemetry soak. Playback remains at the selected mute or gain guard throughout.'}
a.output.parent.mkdir(parents=True,exist_ok=True);a.output.write_text(json.dumps(report,indent=2)+'\n',encoding='utf-8')
print(json.dumps(report,indent=2))
