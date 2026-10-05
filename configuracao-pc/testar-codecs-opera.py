"""Diagnóstico local de codecs; não lê histórico, cookies nem mídia remota."""
from http.server import BaseHTTPRequestHandler, HTTPServer
from pathlib import Path
import json
import time

ROOT = Path(__file__).resolve().parent
PAGE = '''<!doctype html><meta charset="utf-8"><title>Teste Dolby Digital no Opera</title>
<style>body{font:18px system-ui;max-width:800px;margin:50px auto;padding:20px;background:#111827;color:#eee}pre{white-space:pre-wrap}</style>
<h1>Teste de Dolby Digital no Opera</h1><p>Consulta local. Não altera a rota de áudio nem reproduz som.</p><pre id="result">Verificando…</pre>
<script>
(async()=>{
 const result={userAgent:navigator.userAgent,output:{},codecs:[],channelParameter:[]};
 try{const c=new AudioContext();result.output={maxChannelCount:c.destination.maxChannelCount,sampleRate:c.sampleRate};await c.close()}catch(e){result.output.error=e.message}
 for(const [name,type] of [['AAC','audio/mp4; codecs="mp4a.40.2"'],['Opus','audio/webm; codecs="opus"'],['AC-3','audio/mp4; codecs="ac-3"'],['E-AC-3','audio/mp4; codecs="ec-3"']]){
  const row={name,mediaSource:MediaSource.isTypeSupported(type),canPlayType:document.createElement('audio').canPlayType(type)};
  try{const info=await navigator.mediaCapabilities.decodingInfo({type:'media-source',audio:{contentType:type,channels:'6',bitrate:448000,samplerate:48000}});row.sixChannelsSupported=info.supported;row.smooth=info.smooth;row.powerEfficient=info.powerEfficient}catch(e){row.error=e.message}
  result.codecs.push(row);
 }
 for (const codec of ['mp4a.40.2','ac-3','ec-3']) {
  for (const channels of [2,6,99]) {
   const type=`audio/mp4; codecs="${codec}"; channels=${channels}`;
   result.channelParameter.push({codec,channels,supported:MediaSource.isTypeSupported(type)});
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
        if not 0 < length < 16384:
            self.send_error(400)
            return
        result = json.loads(self.rfile.read(length))
        (ROOT / 'opera-codecs-resultado.json').write_text(
            json.dumps(result, ensure_ascii=False, indent=2), encoding='utf-8')
        self.send_response(204)
        self.end_headers()
        self.server.result_received = True

server = HTTPServer(('127.0.0.1', 0), Handler)
server.timeout = 1
server.result_received = False
(ROOT / 'opera-codecs-url.txt').write_text(
    f'http://127.0.0.1:{server.server_port}/', encoding='ascii')
deadline = time.monotonic() + 90
try:
    while not server.result_received and time.monotonic() < deadline:
        server.handle_request()
finally:
    server.server_close()
