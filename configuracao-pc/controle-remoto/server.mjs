import http from 'node:http';
import fs from 'node:fs';
import path from 'node:path';
import os from 'node:os';
import crypto from 'node:crypto';
import {fileURLToPath} from 'node:url';
import {isIP} from 'node:net';
import {WorkerBridge} from './worker-bridge.mjs';

const root=path.dirname(fileURLToPath(import.meta.url)), audio=path.dirname(root);
const port=Number(process.env.REMOTE51_PORT||8787);
if(!Number.isInteger(port)||port<1024||port>65535)throw Error('Porta de controle invalida.');
const stateDir=process.env.REMOTE51_DATA_DIR?path.resolve(process.env.REMOTE51_DATA_DIR):root;
fs.mkdirSync(stateDir,{recursive:true});
const startedAt=new Date().toISOString();
const interfaces=os.networkInterfaces();
const detectedLan=Object.entries(interfaces).filter(([name])=>/wi-?fi|wireless|ethernet/i.test(name)&&!/virtual|radmin/i.test(name)).flatMap(([,rows])=>rows).find(x=>x.family==='IPv4'&&!x.internal&&/^(192\.168\.|10\.|172\.(1[6-9]|2\d|3[01])\.)/.test(x.address))?.address;
const lan=process.env.REMOTE51_IP||detectedLan;
if(!lan&&!process.env.REMOTE51_TEST) throw Error('Nenhum IPv4 da rede Wi-Fi encontrado. Defina REMOTE51_IP.');
if(lan&&isIP(lan)!==4)throw Error('Endereco IPv4 invalido.');
const stateFile=path.join(stateDir,'connection-private.json');
const connection=fs.existsSync(stateFile)?JSON.parse(fs.readFileSync(stateFile)): {pin:String(crypto.randomInt(100000,1000000)),bridgeKey:crypto.randomBytes(32).toString('hex')};
if(!/^\d{6}$/.test(connection.pin)||!/^[a-f0-9]{64}$/.test(connection.bridgeKey))throw Error('Arquivo de conexao invalido. Preserve uma copia antes de recriar a configuracao.');
connection.ip=lan||'127.0.0.1'; connection.phoneIp=connection.ip==='127.0.0.1'?(detectedLan||connection.ip):connection.ip; connection.port=port;
const hosts=new Set([`127.0.0.1:${port}`,`localhost:${port}`,`${connection.ip}:${port}`]);
const origins=new Set([...hosts].map(h=>`http://${h}`));
const paired=new Map(), attempts=new Map();
let bridge={seen:0,player:null,tracks:[],subtitles:[],message:null}, commands=[], bridgeSequence=0;
const workerBridge=new WorkerBridge(path.join(root,'worker.ps1'));
const rpc=(command,options)=>workerBridge.rpc(command,options);
let cached=null,cachedAt=0,snapshotPromise=null,cacheVersion=0,ready=false,closing=false;
function invalidateSnapshot(){cacheVersion++;cached=null;}
async function snapshot(){
 if(cached&&Date.now()-cachedAt<2000)return cached;
 if(!snapshotPromise){const version=cacheVersion;snapshotPromise=rpc({type:'snapshot'}).then(value=>{if(version===cacheVersion){cached=value;cachedAt=Date.now();}return value;}).finally(()=>{snapshotPromise=null;});}
 return snapshotPromise;
}
function cleanMemory(){const now=Date.now();for(const [key,s] of paired)if(s.expires<now)paired.delete(key);for(const [key,a] of attempts)if(now-a.start>60000)attempts.delete(key);commands=commands.filter(c=>c.expires>now);}
const cleanupTimer=setInterval(cleanMemory,60000);cleanupTimer.unref();
function rateLimit(key,maximum=10){
 const now=Date.now();let attempt=attempts.get(key);if(!attempt||now-attempt.start>60000)attempt={count:0,start:now};
 attempt.count++;attempts.set(key,attempt);if(attempts.size>1024)attempts.delete(attempts.keys().next().value);
 if(attempt.count>maximum)fail('Aguarde um minuto para tentar novamente.',429);
}
function respond(res,status,data,headers={}){
 res.writeHead(status,{'Content-Type':'application/json; charset=utf-8','Cache-Control':'no-store','X-Content-Type-Options':'nosniff',...headers});res.end(JSON.stringify(data));
}
function fail(message,status=400){throw Object.assign(Error(message),{status});}
async function body(req){
 if(Number(req.headers['content-length'])>16384)fail('Pedido muito grande.',413);
 let data='';for await(const chunk of req){data+=chunk;if(Buffer.byteLength(data)>16384)fail('Pedido muito grande.',413);}
 let parsed;try{parsed=JSON.parse(data||'{}');}catch{fail('JSON invalido.');}
 if(!parsed||typeof parsed!=='object'||Array.isArray(parsed))fail('Use um objeto JSON.');return parsed;
}
function session(req){const cookie=(req.headers.cookie||'').match(/(?:^|;\s*)remote51=([a-f0-9]{64})(?:;|$)/),s=cookie&&paired.get(cookie[1]);if(!s||s.expires<Date.now()){if(cookie)paired.delete(cookie[1]);fail('Conecte o celular com o PIN.',401);}s.lastSeen=Date.now();return s;}
function assertOrigin(req){if(!req.headers.origin||!origins.has(req.headers.origin))fail('Origem recusada.',403);if(!(req.headers['content-type']||'').startsWith('application/json'))fail('Use JSON.',415);}
async function jelly(s,route,method='GET',payload){
 if(!s.jelly)fail('Entre no Jellyfin na aba Conectar.',401);
 let response;try{response=await fetch(`http://127.0.0.1:8096${route}`,{method,headers:{'X-Emby-Token':s.jelly.token,'Content-Type':'application/json'},body:payload?JSON.stringify(payload):undefined,signal:AbortSignal.timeout(8000)});}catch{fail('Jellyfin nao respondeu. Verifique se o servidor esta aberto.',502);}
 if(!response.ok)fail(response.status===401?'Sessao Jellyfin expirada. Entre novamente.':`Jellyfin respondeu ${response.status}.`,response.status===401?401:502);
 return response.status===204?null:response.json();
}
async function jellySessions(s){
 if(!s.jelly)return [];
 if(s.jellyCache&&Date.now()-s.jellyCache.at<750)return s.jellyCache.sessions;
 if(s.jellyPending)return s.jellyPending;
 const identity=s.jelly;
 s.jellyPending=loadJellySessions(s).then(sessions=>{if(s.jelly===identity)s.jellyCache={at:Date.now(),sessions};return sessions;}).finally(()=>{s.jellyPending=null;});
 return s.jellyPending;
}
async function loadJellySessions(s){
 const sessions=await jelly(s,`/Sessions?controllableByUserId=${encodeURIComponent(s.jelly.userId)}`);
 return sessions.filter(x=>x.SupportsRemoteControl&&x.DeviceId!=='controle-51-web').map(x=>({id:x.Id,app:x.Client,device:x.DeviceName,title:x.NowPlayingItem?.Name||'Sem video',paused:x.PlayState?.IsPaused,position:(x.PlayState?.PositionTicks||0)/1e7,duration:(x.NowPlayingItem?.RunTimeTicks||0)/1e7,itemId:x.NowPlayingItem?.Id,sourceId:x.PlayState?.MediaSourceId,streams:x.NowPlayingItem?.MediaStreams||[],audioIndex:x.PlayState?.AudioStreamIndex,subtitleIndex:x.PlayState?.SubtitleStreamIndex,commands:x.SupportedCommands||[]}));
}
async function targetJelly(s,id){if(typeof id!=='string'||!/^[a-f0-9-]{16,64}$/i.test(id))fail('Sessao invalida.');const target=(await jellySessions(s)).find(x=>x.id===id);if(!target)fail('Essa reproducao nao esta mais disponivel.',404);return target;}
function bridgeCommand(action,extra={}){if(Date.now()-bridge.seen>6000||!bridge.player)fail('A extensao Netflix ainda nao esta conectada.',409);const command={id:++bridgeSequence,action,player:bridge.player.id,...extra};commands.push({...command,expires:Date.now()+5000});if(commands.length>20)commands.shift();return {queued:true,id:command.id};}
const staticFiles={'/':['index.html','text/html; charset=utf-8'],'/app.js':['app.js','application/javascript; charset=utf-8'],'/style.css':['style.css','text/css; charset=utf-8'],'/manifest.webmanifest':['manifest.webmanifest','application/manifest+json'],'/icon.svg':['icon.svg','image/svg+xml']};
async function handler(req,res){
 try{
  if(!hosts.has(req.headers.host))fail('Host recusado.',403);
  const url=new URL(req.url,`http://${req.headers.host}`),route=url.pathname;
  if(route==='/health'&&req.method==='GET')return respond(res,ready?200:503,{ok:ready,pid:process.pid,ip:connection.ip,port,startedAt});
  if(route==='/setup'&&req.method==='GET'){
   if(req.socket.remoteAddress!=='127.0.0.1')fail('Abra esta pagina no PC.',403);
   const setup=`<!doctype html><meta charset="utf-8"><meta name="viewport" content="width=device-width"><title>Conectar A34</title><style>body{font:20px system-ui;background:#101722;color:#eef5ff;max-width:700px;margin:8vh auto;padding:24px}a{color:#8df0d1}code{font-size:32px}p{line-height:1.6}</style><h1>Controle remoto do PC</h1>${connection.ip==='127.0.0.1'?'<p><b>Acesso pelo Wi-Fi ainda desligado.</b> Abra o atalho “Controle remoto do PC” na area de trabalho para ativar.</p>':''}<p>No A34, conectado ao mesmo Wi-Fi, abra:</p><p><a href="http://${connection.phoneIp}:${port}">http://${connection.phoneIp}:${port}</a></p><p>PIN para conectar: <code>${connection.pin}</code></p><p>Depois, use “Adicionar à tela inicial” no navegador do celular.</p><p>O volume das seis caixas e a reproducao pelo Windows ja funcionam. Para audio e legendas, entre no Jellyfin pelo controle ou instale a extensao no Edge.</p><p><a href="/">Abrir o controle neste PC</a></p><details><summary>Extensao Netflix</summary><p>Abra edge://extensions, ative Modo de desenvolvedor, clique em Carregar sem compactacao e selecione a pasta <b>${path.join(root,'extension')}</b>. Atualize a Netflix. O controle do Windows funciona sem essa extensao.</p></details>`;
   res.writeHead(200,{'Content-Type':'text/html; charset=utf-8','Cache-Control':'no-store'});return res.end(setup);
  }
  if(staticFiles[route]&&req.method==='GET'){
   const [file,mime]=staticFiles[route];res.writeHead(200,{'Content-Type':mime,'Cache-Control':'no-cache','X-Content-Type-Options':'nosniff','Content-Security-Policy':"default-src 'self'; script-src 'self'; style-src 'self'; img-src 'self' data:; connect-src 'self'; frame-ancestors 'none'; base-uri 'none'"});return res.end(fs.readFileSync(path.join(root,'web',file)));
  }
  if(route==='/bridge'&&req.method==='POST'){
   const key=String(req.headers['x-bridge-key']||'');if(!/^[a-f0-9]{64}$/.test(key)||!crypto.timingSafeEqual(Buffer.from(key),Buffer.from(connection.bridgeKey)))fail('Ponte nao autorizada.',401);
   if(req.socket.remoteAddress!=='127.0.0.1')fail('Ponte somente neste PC.',403);
   const b=await body(req);const cleanTracks=list=>Array.isArray(list)?list.slice(0,100).map((t,i)=>({index:i,label:String(t.label||`Faixa ${i+1}`).slice(0,120),selected:!!t.selected})):[];
   if(!b.player&&bridge.player&&Date.now()-bridge.seen<6000)return respond(res,200,{commands:[]});
   bridge={seen:Date.now(),player:b.player?{id:String(b.player.id).slice(0,80),title:String(b.player.title||'Netflix').slice(0,200),paused:!!b.player.paused,position:Number(b.player.position)||0,duration:Number(b.player.duration)||0}:null,tracks:cleanTracks(b.tracks),subtitles:cleanTracks(b.subtitles),message:b.message?String(b.message).slice(0,200):null};
   const todo=commands.filter(c=>c.expires>Date.now()&&c.player===bridge.player?.id).map(({expires,...c})=>c);commands=[];return respond(res,200,{commands:todo});
  }
  if(req.method==='POST')assertOrigin(req);
  if(route==='/api/pair'&&req.method==='POST'){
   rateLimit(`pair:${req.socket.remoteAddress}`);
   const b=await body(req),pin=String(b.pin||'');if(!/^\d{6}$/.test(pin)||!crypto.timingSafeEqual(Buffer.from(pin),Buffer.from(connection.pin)))fail('PIN incorreto.',401);
   cleanMemory();if(paired.size>=32){const oldest=[...paired].sort((a,b)=>a[1].lastSeen-b[1].lastSeen)[0];paired.delete(oldest[0]);}
   const sid=crypto.randomBytes(32).toString('hex');paired.set(sid,{expires:Date.now()+7*86400000,lastSeen:Date.now(),jelly:null});return respond(res,200,{ok:true},{'Set-Cookie':`remote51=${sid}; HttpOnly; SameSite=Strict; Path=/; Max-Age=604800`});
  }
  const s=session(req);
  if(route==='/api/status'&&req.method==='GET'){
   const current=await snapshot();
   let sessions=[],jellyError=null;try{sessions=await jellySessions(s);}catch(e){jellyError=e.message;}
   return respond(res,200,{...current,jellyConnected:!!s.jelly,jellySessions:sessions,jellyError,netflix:Date.now()-bridge.seen<6000?bridge:null,backend:{startedAt,snapshotAt:cachedAt?new Date(cachedAt).toISOString():null,...workerBridge.diagnostics()}});
  }
  const b=req.method==='POST'?await body(req):{};
  if(route==='/api/input'&&req.method==='POST'){
   if(typeof b.window!=='string'||!(b.window==='foreground'||/^window:[a-f0-9]+:\d+:\d+$/.test(b.window)))fail('Janela invalida.');
   const actions=['up','down','left','right','select','back','home','search','fullscreen','tab','shifttab','browserback','space','rewind','forward','skipintro','next','previous','scrollup','scrolldown','move','text'];
   if(!actions.includes(b.action)||!['keyboard','mouse'].includes(b.mode||'keyboard'))fail('Comando invalido.');
   if(b.action==='text'&&(typeof b.text!=='string'||!b.text.trim()||b.text.length>500||/[\u0000-\u001f\u007f]/.test(b.text)))fail('Texto invalido.');
   if(b.action==='move'&&(!Number.isInteger(b.dx)||!Number.isInteger(b.dy)||Math.abs(b.dx)>300||Math.abs(b.dy)>300))fail('Movimento invalido.');
   const result=await rpc({type:'input',window:b.window,action:b.action,mode:b.mode||'keyboard',text:b.text,dx:b.dx||0,dy:b.dy||0});invalidateSnapshot();return respond(res,200,result);
  }
  if(route==='/api/volume'&&req.method==='POST'){
   if(!Number.isInteger(b.percent)||b.percent<0||b.percent>100||typeof b.muted!=='boolean')fail('Volume invalido.');const result=await rpc({type:'volume',percent:b.percent,muted:b.muted});invalidateSnapshot();return respond(res,200,result);
  }
  if(route==='/api/audio'&&req.method==='POST'){
   if(!['Ligar','Desligar','UpmixAuto','Nativo'].includes(b.action))fail('Acao invalida.');const result=await rpc({type:'audio',action:b.action},{timeoutMs:40000});invalidateSnapshot();return respond(res,200,result);
  }
  if(route==='/api/profile'&&req.method==='POST'){
   if(!['Fidelidade','Estavel'].includes(b.profile))fail('Perfil invalido.');const result=await rpc({type:'profile',profile:b.profile},{timeoutMs:80000});invalidateSnapshot();return respond(res,200,result);
  }
  if(route==='/api/media'&&req.method==='POST'){
   if(!['play','pause','toggle','next','previous','seek'].includes(b.action))fail('Acao invalida.');if(b.action==='seek'&&(!Number.isFinite(b.seconds)||Math.abs(b.seconds)>600))fail('Tempo invalido.');if(b.session&&String(b.session).length>300)fail('Aplicativo invalido.');
   const result=await rpc({type:'media',action:b.action,seconds:b.seconds,session:b.session});invalidateSnapshot();return respond(res,200,result);
  }
  if(route==='/api/jellyfin/login'&&req.method==='POST'){
   rateLimit(`jelly-login:${req.socket.remoteAddress}`,10);
   if(typeof b.username!=='string'||b.username.length>100||typeof b.password!=='string'||b.password.length>300)fail('Dados de entrada invalidos.');
   const response=await fetch('http://127.0.0.1:8096/Users/AuthenticateByName',{method:'POST',headers:{'Content-Type':'application/json','X-Emby-Authorization':'MediaBrowser Client="Controle 5.1", Device="Controle Web", DeviceId="controle-51-web", Version="1.0"'},body:JSON.stringify({Username:b.username,Pw:b.password}),signal:AbortSignal.timeout(8000)});
   if(!response.ok)fail('Nao foi possivel entrar no Jellyfin. Confira usuario e senha.',401);const login=await response.json();s.jelly={token:login.AccessToken,userId:login.User.Id};s.jellyCache=null;s.jellyPending=null;return respond(res,200,{ok:true});
  }
  if(route==='/api/jellyfin/tracks'&&req.method==='POST'){
   const t=await targetJelly(s,b.session);let streams=t.streams;
   if(!streams.length&&t.itemId){const item=await jelly(s,`/Items/${t.itemId}/PlaybackInfo?UserId=${s.jelly.userId}`);streams=(item.MediaSources?.find(x=>x.Id===t.sourceId)||item.MediaSources?.[0])?.MediaStreams||[];}
   const track=x=>({index:x.Index,label:x.DisplayTitle||[x.Language,x.Codec,x.Channels?`${x.Channels} canais`:null].filter(Boolean).join(' · '),selected:x.Type==='Audio'?x.Index===t.audioIndex:x.Index===t.subtitleIndex});
   return respond(res,200,{audio:streams.filter(x=>x.Type==='Audio').map(track),subtitles:[{index:-1,label:'Sem legendas',selected:t.subtitleIndex===-1||t.subtitleIndex==null},...streams.filter(x=>x.Type==='Subtitle').map(track)]});
  }
  if(route==='/api/jellyfin/command'&&req.method==='POST'){
   const t=await targetJelly(s,b.session);
   if(['play','pause','toggle','next','previous','seek'].includes(b.action)){
    const command={play:'Unpause',pause:'Pause',toggle:'PlayPause',next:'NextTrack',previous:'PreviousTrack',seek:'Seek'}[b.action];let query='';if(b.action==='seek'){if(!Number.isFinite(b.seconds)||Math.abs(b.seconds)>600)fail('Tempo invalido.');query=`?seekPositionTicks=${Math.round(Math.max(0,Math.min(t.duration,t.position+b.seconds))*1e7)}`;}
    await jelly(s,`/Sessions/${t.id}/Playing/${command}${query}`,'POST');
   }else if(['audio','subtitle'].includes(b.action)){
    if(!Number.isInteger(b.index)||b.index< -1||b.index>1000)fail('Faixa invalida.');const command=b.action==='audio'?'SetAudioStreamIndex':'SetSubtitleStreamIndex';await jelly(s,`/Sessions/${t.id}/Command`,'POST',{Name:command,Arguments:{Index:String(b.index)}});
   }else if(['up','down','left','right','select','back','home','search','fullscreen'].includes(b.action)){
    const name={up:'MoveUp',down:'MoveDown',left:'MoveLeft',right:'MoveRight',select:'Select',back:'Back',home:'GoHome',search:'GoToSearch',fullscreen:'ToggleFullscreen'}[b.action];
    await jelly(s,`/Sessions/${t.id}/Command`,'POST',{Name:name,Arguments:{}});
   }else if(b.action==='text'){
    if(typeof b.text!=='string'||!b.text.trim()||b.text.length>500||/[\u0000-\u0008\u000b\u000c\u000e-\u001f]/.test(b.text))fail('Texto invalido. Use ate 500 caracteres.');
    await jelly(s,`/Sessions/${t.id}/Command`,'POST',{Name:'SendString',Arguments:{String:b.text}});
   }else fail('Acao invalida.');return respond(res,200,{accepted:true});
  }
  if(route==='/api/netflix/open'&&req.method==='POST'){
   const mode=b.mode||'app';if(!['app','browser'].includes(mode))fail('Modo Netflix invalido.');
   const result=await rpc({type:'netflix',mode},{timeoutMs:80000});invalidateSnapshot();return respond(res,200,result);
  }
  if(route==='/api/netflix/command'&&req.method==='POST'){
   if(!['play','pause','toggle','seek','audio','subtitle'].includes(b.action))fail('Acao invalida.');
   if(['audio','subtitle'].includes(b.action)){const list=b.action==='audio'?bridge.tracks:bridge.subtitles;if(!Number.isInteger(b.index)||!list[b.index])fail('Faixa indisponivel.');}
   if(b.action==='seek'&&(!Number.isFinite(b.seconds)||Math.abs(b.seconds)>600))fail('Tempo invalido.');
   return respond(res,200,bridgeCommand(b.action,{index:b.index,seconds:b.seconds}));
  }
  if(route==='/api/logout'&&req.method==='POST'){s.expires=0;s.jelly=null;cleanMemory();return respond(res,200,{ok:true},{'Set-Cookie':'remote51=; HttpOnly; SameSite=Strict; Path=/; Max-Age=0'});}
  fail('Pagina nao encontrada.',404);
 }catch(e){if(!res.destroyed&&!res.writableEnded)respond(res,e.status||500,{error:e.message||'Falha no controle.'});}
}
const servers=[];
async function listen(){
 for(const host of [...new Set(['127.0.0.1',connection.ip])]){
  const server=http.createServer(handler);server.requestTimeout=20000;server.headersTimeout=10000;server.keepAliveTimeout=5000;
  servers.push(server);
  await new Promise((resolve,reject)=>{server.once('error',reject);server.listen(port,host,()=>{server.removeListener('error',reject);resolve();});});
  server.on('error',e=>{console.error(e.code||'Falha no servidor HTTP.');shutdown();process.exitCode=1;});
 }
 fs.writeFileSync(stateFile,JSON.stringify(connection,null,2));
 if(stateDir===root)fs.writeFileSync(path.join(root,'extension','connection.json'),JSON.stringify({origin:`http://127.0.0.1:${port}`,key:connection.bridgeKey}));
 fs.writeFileSync(path.join(stateDir,'endereco.txt'),`http://${connection.ip}:${port}\nPIN: ${connection.pin}\n`);
 fs.writeFileSync(path.join(stateDir,'server.pid'),String(process.pid));ready=true;
 console.log(`Controle pronto em http://${connection.ip}:${port}. Configuracao local: http://127.0.0.1:${port}/setup`);
}
function shutdown(){
 if(closing)return;closing=true;ready=false;clearInterval(cleanupTimer);
 for(const server of servers){server.close();server.closeAllConnections();}workerBridge.close();
 try{const pidFile=path.join(stateDir,'server.pid');if(fs.readFileSync(pidFile,'utf8').trim()===String(process.pid))fs.unlinkSync(pidFile);}catch{}
}
process.on('SIGINT',()=>{shutdown();process.exit(0);});process.on('SIGTERM',()=>{shutdown();process.exit(0);});
listen().catch(e=>{console.error(e.code||'Nao foi possivel iniciar o controle.');shutdown();process.exitCode=1;});
