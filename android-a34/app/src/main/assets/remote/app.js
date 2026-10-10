'use strict';
let token='', state=null, profile=null, revision=0, session='', pending={}, pendingArrayIndices={}, inflight=false, polling=false, operationGeneration=0;
const $=id=>document.getElementById(id), channels=['FL','FR','Central','LFE','SL','SR'];
const generalEqFrequencies=[31.5,63,125,250,500,1000,2000,4000,8000,16000];
const liveFields=new Set(['masterGain','channelTrim','generalEqEnabled','generalEqGainDb','centerToFrontGain']);
const controls=new Map(),liveQueue=new Map(),liveApplying=new Map();let liveTimer=null,activeSlider=null,liveRetryPending=false;
const meterNodes=[];
const savedSessionKey='sistema51-control-session';
function forgetSession(){try{localStorage.removeItem(savedSessionKey);}catch(ignored){}}
async function requestJson(path,options){
 const controller=new AbortController(),timer=setTimeout(()=>controller.abort(),5000);
 try{const response=await fetch(path,{...options,signal:controller.signal});const value=await response.json();return {response,value};}
 catch(error){if(error.name==='AbortError')throw Error('O A34 não respondeu. Confira o Wi-Fi e tente novamente.');throw error;}
 finally{clearTimeout(timer);}
}
const controlId=(key,index)=>key+':'+index;
const percentToGain=percent=>Math.pow(Math.max(0,Math.min(100,percent))/100,3);
const gainToPercent=gain=>100*Math.cbrt(Math.max(0,Math.min(1,gain)));
const groups=[
 ['Volume por canal',[['channelTrim','Nível (dB)',-60,12.041,.1,'db']]],
 ['Equalizador geral · 10 bandas',[['generalEqEnabled','Ativar equalizador geral'],['generalEqGainDb','Ganho (dB)',-12,6,.1,'dbValue']]],
 ['Entrada e processamento',[
  ['muted','Mute'],['bypass','Bypass'],['inputMode','Modo da entrada PCM manual'],['swapCenterLfe','Inverter central/sub na saída']]],
 ['Atraso de canais',[['delaySamples','Atraso (ms)',0,250,.020833333,'delay']]],
 ['Upmix mono/estéreo',[['upmixCenterGain','Envio à central',0,1,.01],['upmixSurroundGain','Envio às surrounds',0,1,.01],['upmixBassGain','Envio ao sub',0,1,.01],['upmixDifference','Ambiência L − R',0,1,.01],['upmixBassCutoffHz','Corte do grave (Hz)',40,160,1]]],
 ['Roteamento da central',[['centerToFrontGain','Central → frontais (%)',0,100,1,'percent']]],
 ['Cortes e graves',[['frontCrossoverEnabled','Crossover frontal'],['frontCutoffHz','Corte frontal (Hz)',40,160,1],['frontBassSend','Envio de graves frontal/central',0,1,.01],['surroundCrossoverEnabled','Crossover surround'],['surroundCutoffHz','Corte surround (Hz)',40,120,1],['surroundBassSend','Envio de graves surround',0,1,.01],['centerBassCopyEnabled','Cópia de graves central'],['centerBassCutoffHz','Corte da cópia central (Hz)',40,120,1],['centerBassSend','Envio da cópia central',0,1,.01],['lfeSubsonicEnabled','Subsônico LFE'],['lfeSubsonicHz','Corte subsônico (Hz)',10,40,1]]],
 ['Equalizador do sub',[['lfeEqEnabled','EQ LFE'],['automaticLfeHeadroom','Margem automática LFE'],['lfeHeadroom','Margem manual LFE',0,1,.01],['lfeEqFrequenciesHz','Frequência (Hz)',10,200,1],['lfeEqGainDb','Ganho (dB)',-12,6,.1,'dbValue'],['lfeEqQ','Q',.3,10,.1]]]
];
async function api(path,body){
 const requestedToken=token;
 const {response,value}=await requestJson('/api/'+path,{method:body?'POST':'GET',headers:{Authorization:'Bearer '+token,...(body?{'Content-Type':'application/json'}:{})},body:body?JSON.stringify(body):undefined});
 if(token!==requestedToken)throw Error('Controle desconectado.');
 if(response.status===401){forgetSession();token='';state=null;profile=null;pending={};pendingArrayIndices={};liveQueue.clear();liveApplying.clear();$('panel').hidden=true;$('login').hidden=false;$('message').textContent='Sessão expirada. Informe novamente o código de 4 dígitos.';throw Error(value.error||'Sessão expirada.');}
 if(!response.ok){const error=Error(value.error||value.detail||'Operação recusada ('+response.status+').');error.status=response.status;throw error;}
 return value;
}
async function unlock(code){
 const generation=operationGeneration;
 const {response,value}=await requestJson('/api/unlock',{method:'POST',headers:{'Content-Type':'application/json'},body:JSON.stringify({code})});
 if(generation!==operationGeneration)throw Error('Conexão cancelada.');
 if(!response.ok){let message=value.error||'Código recusado ('+response.status+').';if(response.status===429&&Number(value.retryAfterSeconds)>0)message+=' Aguarde '+value.retryAfterSeconds+' s.';throw Error(message);}
 token=value.sessionToken;
 try{localStorage.setItem(savedSessionKey,JSON.stringify({token,expires:Date.now()+Number(value.expiresInSeconds||43200)*1000}));}catch(ignored){}
}
function status(value){
 if(state&&value.controlSession===state.controlSession&&value.profileRevision<state.profileRevision)return;
 state=value;$('title').textContent=value.title||'DSP';$('detail').textContent=value.detail||'';
 $('connectionStatus').textContent='A34 conectado';$('connectionStatus').dataset.state='running';
 $('route').textContent=(value.inputSummary||'Sem captura')+' → '+(value.outputSummary||'Sem reprodução');
 const active=!!value.running||!!value.starting||!!value.stopping;
 if(active&&['optical','pcm'].includes(value.audioRoute))$('audioRoute').value=value.audioRoute;
 $('audioRoute').disabled=active||inflight;
 const optical=$('audioRoute').value==='optical';
 $('optical').textContent=optical?(value.opticalDetail||'Fire TV óptico: todo mono/estéreo decodificado recebe upmix; 5.1 mantém os seis canais nativos.'):'PCM Android: selecione o modo de entrada manual nos ajustes.';
 const sourceChannels=Number(value.sourceChannels||0),codec=value.sourceCodec||'';
 const policy=value.sourcePolicyAutomatic?(sourceChannels?(value.upmixActive?' · upmix para 5.1':' · canais nativos'):' · aguardando identificação'):'';
 const isMuted=!!value.runtimeMuted||!!value.profile?.muted;
 $('playbackStatus').textContent=value.runtimeError?'Interrompido':value.running?(isMuted?'Silenciado':'Em execução'):value.starting?'Iniciando':'Parado';
 $('playbackStatus').dataset.state=value.runtimeError?'error':value.running?(isMuted?'muted':'running'):'idle';
 const usb=value.nativeUsb||{},transportError=Number(usb.errno||0)||Number(usb.inputPacketErrors||0)||Number(usb.outputPacketErrors||0);
 $('transportHealth').textContent=transportError?'Falha USB':value.running&&value.nativeUsb?'Sem erros USB':'Aguardando';
 $('processingHealth').textContent=value.running&&Number.isFinite(value.processingPercent)?Number(value.processingPercent).toFixed(0)+'% do bloco':'—';
 $('queueHealth').textContent=value.running&&Number.isFinite(value.softwareQueueMs)?Number(value.softwareQueueMs).toFixed(0)+' ms':'—';
 $('source').textContent=(sourceChannels?codec+' · '+sourceChannels+' canal(is)'+policy:'Sem fonte decodificada')+(isMuted?' · saída silenciada':'');
 $('audioError').textContent=value.runtimeError||'';
 $('autoDolby').checked=!!value.autoDolbyEnabled&&!value.autoDolbyPaused;
 $('autoStatus').textContent=value.autoDolbyPaused?'Automático pausado. Reative a opção ou toque em Iniciar Fire TV.':value.autoDolbyEnabled?'A34 aguarda Dolby e retoma quando o sinal volta. Volume e mute são preservados.':'Início automático desligado.';
 if(activeSlider!=='masterGain:-1'&&!liveQueue.has('masterGain:-1')&&!liveApplying.has('masterGain:-1')){const percent=Math.round(gainToPercent(value.profile?.masterGain||0));$('masterVolume').value=String(percent);$('masterReadout').textContent=String(percent);}
 $('start').textContent=optical?'Iniciar Fire TV':'Iniciar PCM USB';
 $('start').disabled=active||!!value.busy||(optical?!value.opticalHardwareReady:!value.hardwareReady||!!value.requiresPcmRecordingPermission)||inflight;
 $('stop').disabled=inflight||(!active&&value.state!=='starting');
 $('mute').disabled=inflight||!!value.profile?.muted;
 $('mute').hidden=!!value.profile?.muted;$('unmute').hidden=!value.profile?.muted;$('unmute').disabled=inflight;
 $('diagnostics').textContent=JSON.stringify(value,null,2);
 if(!meterNodes.length){const meters=$('meters');channels.forEach(name=>{const div=document.createElement('div');div.className='meter';const b=document.createElement('b');b.textContent=name;const progress=document.createElement('progress');progress.max=1;div.append(b,progress);meters.append(div);meterNodes.push(progress);});}
 meterNodes.forEach((progress,i)=>{const level=Number(value.meters?.[i]||0);progress.value=Math.max(0,Math.min(1,(20*Math.log10(Math.max(level,1e-6))+60)/60));});
}
function editor(value,preservePending=false){
 if(state&&value.controlSession===state.controlSession&&value.profileRevision<state.profileRevision)return;
 const priorPending=preservePending?pending:{},priorIndices=preservePending?pendingArrayIndices:{};
 profile=structuredClone(value.profile);revision=value.profileRevision;session=value.controlSession;pending={};pendingArrayIndices={};
 for(const key of Object.keys(priorPending)){
  if(Array.isArray(priorPending[key])){
   const indexes=new Set(priorIndices[key]||[]),rebased=Array.isArray(profile[key])?profile[key].slice():priorPending[key].slice();
   for(const index of indexes)rebased[index]=priorPending[key][index];pending[key]=rebased;pendingArrayIndices[key]=indexes;
  }else pending[key]=priorPending[key];
 }
 if(!preservePending)$('unlock').checked=false;
 if(controls.size){syncEditor();return;}
 const fields=$('fields');fields.replaceChildren();
 for(const [groupIndex,[title,items]] of groups.entries()){
  const container=groupIndex<2?fields:document.createElement('details');
  if(groupIndex>=2){container.className='advanced-group';const summary=document.createElement('summary');summary.textContent=title;container.append(summary);fields.append(container);}
  const heading=document.createElement('h3');heading.textContent=title;container.append(heading);const grid=document.createElement('div');grid.className=groupIndex===0?'grid channel-grid':groupIndex===1?'eq-grid':'grid';container.append(grid);
  for(const [key,label,min,max,step,kind] of items){
   const values=Array.isArray(profile[key])?profile[key]:[profile[key]];
   values.forEach((baseValue,index)=>{
    const shown=Object.prototype.hasOwnProperty.call(pending,key)?(Array.isArray(pending[key])?pending[key][index]:pending[key]):baseValue;
    const wrap=document.createElement('label'),caption=document.createElement('span');
    const suffix=key==='channelTrim'||key==='delaySamples'?' · '+channels[index]:key==='generalEqGainDb'?' · '+generalEqFrequencies[index]+' Hz':values.length>1?' · banda '+(index+1):'';
    caption.textContent=label+suffix;wrap.append(caption);
    if(key==='channelTrim')caption.textContent=['Frontal esquerda','Frontal direita','Central','Subwoofer · LFE','Surround esquerda','Surround direita'][index];
    if(key==='generalEqGainDb')caption.textContent=generalEqFrequencies[index]>=1000?(generalEqFrequencies[index]/1000)+'k':String(generalEqFrequencies[index]);
    if(key==='generalEqEnabled')wrap.className='eq-toggle';
    const input=key==='inputMode'?document.createElement('select'):document.createElement('input');
    input.dataset.profileKey=key;input.dataset.profileIndex=String(index);
    input.setAttribute('aria-label',label+suffix);
    const live=liveFields.has(key),range=live&&typeof shown==='number';
    if(key==='inputMode'){for(const [v,t] of [['NATIVE_5_1','PCM 5.1 nativo'],['STEREO_UPMIX','PCM estéreo com upmix']]){const option=document.createElement('option');option.value=v;option.textContent=t;input.append(option);}input.value=shown;}
    else if(typeof baseValue==='boolean'){input.type='checkbox';input.checked=shown;}
    else{input.type=range?'range':'number';input.min=min;input.max=max;input.step=range?step:'any';input.value=String(kind==='db'?Number(shown<=0?-60:20*Math.log10(shown)).toFixed(2):kind==='delay'?Number(shown/48).toFixed(6):kind==='percent'?Number(shown*100).toFixed(0):shown);}
    let readout=null;if(range){readout=document.createElement('small');readout.textContent=kind==='percent'?Number(input.value).toFixed(0)+'%':Number(input.value).toFixed(1)+' dB';}
    if(kind==='delay'){input.disabled=true;input.className='delay';}
    const id=controlId(key,Array.isArray(profile[key])?index:-1);
    controls.set(id,{input,readout,key,index:Array.isArray(profile[key])?index:-1,kind});
    if(range)input.addEventListener('input',()=>{activeSlider=id;readout.textContent=kind==='percent'?Number(input.value).toFixed(0)+'%':Number(input.value).toFixed(1)+' dB';const next=kind==='db'?(Number(input.value)<=-60?0:Math.pow(10,Number(input.value)/20)):kind==='percent'?Number(input.value)/100:Number(input.value);queueLive(key,Array.isArray(profile[key])?index:-1,next);});
    input.addEventListener('change',()=>{
     activeSlider=null;
     let next=input.type==='checkbox'?input.checked:key==='inputMode'?input.value:Number(input.value);
     if((input.type==='number'||input.type==='range')&&(!Number.isFinite(next)||next<min||next>max||input.value.trim()==='')){input.setCustomValidity('Valor fora da faixa');input.reportValidity();return;}
     input.setCustomValidity('');if(kind==='db')next=next<=-60?0:Math.pow(10,next/20);if(kind==='delay')next=Math.round(next*48);if(kind==='percent')next=next/100;
     if(live)return commitLive(key,Array.isArray(profile[key])?index:-1,next);
     queuePending(key,Array.isArray(profile[key])?index:-1,next);
    });
    wrap.append(input);if(readout)wrap.append(readout);grid.append(wrap);
   });
  }
 }
}
function syncEditor(){
 for(const [id,{input,readout,key,index,kind}] of controls){
  if(activeSlider===id||liveQueue.has(id)||liveApplying.has(id))continue;
  const value=Object.prototype.hasOwnProperty.call(pending,key)?pending[key]:profile[key],shown=index<0?value:value[index];
  if(typeof shown==='boolean')input.checked=shown;
  else input.value=String(kind==='db'?Number(shown<=0?-60:20*Math.log10(shown)).toFixed(2):kind==='delay'?Number(shown/48).toFixed(6):kind==='percent'?Number(shown*100).toFixed(0):shown);
  if(readout)readout.textContent=kind==='percent'?Number(input.value).toFixed(0)+'%':Number(input.value).toFixed(1)+' dB';
  if(kind==='delay')input.disabled=!$('unlock').checked;
 }
}
function queuePending(key,index,next){
 if(index>=0){if(!pending[key])pending[key]=profile[key].slice();pending[key][index]=next;if(!pendingArrayIndices[key])pendingArrayIndices[key]=new Set();pendingArrayIndices[key].add(index);}
 else pending[key]=next;
}
function mergePending(base){
 const changes={};
 for(const key of Object.keys(pending)){
  if(Array.isArray(pending[key])){const merged=Array.isArray(base[key])?base[key].slice():pending[key].slice();for(const index of pendingArrayIndices[key]||[])merged[index]=pending[key][index];changes[key]=merged;}
  else changes[key]=pending[key];
 }
 return changes;
}
async function commitLive(key,index,value){
 liveRetryPending=false;
 clearTimeout(liveTimer);liveTimer=null;liveQueue.set(controlId(key,index),{key,index,value});
 return flushLive();
}
function queueLive(key,index,value){
 liveRetryPending=false;
 liveQueue.set(controlId(key,index),{key,index,value});
 if(liveTimer===null)liveTimer=setTimeout(()=>{liveTimer=null;flushLive();},100);
}
async function flushLive(){
 if(inflight||!token||!liveQueue.size)return;
 return operation(async()=>{
  while(token&&liveQueue.size){
   const batch=Array.from(liveQueue.values());liveQueue.clear();for(const item of batch)liveApplying.set(controlId(item.key,item.index),item);
   try{
    let latest=await api('state'),updated;
    for(let attempt=0;attempt<2;attempt++){
     const changes={};for(const {key,index,value} of batch){if(index>=0){if(!Array.isArray(latest.profile[key]))throw Error('O perfil mudou. Atualize o painel.');if(!changes[key])changes[key]=latest.profile[key].slice();changes[key][index]=value;}else changes[key]=value;}
     try{updated=await api('profile',{session:latest.controlSession,revision:latest.profileRevision,changes});break;}
     catch(error){if(error.status!==409||attempt!==0)throw error;const fresh=await api('state');if(fresh.controlSession!==latest.controlSession)throw error;latest=fresh;}
    }
    for(const item of batch)liveApplying.delete(controlId(item.key,item.index));status(updated);editor(updated,true);
   }catch(error){
    liveRetryPending=true;
    if(token)for(const item of batch){const id=controlId(item.key,item.index);if(!liveQueue.has(id))liveQueue.set(id,item);}
    liveApplying.clear();throw error;
   }
  }
  return 'Ajuste aplicado no DSP do A34.';
 });
}
async function refresh(){const value=await api('state');status(value);editor(value);}
async function operation(job){if(inflight)return;const generation=++operationGeneration;inflight=true;$('apply').disabled=true;if(state)status(state);try{const message=await job();if(generation!==operationGeneration)return false;$('message').textContent=typeof message==='string'?message:'Ajustes confirmados pelo A34.';return true;}catch(error){if(generation===operationGeneration)$('message').textContent=error.message;return false;}finally{inflight=false;$('apply').disabled=false;if(state)status(state);if(liveQueue.size&&token&&!liveRetryPending)Promise.resolve().then(flushLive);}}
$('masterVolume').addEventListener('input',()=>{activeSlider='masterGain:-1';$('masterReadout').textContent=$('masterVolume').value;queueLive('masterGain',-1,percentToGain(Number($('masterVolume').value)));});
$('masterVolume').addEventListener('change',()=>{activeSlider=null;return commitLive('masterGain',-1,percentToGain(Number($('masterVolume').value)));});
$('autoDolby').addEventListener('change',()=>{const enabled=$('autoDolby').checked;return operation(async()=>{const latest=await api('state');status(await api('automation',{session:latest.controlSession,enabled}));});});
$('pair').addEventListener('submit',event=>{event.preventDefault();return operation(async()=>{await unlock($('key').value.trim());await refresh();$('login').hidden=true;$('panel').hidden=false;$('key').value='';});});
 $('settings').addEventListener('submit',event=>{event.preventDefault();return operation(async()=>{const latest=await api('state'),changes=mergePending(latest.profile);const value=await api('profile',{session:latest.controlSession,revision:latest.profileRevision,changes,unlockDelays:$('unlock').checked});status(value);editor(value);});});
