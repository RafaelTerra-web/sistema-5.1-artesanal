"""Private real-device control for the optical integration campaign; never prints the token."""
import argparse, json, sys
from pathlib import Path
import xml.etree.ElementTree as ET
from importlib.util import spec_from_file_location, module_from_spec
spec=spec_from_file_location('lan',Path(__file__).with_name('test-lan-control.py'))
lan=module_from_spec(spec);spec.loader.exec_module(lan)
p=argparse.ArgumentParser()
p.add_argument('action',choices=['state','mute','start','stop','audition','restore'])
p.add_argument('--url',default='http://192.168.1.101:8051')
p.add_argument('--prefs',type=Path,default=Path(__file__).parent.parent/'artifacts/remote-2026-10-09/connection-private.xml')
p.add_argument('--output',type=Path,default=Path(__file__).parent.parent/'artifacts/live-2026-10-09')
a=p.parse_args();token=ET.parse(a.prefs).find("string[@name='token']").text
code,state=lan.request(a.url+'/api/state',token)
if code!=200:raise RuntimeError('State unavailable: '+str(code))
a.output.mkdir(parents=True,exist_ok=True)
if a.action=='mute':
    backup=a.output/'profile-before-live.json'
    if not backup.exists():backup.write_text(json.dumps(state['profile'],indent=2),encoding='utf-8')
    body={'session':state['controlSession'],'revision':state['profileRevision'],'changes':{'muted':True}}
    code,state=lan.request(a.url+'/api/profile',token,body)
elif a.action in ('start','stop'):
    code,state=lan.request(a.url+'/api/'+a.action,token,{'session':state['controlSession'],'revision':state['profileRevision'],'confirmPlayback':True,'route':'optical'})
elif a.action=='audition':
    if not state.get('running') or state.get('sourceChannels') not in (1,2,6):
        raise RuntimeError('Validated running decoded source required before audition')
    body={'session':state['controlSession'],'revision':state['profileRevision'],'changes':{'masterGain':0.01,'muted':False}}
    code,state=lan.request(a.url+'/api/profile',token,body)
elif a.action=='restore':
    backup=json.loads((a.output/'profile-before-live.json').read_text(encoding='utf-8'))
    body={'session':state['controlSession'],'revision':state['profileRevision'],'changes':{'masterGain':backup['masterGain'],'muted':backup['muted']}}
    code,state=lan.request(a.url+'/api/profile',token,body)
if code not in (200,202):raise RuntimeError('Control rejected: '+str(code)+' '+str(state))
(a.output/(a.action+'-state.json')).write_text(json.dumps(state,indent=2),encoding='utf-8')
print(json.dumps({k:state.get(k) for k in ['state','title','detail','running','starting','stopping','sourceCodec','sourceChannels','upmixActive','runtimeMuted','decodedPeaks','clockCorrectionPpm','resamplingRatio','runtimeError','capturedFrames','reproducedFrames','underruns','clippingSamples','peaks','nativeUsb','profileRevision']},indent=2))
print('Muted:',state.get('profile',{}).get('muted'),'Master:',state.get('profile',{}).get('masterGain'))
