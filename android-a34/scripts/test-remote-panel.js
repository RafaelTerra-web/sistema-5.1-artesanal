'use strict';
// Exercise route guards, immediate mute and response ordering without starting audio.
const assert=require('node:assert/strict');
const fs=require('node:fs');
const path=require('node:path');
const vm=require('node:vm');
const nodes=new Map();
function node(){return {value:'',textContent:'',disabled:false,hidden:false,checked:false,dataset:{},listeners:{},children:[],
 append(...children){this.children.push(...children);},replaceChildren(...children){this.children=children;},setAttribute(){},setCustomValidity(){},reportValidity(){},addEventListener(type,handler){this.listeners[type]=handler;}};}
const mutedInput=node();
const callbacks=[];
const context=vm.createContext({
 document:{getElementById(id){if(!nodes.has(id))nodes.set(id,node());return nodes.get(id);},
 createElement(){return node();},querySelectorAll(selector){return selector.includes('muted')?[mutedInput]:[];}},
 setInterval(callback){callbacks.push(callback);},setTimeout,clearTimeout,structuredClone,Blob,URL,AbortController,localStorage:{getItem(){return null;},setItem(){},removeItem(){}},confirm(){return true;},
 fetch(){throw Error('Unexpected request');}
});
vm.runInContext(fs.readFileSync(path.join(__dirname,'../app/src/main/assets/remote/app.js'),'utf8'),context);
const evaluate=code=>vm.runInContext(code,context);
const profileFixture={masterGain:.08,muted:false,bypass:false,inputMode:'NATIVE_5_1',channelTrim:[1,1,1,1,1,1],delaySamples:[0,0,0,0,0,0],
 upmixCenterGain:1,upmixSurroundGain:.5,upmixBassGain:.5,upmixDifference:0,upmixBassCutoffHz:120,
 frontCrossoverEnabled:false,frontCutoffHz:90,frontBassSend:1,surroundCrossoverEnabled:false,surroundCutoffHz:90,surroundBassSend:1,
 centerBassCopyEnabled:false,centerBassCutoffHz:120,centerBassSend:1,centerToFrontGain:.35,lfeSubsonicEnabled:false,lfeSubsonicHz:20,swapCenterLfe:false,
 generalEqEnabled:true,generalEqGainDb:Array(10).fill(0),lfeEqEnabled:true,automaticLfeHeadroom:false,lfeHeadroom:1/3,
 lfeEqFrequenciesHz:[20,25,30,40,50,60,80,100,120],lfeEqGainDb:[0,0,0,0,0,0,0,0,0],lfeEqQ:Array(9).fill(2)};
const snapshot=(revision,values={})=>{const {profile={},...rest}=values;return {controlSession:'same-session',profileRevision:revision,profile:{...profileFixture,...profile},
 hardwareReady:true,opticalHardwareReady:true,requiresPcmRecordingPermission:true,running:false,
 title:'Ready',state:'ready',meters:[0,0,0,0,0,0],...rest};};
