"""Exercise the installed A34 over LAN. No USB capture/playback is started.

Uses a PRIVATE adb run-as preferences backup for the bearer token. Temporarily
reduces the stopped DSP master and restores the original profile in finally.
"""
import argparse
import json
from pathlib import Path
import urllib.error
import urllib.request
import xml.etree.ElementTree as ET

def request(url, token='', body=None, origin=None):
    headers={'Authorization': 'Bearer ' + token}
    if body is not None:
        headers['Content-Type']='application/json'
    if origin:
        headers['Origin']=origin
    req=urllib.request.Request(url, headers=headers, data=None if body is None else json.dumps(body).encode())
    try:
        response=urllib.request.urlopen(req, timeout=5)
    except urllib.error.HTTPError as error:
        response=error
    raw=response.read()
    return response.status, json.loads(raw) if response.headers.get_content_type()=='application/json' else raw

def run(base, private_prefs, output):
    token=ET.parse(private_prefs).find("string[@name='token']").text
    checks=[]
    def check(condition, name):
        if not condition:
            raise AssertionError(name)
        checks.append(name)
    check(request(base+'/')[0]==200, 'public panel')
    check(request(base+'/api/state')[0]==401, 'unauthenticated state blocked')
    check(request(base+'/api/state', token, origin='http://untrusted.example')[0]==403, 'foreign origin blocked')
    code, before=request(base+'/api/state', token)
    check(code==200 and not before['running'], 'authenticated stopped DSP')
    profile=before['profile']
    output.parent.mkdir(parents=True, exist_ok=True)
    (output.parent/'profile-before-lan.json').write_text(json.dumps(profile, indent=2), encoding='utf-8')
    def patch(revision, changes, unlock=False):
        return request(base+'/api/profile', token, {'session':before['controlSession'], 'revision':revision, 'changes':changes, 'unlockDelays':unlock})
    revision=before['profileRevision']
    check(request(base+'/api/profile', token, {'session':'obsolete', 'revision':revision, 'changes':{'muted':False}})[0]==409, 'obsolete session blocked')
    check(patch(revision, {'masterGain':2})[0]==400, 'invalid gain blocked')
    check(patch(revision, {'muted':'false'})[0]==400, 'wrong boolean type blocked')
    check(patch(revision, {'delaySamples':profile['delaySamples']})[0]==400, 'delay lock enforced')
    try:
        code, updated=patch(revision, {'masterGain':profile['masterGain']/2, 'muted':True})
        check(code==200 and updated['profile']['muted'], 'mute and master applied')
        expected={**profile, 'masterGain':updated['profile']['masterGain'], 'muted':True}
        check(updated['profile']==expected, 'other settings preserved')
        check(patch(revision, {'muted':False})[0]==409, 'stale and duplicate writes blocked')
        code, reread=request(base+'/api/state', token)
        check(code==200 and reread['profile']==updated['profile'], 'applied state confirmed')
    finally:
        code, current=request(base+'/api/state', token)
        # Restore only the fields owned by this test, preserving concurrent adjustments.
        code, restored=patch(current['profileRevision'], {'masterGain':profile['masterGain'], 'muted':profile['muted']})
        check(code==200 and restored['profile']==profile, 'original profile restored')
    report={'ok':True,'checks':checks,'scope':'LAN on real A34, DSP stopped, no USB audio started'}
    output.write_text(json.dumps(report, indent=2)+'\n', encoding='utf-8')
    print(json.dumps(report, indent=2))

if __name__=='__main__':
    p=argparse.ArgumentParser()
    p.add_argument('--url',required=True)
    p.add_argument('--private-prefs',type=Path,required=True)
    p.add_argument('--output',type=Path,required=True)
    a=p.parse_args()
    run(a.url.rstrip('/'), a.private_prefs, a.output)
