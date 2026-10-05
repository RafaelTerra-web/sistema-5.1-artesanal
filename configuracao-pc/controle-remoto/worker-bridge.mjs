import {spawn} from 'node:child_process';
import readline from 'node:readline';

// One PowerShell worker keeps WinRT initialization out of each HTTP request.
// A timeout discards that worker. Commands are never replayed automatically.
export class WorkerBridge {
 constructor(script,{spawnWorker,timeoutMs=14000,maxQueue=32}={}){
  this.spawnWorker=spawnWorker||(()=>spawn('powershell.exe',['-NoLogo','-NoProfile','-ExecutionPolicy','Bypass','-File',script],{windowsHide:true,stdio:['pipe','pipe','pipe']}));
  this.timeoutMs=timeoutMs;this.maxQueue=maxQueue;this.child=null;this.pending=null;this.queue=Promise.resolve();this.depth=0;this.sequence=0;this.starts=0;this.closed=false;this.lastError=null;
 }
 diagnostics(){return {workerReady:!!this.child&&!this.child.killed,workerRestarts:Math.max(0,this.starts-1),workerQueue:this.depth,lastWorkerError:this.lastError};}
 start(){
  if(this.closed)throw Error('O controle esta encerrando.');
  const child=this.spawnWorker();this.child=child;this.starts++;
  const lines=readline.createInterface({input:child.stdout});
  lines.on('line',line=>{
   if(this.child!==child)return;
   let result;try{result=JSON.parse(line);}catch{return;}
   const active=this.pending;if(!active||result.id!==active.id)return;
   this.finish(active,result.ok?null:Error(result.error||'O aplicativo recusou o comando.'),result.data);
  });
  const failed=()=>{lines.close();if(this.child!==child)return;this.child=null;this.lastError={at:new Date().toISOString(),message:'O processo de controle foi encerrado. O proximo pedido inicia outro.'};if(this.pending)this.finish(this.pending,Error('O controle de audio reiniciou. Tente novamente.'));};
  child.once('error',failed);child.once('exit',failed);child.stdin.on('error',failed);
  child.stderr.on('data',()=>{});
  return child;
 }
 finish(active,error,data){
  if(this.pending!==active)return;
  this.pending=null;clearTimeout(active.timer);error?active.reject(error):active.resolve(data);
 }
 discard(){const child=this.child;this.child=null;if(child&&!child.killed)child.kill();}
 rpc(command,{timeoutMs=this.timeoutMs}={}){
  if(this.closed)return Promise.reject(Error('O controle esta encerrando.'));
  if(this.depth>=this.maxQueue)return Promise.reject(Object.assign(Error('Ha muitos comandos aguardando. Tente novamente.'),{status:429}));
  this.depth++;
  const job=this.queue.then(()=>new Promise((resolve,reject)=>{
   if(this.closed){reject(Error('O controle esta encerrando.'));return;}
   let child;try{child=this.child||this.start();}catch(e){reject(e);return;}
   const active={id:++this.sequence,resolve,reject,timer:null};this.pending=active;
   active.timer=setTimeout(()=>{
    this.lastError={at:new Date().toISOString(),message:'O processo de controle excedeu o tempo de resposta e foi reiniciado.'};
    this.discard();this.finish(active,Object.assign(Error('O PC demorou a responder. Tente novamente.'),{status:504}));
   },timeoutMs);
   child.stdin.write(JSON.stringify({...command,id:active.id})+'\n',error=>{if(error&&this.pending===active){this.discard();this.finish(active,Error('O controle de audio foi interrompido. Tente novamente.'));}});
  })).finally(()=>{this.depth--;});
  this.queue=job.catch(()=>{});return job;
 }
 close(){this.closed=true;this.discard();if(this.pending)this.finish(this.pending,Error('O controle esta encerrando.'));}
}