function publish(value){context.testValue=value;evaluate('status(testValue)');}
function findInputs(key){const found=[];const visit=value=>{if(value?.dataset?.profileKey===key)found.push(value);for(const child of value?.children||[])visit(child);};visit(nodes.get('fields'));return found;}
async function run(){
 // The four-digit code is exchanged without Authorization for an ephemeral API session.
 evaluate("$('key').value='0042'");
 const loginRequests=[];
 context.fetch=async(url,options)=>{
  loginRequests.push({url,options});
  if(url==='/api/unlock')return {ok:true,status:200,json:async()=>({sessionToken:'random-session-token',expiresInSeconds:43200})};
  return {ok:true,status:200,json:async()=>snapshot(1)};
 };
 await nodes.get('pair').listeners.submit({preventDefault(){}});
 assert.equal(loginRequests[0].url,'/api/unlock');
 assert.equal(loginRequests[0].options.headers.Authorization,undefined,'PIN is not reused as the API bearer token');
 assert.deepEqual(JSON.parse(loginRequests[0].options.body),{code:'0042'});
 assert.equal(loginRequests[1].options.headers.Authorization,'Bearer random-session-token');
 assert.equal(nodes.get('login').hidden,true);assert.equal(nodes.get('panel').hidden,false);
 assert.equal(nodes.get('key').value,'');
 assert.equal(nodes.get('masterVolume').value,'43','master shows the saved gain on its perceptual zero-to-100 scale');
 assert.equal(evaluate('percentToGain(0)'),0);assert.equal(evaluate('percentToGain(100)'),1);
 assert.equal(evaluate('percentToGain(50)'),.125);

 // Channel and graphic EQ sliders commit on release, while preserving other channels.
 let serverProfile={...profileFixture,channelTrim:profileFixture.channelTrim.slice(),generalEqGainDb:profileFixture.generalEqGainDb.slice(),lfeEqGainDb:profileFixture.lfeEqGainDb.slice()};let serverRevision=1;
 const liveRequests=[];
 context.fetch=async(url,options)=>{
  liveRequests.push({url,options,body:options.body?JSON.parse(options.body):null});
  if(url==='/api/state')return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};
  for(const [key,value] of Object.entries(JSON.parse(options.body).changes))serverProfile[key]=value;
  serverRevision++;
  return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};
 };
 nodes.get('masterVolume').value='50';await nodes.get('masterVolume').listeners.change();
 assert.equal(serverProfile.masterGain,.125,'master slider writes the DSP gain');
 assert.equal(findInputs('channelTrim').length,6,'a separate channel volume slider is rendered for every output');
 for(let channel=0;channel<6;channel++){
  const before=serverProfile.channelTrim.slice(),channelSlider=findInputs('channelTrim')[channel];assert.equal(channelSlider.type,'range');channelSlider.value=String(-6+channel);await channelSlider.listeners.change();
  assert.ok(Math.abs(serverProfile.channelTrim[channel]-Math.pow(10,(-6+channel)/20))<1e-6,'channel '+channel+' commits its own dB value');
  for(let other=0;other<6;other++)if(other!==channel)assert.equal(serverProfile.channelTrim[other],before[other],'editing one channel preserves the others');
 }
 const eqSlider=findInputs('generalEqGainDb')[5];assert.equal(eqSlider.type,'range');eqSlider.value='3';await eqSlider.listeners.change();
 assert.equal(serverProfile.generalEqGainDb[5],3,'graphic EQ stores the entered dB value');
 const centerToFront=findInputs('centerToFrontGain')[0];assert.equal(centerToFront.type,'range');assert.equal(centerToFront.value,'35','center routing slider uses a readable percentage scale');centerToFront.value='62';await centerToFront.listeners.change();
 assert.equal(serverProfile.centerToFrontGain,.62,'center routing slider persists linear gain without attenuating the center channel');
 const lfeGain=findInputs('lfeEqGainDb')[4];lfeGain.value='3';await lfeGain.listeners.change();
 assert.equal(evaluate('pending.lfeEqGainDb[4]'),3,'LFE EQ dB is not converted as linear volume');
 serverProfile.lfeEqGainDb[0]=-4;
 await nodes.get('settings').listeners.submit({preventDefault(){}});
 assert.equal(liveRequests.at(-1).body.changes.lfeEqGainDb[0],-4,'pending EQ edit preserves another remote band');
 assert.equal(liveRequests.at(-1).body.changes.lfeEqGainDb[4],3);

 // A second slider change received during a slow command must be retained.
 let completeState;let delayed=true;
 context.fetch=async(url,options)=>{
  if(url==='/api/state'&&delayed){delayed=false;return new Promise(resolve=>{completeState=()=>resolve({ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})});});}
  if(url==='/api/state')return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};
  Object.assign(serverProfile,JSON.parse(options.body).changes);serverRevision++;
  return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};
 };
 const first=findInputs('channelTrim')[0];first.value='-8';const applying=first.listeners.change();
 const second=findInputs('channelTrim')[3];second.value='-2';await second.listeners.change();
 completeState();await applying;
 assert.ok(Math.abs(serverProfile.channelTrim[0]-Math.pow(10,-8/20))<1e-6);
 assert.ok(Math.abs(serverProfile.channelTrim[3]-Math.pow(10,-2/20))<1e-6,'a busy request does not drop the last LFE gesture');

 nodes.get('audioRoute').value='optical';publish(snapshot(serverRevision+1));
 assert.equal(nodes.get('start').disabled,false,'optical capture does not ask for microphone permission');
 nodes.get('audioRoute').value='pcm';publish(snapshot(serverRevision+2));
 assert.equal(nodes.get('start').disabled,true,'Android PCM retains its recording permission guard');
 publish(snapshot(serverRevision+3,{running:true,audioRoute:'optical',sourceCodec:'AC3',sourceChannels:2,sourcePolicyAutomatic:true,upmixActive:false,runtimeMuted:true}));
 assert.equal(nodes.get('audioRoute').disabled,true);
 assert.match(nodes.get('source').textContent,/canais nativos.*silenciada/);
 assert.equal(nodes.get('mute').disabled,false,'temporary pipeline silence does not disable a persistent mute');
 publish(snapshot(1,{runtimeMuted:false}));
 assert.match(nodes.get('source').textContent,/silenciada/,'an older profile response cannot undo newer telemetry');

 // A poll already in flight must not replace the acknowledgement of a later command.
 evaluate("token='private-test-token';$('panel').hidden=false");
 const acknowledgedRevision=serverRevision+5;
 let finishPoll;
 context.fetch=()=>new Promise(resolve=>{finishPoll=resolve;});
 const oldPoll=callbacks[0]();
 context.commandState=snapshot(acknowledgedRevision,{profile:{muted:true}});
 await evaluate('operation(async()=>status(commandState))');
 finishPoll({ok:true,json:async()=>snapshot(acknowledgedRevision-1,{profile:{muted:false}})});
 await oldPoll;
 assert.equal(evaluate('state.profile.muted'),true,'a stale poll cannot reverse an acknowledged mute');

 // Immediate mute changes only mute, preserving unrelated edits and their conflict guard.
 evaluate("pending={muted:false,masterGain:0.02};revision=2;session='same-session'");
 const requests=[];
 context.fetch=async(url,options)=>{
  requests.push({url,body:options.body?JSON.parse(options.body):null});
  return {ok:true,json:async()=>snapshot(url.endsWith('/state')?3:4,{profile:{muted:true}})};
 };
 await nodes.get('mute').listeners.click();
 assert.equal(requests.length,2);
 assert.deepEqual(requests[1].body.changes,{muted:true});
 assert.equal(evaluate('pending.muted'),undefined,'a previously queued unmute is discarded by immediate mute');
 assert.equal(evaluate('pending.masterGain'),0.02);
 assert.equal(evaluate('revision'),2,'other pending edits keep their stale-write conflict guard');
 assert.equal(mutedInput.checked,true);
 assert.match(nodes.get('message').textContent,/Saída silenciada.*Atualize/);
 publish(snapshot(acknowledgedRevision+1,{runtimeError:'USB disconnected'}));
 assert.equal(nodes.get('audioError').textContent,'USB disconnected');
 // Concurrent writers rebase a live gesture once, preserving the other channel.
 evaluate('state=null;pending={};pendingArrayIndices={};liveQueue.clear();liveApplying.clear();liveRetryPending=false');
 let conflict=true;serverRevision=100;
 context.fetch=async(url,options)=>{
  if(url==='/api/state')return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};
  if(conflict){conflict=false;serverProfile.channelTrim[1]=.4;serverRevision++;return {ok:false,status:409,json:async()=>({error:'Concurrent edit'})};}
  Object.assign(serverProfile,JSON.parse(options.body).changes);serverRevision++;
  return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};
 };
 first.value='-9';await first.listeners.change();
 assert.ok(Math.abs(serverProfile.channelTrim[0]-Math.pow(10,-9/20))<1e-6);
 assert.equal(serverProfile.channelTrim[1],.4,'conflict retry preserves the other writer');
 // A failed network write retains the intent and does not spin retry requests.
 let writes=0;
 context.fetch=async(url)=>{if(url==='/api/state')return {ok:true,status:200,json:async()=>snapshot(serverRevision,{profile:serverProfile})};writes++;throw Error('Network unavailable');};
 first.value='-10';await first.listeners.change();
 assert.equal(writes,1);assert.equal(evaluate('liveRetryPending'),true);
 assert.equal(evaluate('liveQueue.has("channelTrim:0")'),true);
 assert.equal(first.value,'-10','unacknowledged preview must not revert to stale server value');
 // Disconnect during login cannot reopen the panel when the old response arrives.
 await nodes.get('disconnect').listeners.click();
 let finishLogin;context.fetch=()=>new Promise(resolve=>{finishLogin=resolve;});
 const lateLogin=nodes.get('pair').listeners.submit({preventDefault(){}});
 await nodes.get('disconnect').listeners.click();
 finishLogin({ok:true,status:200,json:async()=>({sessionToken:'late-token',expiresInSeconds:43200})});await lateLogin;
 assert.equal(evaluate('token'),'');assert.equal(nodes.get('panel').hidden,true);
 console.log('Remote panel: four-digit unlock, six channel sliders, general EQ, center-to-front slider, route permissions, source policy, mute and response ordering passed.');
}
run().catch(error=>{console.error(error);process.exitCode=1;});
