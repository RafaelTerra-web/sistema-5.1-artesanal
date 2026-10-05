import test from 'node:test';
import assert from 'node:assert/strict';
import {EventEmitter} from 'node:events';
import {PassThrough,Writable} from 'node:stream';
import {spawn} from 'node:child_process';
import fs from 'node:fs';
import os from 'node:os';
import path from 'node:path';
import {fileURLToPath} from 'node:url';
import {WorkerBridge} from './worker-bridge.mjs';

function fakeChild(onCommand){
 const child=new EventEmitter();child.stdout=new PassThrough();child.stderr=new PassThrough();child.killed=false;
 child.stdin=new Writable({write(chunk,encoding,done){onCommand(JSON.parse(chunk.toString()),child);done();}});
 child.kill=()=>{child.killed=true;setTimeout(()=>child.emit('exit',1),30);};
 child.reply=(id,data)=>child.stdout.write(JSON.stringify({id,ok:true,data})+'\n');return child;
}
test('worker timeout is discarded; late exit from old worker cannot interrupt its replacement',async()=>{
 let starts=0,old;
 const bridge=new WorkerBridge('unused',{timeoutMs:20,spawnWorker:()=>{
  starts++;return starts===1?(old=fakeChild(()=>{})):fakeChild((request,child)=>setTimeout(()=>child.reply(request.id,{recovered:true}),60));
 }});
 await assert.rejects(bridge.rpc({type:'snapshot'}),/demorou/);assert.equal(old.killed,true);
 const result=await bridge.rpc({type:'snapshot'},{timeoutMs:150});assert.deepEqual(result,{recovered:true});assert.equal(bridge.diagnostics().workerRestarts,1);bridge.close();
});
test('queue is bounded and shutdown rejects active and queued requests',async()=>{
 const bridge=new WorkerBridge('unused',{maxQueue:2,spawnWorker:()=>fakeChild(()=>{})});
 const first=bridge.rpc({type:'snapshot'}),second=bridge.rpc({type:'snapshot'});
 await assert.rejects(bridge.rpc({type:'snapshot'}),error=>error.status===429);
 const outcome=Promise.allSettled([first,second]);bridge.close();assert((await outcome).every(x=>x.status==='rejected'));
});
test('spawn error is handled and a later command starts a fresh worker without replaying the first',async()=>{
 let starts=0;
 const bridge=new WorkerBridge('unused',{spawnWorker:()=>{
  starts++;const child=fakeChild((request,c)=>{if(starts>1)c.reply(request.id,{ok:true});});
  if(starts===1)queueMicrotask(()=>child.emit('error',Error('spawn failed')));return child;
 }});
 await assert.rejects(bridge.rpc({type:'media'}),/reiniciou/);assert.deepEqual(await bridge.rpc({type:'snapshot'}),{ok:true});assert.equal(starts,2);bridge.close();
});
test('HTTP authentication, Origin checks and live read-only snapshots on an isolated loopback server', {skip:process.platform!=='win32',timeout:45000},async context=>{
 const directory=fs.mkdtempSync(path.join(os.tmpdir(),'remote51-backend-test-'));
 const root=path.dirname(fileURLToPath(import.meta.url)),port=18787,origin=`http://127.0.0.1:${port}`;
 const child=spawn(process.execPath,[path.join(root,'server.mjs')],{env:{...process.env,REMOTE51_IP:'127.0.0.1',REMOTE51_PORT:String(port),REMOTE51_TEST:'1',REMOTE51_DATA_DIR:directory},windowsHide:true,stdio:['ignore','pipe','pipe']});
 let output='';child.stderr.on('data',chunk=>{output+=chunk;});child.stdout.on('data',()=>{});
 try{
  let health;for(let i=0;i<100;i++){
   if(child.exitCode!==null)throw Error(`Test server exited: ${output}`);
   try{const response=await fetch(`${origin}/health`);health=await response.json();if(health.ok&&health.pid===child.pid)break;}catch{}
   await new Promise(resolve=>setTimeout(resolve,50));
  }
  assert.equal(health?.pid,child.pid);assert.equal(health.ip,'127.0.0.1');
  const duplicate=spawn(process.execPath,[path.join(root,'server.mjs')],{env:{...process.env,REMOTE51_IP:'127.0.0.1',REMOTE51_PORT:String(port),REMOTE51_TEST:'1',REMOTE51_DATA_DIR:directory},windowsHide:true,stdio:'ignore'});
  await new Promise((resolve,reject)=>{const deadline=setTimeout(()=>{duplicate.kill();reject(Error('Duplicate server did not stop.'));},3000);duplicate.once('exit',()=>{clearTimeout(deadline);resolve();});});
  assert.equal(duplicate.exitCode,1);assert.equal(fs.readFileSync(path.join(directory,'server.pid'),'utf8').trim(),String(child.pid));
  const credentials=JSON.parse(fs.readFileSync(path.join(directory,'connection-private.json'),'utf8'));
  const post=(route,data,headers={})=>fetch(`${origin}${route}`,{method:'POST',headers:{Origin:origin,'Content-Type':'application/json',...headers},body:JSON.stringify(data)});
  assert.equal((await fetch(`${origin}/api/status`)).status,401);
  assert.equal((await post('/api/input',{window:'foreground',action:'tab'})).status,401);
  assert.equal((await post('/api/pair',{pin:credentials.pin},{Origin:'http://untrusted.invalid'})).status,403);
  assert.equal((await post('/api/pair',null)).status,400);
  assert.equal((await fetch(`${origin}/connection-private.json`)).status,401);
  const login=await post('/api/pair',{pin:credentials.pin});assert.equal(login.status,200);
  const cookie=login.headers.get('set-cookie').split(';')[0];
  const statusRequests=await Promise.all([fetch(`${origin}/api/status`,{headers:{Cookie:cookie}}),fetch(`${origin}/api/status`,{headers:{Cookie:cookie}})]);
  assert(statusRequests.every(response=>response.status===200));
  const [first,second]=await Promise.all(statusRequests.map(response=>response.json()));
  assert.equal(first.updatedAt,second.updatedAt);assert.equal(first.backend.workerRestarts,0);assert(Array.isArray(first.sessions));assert.equal(first.mediaError,null);
  assert(Array.isArray(first.windows));assert(first.windows.every(x=>/^window:[a-f0-9]+:\d+:\d+$/.test(x.id)));
  const ids=first.sessions.map(x=>x.id);assert(ids.every(id=>/^media:[a-f0-9]{32}$/.test(id)));
  await new Promise(resolve=>setTimeout(resolve,2200));
  const later=await (await fetch(`${origin}/api/status`,{headers:{Cookie:cookie}})).json();
  context.diagnostic(`Read-only snapshot: cold ${first.snapshotElapsedMs} ms; warm ${later.snapshotElapsedMs} ms; media sessions ${later.sessions.length}.`);
  for(const item of first.sessions){const matches=later.sessions.filter(x=>x.app===item.app);if(matches.length===1&&first.sessions.filter(x=>x.app===item.app).length===1)assert.equal(matches[0].id,item.id);}
  assert.equal((await post('/api/volume',{percent:101,muted:false},{Cookie:cookie})).status,400);
  assert.equal((await post('/api/audio',{action:'ArbitraryScript'},{Cookie:cookie})).status,400);
  assert.equal((await post('/api/profile',{profile:'arbitrary'},{Cookie:cookie})).status,400);
  assert.equal((await post('/api/media',{action:'seek',seconds:Infinity},{Cookie:cookie})).status,400);
  for (const invalid of [
    {window:'arbitrary',action:'tab'},
    {window:'foreground',action:'ArbitraryScript'},
    {window:'foreground',action:'tab',mode:'arbitrary'},
    {window:'foreground',action:'text',text:'test\n'},
    {window:'foreground',action:'move',dx:301,dy:0},
    {window:'foreground',action:'move',dx:0.5,dy:0}
  ]) assert.equal((await post('/api/input',invalid,{Cookie:cookie})).status,400);
  await post('/api/logout',{}, {Cookie:cookie});assert.equal((await fetch(`${origin}/api/status`,{headers:{Cookie:cookie}})).status,401);
  for(let i=0;i<9;i++)await post('/api/pair',{pin:'000000'});
  assert.equal((await post('/api/pair',{pin:'000000'})).status,429);
 }finally{
  child.kill();await new Promise(resolve=>{if(child.exitCode!==null)return resolve();child.once('exit',resolve);setTimeout(resolve,3000);});
  // Leave no test server running. Generated test state contains no user credentials.
  fs.writeFileSync(path.join(directory,'TEST-ONLY.txt'),'Servidor de teste encerrado; porta local 18787.\n');
 }
});
