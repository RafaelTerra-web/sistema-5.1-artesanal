"""Local, read-only browser capabilities for Netflix. No account/media/cookies."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json
import time

ROOT = Path(__file__).resolve().parent
PAGE = '''<!doctype html><meta charset="utf-8"><title>Capacidade Dolby para Netflix</title>
<style>body{font:18px system-ui;max-width:900px;margin:40px auto;padding:20px;background:#111827;color:#eee}pre{white-space:pre-wrap}</style>
<h1>Capacidade Dolby para Netflix</h1><p>Diagnóstico local. Não reproduz áudio nem acessa sua conta.</p><pre id="result">Verificando…</pre>
<script>
(async()=>{
 const result={userAgent:navigator.userAgent,output:{},codecs:[],drm:[],spatialAndDrm:[],endpointFeatures:[]};
 result.msMediaKeys={available:!!window.MSMediaKeys,hasFeatureMethod:typeof window.MSMediaKeys?.isTypeSupportedWithFeatures==='function'};
 for(const feature of ['', 'audio-endpoint-codec=DD','audio-endpoint-codec=DD+','audio-endpoint-codec=DD+JOC']){
  const type='video/mp4;codecs="avc1,mp4a"'+(feature?';features="'+feature+'"':'');
  const row={feature,contentType:type};
  try{row.result=window.MSMediaKeys?.isTypeSupportedWithFeatures('com.microsoft.playready',type)??'API unavailable'}catch(e){row.error=e.name+': '+e.message}
  result.endpointFeatures.push(row);
 }
 try{const c=new AudioContext();result.output={maxChannelCount:c.destination.maxChannelCount,sampleRate:c.sampleRate};await c.close()}catch(e){result.output.error=e.message}
 for(const [name,type] of [['AAC','audio/mp4; codecs="mp4a.40.2"'],['AC-3','audio/mp4; codecs="ac-3"'],['E-AC-3','audio/mp4; codecs="ec-3"']]){
  const row={name,mediaSource:MediaSource.isTypeSupported(type),canPlayType:document.createElement('audio').canPlayType(type)};
  try{const info=await navigator.mediaCapabilities.decodingInfo({type:'media-source',audio:{contentType:type,channels:'6',bitrate:640000,samplerate:48000}});row.sixChannelsSupported=info.supported;row.smooth=info.smooth;row.powerEfficient=info.powerEfficient}catch(e){row.error=e.message}
  result.codecs.push(row);
 }
 for(const channels of ['6','5.1']){
 for(const spatialRendering of [false,true]){
  for(const keySystem of [null,'com.microsoft.playready.recommendation','com.widevine.alpha']){
   const row={codec:'ec-3',channels,spatialRendering,keySystem};
   const configuration={type:'media-source',audio:{contentType:'audio/mp4; codecs="ec-3"',channels,bitrate:640000,samplerate:48000,spatialRendering}};
   if(keySystem) configuration.keySystemConfiguration={keySystem,initDataType:'cenc',distinctiveIdentifier:'not-allowed',persistentState:'not-allowed',sessionTypes:['temporary'],audio:{robustness:''}};
   try{const info=await navigator.mediaCapabilities.decodingInfo(configuration);row.supported=info.supported;row.smooth=info.smooth;row.powerEfficient=info.powerEfficient;row.keySystemAccess=!!info.keySystemAccess}catch(e){row.error=e.name+': '+e.message}
   result.spatialAndDrm.push(row);
  }
 }
 }
 for(const keySystem of ['com.microsoft.playready.recommendation','com.widevine.alpha']){
  for(const codec of ['mp4a.40.2','ec-3']){
   const row={keySystem,codec};
   try{const a=await navigator.requestMediaKeySystemAccess(keySystem,[{initDataTypes:['cenc'],audioCapabilities:[{contentType:`audio/mp4; codecs="${codec}"`}],sessionTypes:['temporary']}]);row.supported=true;row.configuration=a.getConfiguration()}catch(e){row.supported=false;row.error=e.name}
   result.drm.push(row);
  }
 }
 document.getElementById('result').textContent=JSON.stringify(result,null,2);
 await fetch('/resultado',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify(result)});
})();</script>'''

class Handler(BaseHTTPRequestHandler):
    def log_message(self, *_):
        pass
    def do_GET(self):
        if self.path != '/':
            self.send_error(404)
            return
        data = PAGE.encode('utf-8')
        self.send_response(200)
        self.send_header('Content-Type', 'text/html; charset=utf-8')
        self.send_header('Content-Length', str(len(data)))
        self.end_headers()
        self.wfile.write(data)
    def do_POST(self):
        if self.path != '/resultado':
            self.send_error(404)
            return
        length = int(self.headers.get('Content-Length', '0'))
        if not 0 < length < 32768:
            self.send_error(400)
            return
        result = json.loads(self.rfile.read(length))
        (ROOT / 'netflix-edge-codecs-resultado.json').write_text(json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        self.send_response(204)
        self.end_headers()
        self.server.result_received = True

server = HTTPServer(('127.0.0.1', 0), Handler)
server.timeout = 1
server.result_received = False
(ROOT / 'netflix-codecs-url.txt').write_text(f'http://127.0.0.1:{server.server_port}/', encoding='ascii')
deadline = time.monotonic() + 90
try:
    while not server.result_received and time.monotonic() < deadline:
        server.handle_request()
finally:
    server.server_close()