$('refresh').addEventListener('click',()=>operation(async()=>{await refresh();liveRetryPending=false;}));
$('unlock').addEventListener('change',()=>document.querySelectorAll('.delay').forEach(input=>input.disabled=!$('unlock').checked));
$('audioRoute').addEventListener('change',()=>{if(state)status(state);});
$('start').addEventListener('click',()=>{const route=$('audioRoute').value;const label=route==='optical'?'Fire TV óptico com decodificação e DSP':'PCM USB Android';if(confirm('Iniciar '+label+'? O volume e o mute atuais serão preservados.'))operation(async()=>status(await api('start',{session,route,confirmPlayback:true})));});
$('mute').addEventListener('click',()=>operation(async()=>{
 // Fetch the current revision so an unrelated edit cannot prevent this mute.
 // A second writer still wins a 409; no implicit retries or unmute are issued.
 const latest=await api('state');
 const value=await api('profile',{session:latest.controlSession,revision:latest.profileRevision,changes:{muted:true}});
 delete pending.muted;
 status(value);if(!Object.keys(pending).length)editor(value);else document.querySelectorAll('[data-profile-key="muted"]').forEach(input=>input.checked=true);
 return Object.keys(pending).length?'Saída silenciada. Atualize antes de aplicar os ajustes pendentes.':'Saída silenciada pelo A34.';
}));
$('stop').addEventListener('click',()=>operation(async()=>status(await api('stop',{session}))));
$('unmute').addEventListener('click',()=>operation(async()=>{const latest=await api('state');const updated=await api('profile',{session:latest.controlSession,revision:latest.profileRevision,changes:{muted:false}});delete pending.muted;status(updated);editor(updated,true);return 'Mute desativado. Volume preservado.';}));
$('disconnect').addEventListener('click',()=>{operationGeneration++;forgetSession();token='';state=null;profile=null;pending={};pendingArrayIndices={};liveQueue.clear();liveApplying.clear();activeSlider=null;$('panel').hidden=true;$('login').hidden=false;$('connectionStatus').textContent='Rede local';$('connectionStatus').dataset.state='idle';$('message').textContent='Desconectado.';});
$('export').addEventListener('click',()=>{const blob=new Blob([JSON.stringify(profile,null,2)],{type:'application/json'});const url=URL.createObjectURL(blob);const a=document.createElement('a');a.href=url;a.download='a34-perfil.json';a.click();URL.revokeObjectURL(url);});
setInterval(async()=>{if(!token||$('panel').hidden||document.hidden||inflight||polling)return;polling=true;const generation=operationGeneration;try{const value=await api('state');if(generation!==operationGeneration||inflight||!token)return;status(value);if(value.profileRevision!==revision||value.controlSession!==session){if(!Object.keys(pending).length)editor(value);else $('message').textContent='O perfil mudou em outro controle. Atualize os ajustes antes de aplicar.';}}catch(error){if(generation===operationGeneration&&token)$('message').textContent=error.message;}finally{polling=false;}},1500);

try{const saved=JSON.parse(localStorage.getItem(savedSessionKey)||'null');if(saved&&saved.expires>Date.now()&&typeof saved.token==='string'){token=saved.token;operation(async()=>{await refresh();$('login').hidden=true;$('panel').hidden=false;});}else forgetSession();}catch(ignored){forgetSession();}
