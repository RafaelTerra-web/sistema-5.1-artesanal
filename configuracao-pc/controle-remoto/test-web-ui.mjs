// Executes the real UI script with an isolated DOM and HTTP responses.
// No browser, Windows audio, Jellyfin session or user credentials are touched.
import test from 'node:test';
import assert from 'node:assert/strict';
import {readFileSync} from 'node:fs';
import vm from 'node:vm';

const html = readFileSync(new URL('./web/index.html', import.meta.url), 'utf8');
const source = readFileSync(new URL('./web/app.js', import.meta.url), 'utf8');
const jobs = async () => { for (let i = 0; i < 6; i++) await new Promise(resolve => setImmediate(resolve)); };
const deferred = () => { let resolve, reject; const promise = new Promise((a,b) => {resolve=a;reject=b;}); return {promise,resolve,reject}; };
const fixture = () => ({volume:80, muted:false, audioRunning:true, audioStatus:{Perfil:'Fidelidade',UpmixAutomatico:true,PlayerId:1}, sessions:[{id:'win1',app:'Opera',title:'Vídeo',state:'Playing',position:5,duration:100,canPlay:true,canPause:true,canSeek:true,current:true}],jellySessions:[],jellyConnected:false,netflix:null});

class Element {
  constructor(id='') { this.id=id; this.hidden=false; this.disabled=false; this.dataset={}; this.children=[]; this.options=[]; this._value=''; this.textContent=''; this.attributes={}; this.handlers={}; this.classes=new Set(); this.classList={contains:name=>this.classes.has(name),add:name=>this.classes.add(name),remove:name=>this.classes.delete(name),toggle:(name,on)=>on?this.classes.add(name):this.classes.delete(name)}; }
  get value() {return this._value;}
  set value(v) {this._value=String(v);}
  replaceChildren(...children) {this.children=children;this.options=children;if(!children.some(x=>x.value===this.value))this.value=children[0]?.value||'';}
  setAttribute(name,value) {this.attributes[name]=String(value);}
  addEventListener(name,handler) {this.handlers[name]=handler;}
  querySelector() {return this.button||(this.button=new Element('formButton'));}
  setPointerCapture() {}
  focus() {}
}

async function ui(initial=fixture(),preferences={}) {
  const elements = new Map([...html.matchAll(/id="([^"]+)"/g)].map(match=>[match[1],new Element(match[1])]));
  elements.get('volume').value='100';
  const nav = [...html.matchAll(/data-nav="([^"]+)"/g)].map(match=>{const action=match[1],e=new Element(action);e.dataset.nav=action;if(['up','left','right','down'].includes(action))e.classes.add('direction');return e;});
  const profiles = ['Fidelidade','Estavel'].map(profile=>{const e=new Element(profile);e.dataset.profile=profile;return e;});
  const calls=[], routes=new Map(), storage=new Map(Object.entries(preferences).map(([key,value])=>['remote51.'+key,value])), timers=new Map();let timerId=0,now=Date.now();
  class TestDate extends Date { constructor(...args) {super(...(args.length?args:[now]));} static now(){return now;} }
  routes.set('/api/status',()=>initial);
  const document={hidden:false,activeElement:null,getElementById:id=>elements.get(id),createElement:()=>new Element(),querySelectorAll:selector=>selector==='[data-nav]'?nav:selector==='[data-profile]'?profiles:[],querySelector:()=>new Element(),addEventListener() {}};
  const context=vm.createContext({document,navigator:{vibrate(){}},window:{addEventListener(){}},localStorage:{getItem:key=>storage.get(key)||null,setItem:(key,value)=>storage.set(key,String(value))},AbortSignal,Date:TestDate,console,setTimeout:(fn,ms)=>{const id=++timerId;timers.set(id,{fn,ms});return id;},clearTimeout:id=>timers.delete(id),fetch:async(route,options)=>{const body=options.body?JSON.parse(options.body):undefined;calls.push({route,body});const handler=routes.get(route);if(!handler)throw Error(`Unexpected test route ${route}`);const value=await handler(body);return {ok:true,status:200,json:async()=>value};}});
  vm.runInContext(source,context,{filename:'app.js'});
  await jobs();
  return {context,e:id=>elements.get(id),calls,routes,nav,timers,advance:ms=>{now+=ms;},run:code=>vm.runInContext(code,context),choose(value){elements.get('target').value=value;elements.get('target').onchange();}};
}

test('volume serializes rapid changes and sends the latest value after the in-flight request', async()=>{
  const app=await ui();const first=deferred();let count=0;
  app.routes.set('/api/volume',()=>++count===1?first.promise:{AppliedLive:true});
  app.e('volume').value='60';app.e('volume').oninput();const flushing=app.run('flushVolume()');await jobs();
  app.e('volume').value='25';app.e('volume').oninput();app.e('mute').onclick();await jobs();
  assert.equal(app.calls.filter(x=>x.route==='/api/volume').length,1);
  first.resolve({AppliedLive:true});await flushing;await jobs();
  assert.deepEqual(app.calls.filter(x=>x.route==='/api/volume').map(x=>x.body),[{percent:60,muted:false},{percent:25,muted:true}]);
});

test('holding a direction queues at most one repeat and release removes that repeat', async()=>{
  const data=fixture();data.jellyConnected=true;data.jellySessions=[{id:'jellyA',device:'PC',app:'Jellyfin',title:'Sem vídeo'}];
  const app=await ui(data), pending=deferred();app.routes.set('/api/jellyfin/command',()=>pending.promise);
  app.run("navigate('up')");for(let i=0;i<100;i++)app.run("navigate('up',true)");
  assert.equal(app.run('navigationQueue.length'),1);
  app.run('stopRepeat()');assert.equal(app.run('navigationQueue.length'),0);
  pending.resolve({success:true});await jobs();assert.equal(app.calls.filter(x=>x.route==='/api/jellyfin/command').length,1);
});

test('a late track response from a previously selected player cannot replace the current player tracks', async()=>{
  const data=fixture();data.jellyConnected=true;data.jellySessions=['jellyA','jellyB'].map(id=>({id,device:id,app:'Jellyfin',title:id,itemId:id,duration:100}));
  const app=await ui(data), a=deferred(), b=deferred();app.routes.set('/api/jellyfin/tracks',body=>body.session==='jellyA'?a.promise:b.promise);
  app.choose('jelly:jellyA');app.choose('jelly:jellyB');
  b.resolve({audio:[{index:2,label:'Português B',selected:true}],subtitles:[]});await jobs();
  a.resolve({audio:[{index:1,label:'Áudio A',selected:true}],subtitles:[]});await jobs();
  assert.equal(app.e('audioTrack').options[0].textContent,'Português B');assert.equal(app.e('audioTrack').value,'2');
});

test('Netflix tracks discovered after the first heartbeat become available without manual refresh', async()=>{
  const data=fixture();data.netflix={player:{id:'episode1',title:'Episódio',paused:false,duration:100},tracks:[],subtitles:[]};
  const app=await ui(data);app.choose('netflix:bridge');await jobs();assert.equal(app.e('audioTrack').disabled,true);
  data.netflix.tracks=[{index:0,label:'Português 5.1',selected:true}];
  await app.run('poll()');await jobs();
  assert.equal(app.e('audioTrack').disabled,false);assert.equal(app.e('audioTrack').options[0].textContent,'Português 5.1');
});

test('a delayed status started before a volume adjustment cannot restore the old slider value', async()=>{
  const old=fixture(), app=await ui(old), pending=deferred();
  app.routes.set('/api/status',()=>pending.promise);app.routes.set('/api/volume',()=>({AppliedLive:true}));
  const polling=app.run('poll()');await jobs();
  app.e('volume').value='25';app.e('volume').oninput();await app.run('flushVolume()');
  app.advance(2000);pending.resolve(old);await polling;await jobs();
  assert.equal(app.e('volume').value,'25');
});

test('track selectors are blocked during a track command rather than silently dropping a second change', async()=>{
  const data=fixture();data.netflix={player:{id:'episode1',title:'Episódio',paused:false,duration:100},tracks:[{index:0,label:'Português',selected:true},{index:1,label:'Inglês'}],subtitles:[{index:0,label:'Sem legendas',selected:true},{index:1,label:'Português'}]};
  const app=await ui(data), pending=deferred();app.routes.set('/api/netflix/command',()=>pending.promise);app.choose('netflix:bridge');await jobs();
  app.e('audioTrack').value='1';app.e('audioTrack').onchange();await jobs();
  assert.equal(app.e('subtitleTrack').disabled,true);
  pending.resolve({success:true});await jobs();
  assert.equal(app.e('subtitleTrack').disabled,false);
});

const netflixWindow = {id:'window:abcdef:100:123456',title:'Netflix',app:'msedge',current:false};
test('PCM diagnostics distinguish native input and confirmed output without claiming audible boxes', async()=>{
  const data=fixture();data.audioStatus={Modo:'Pcm',InputMode:'Native',Estado:'Ligado - PCM USB',Ligado:true,Solicitado:true,PlayerId:1};
  const app=await ui(data);
  assert.equal(app.e('audioState').textContent,'Ligado - PCM USB');
  assert.equal(app.e('routePcm').attributes['aria-pressed'],'true');
  assert.equal(app.e('upmixNative').attributes['aria-pressed'],'true');
  assert.equal(app.e('upmixStereo').attributes['aria-pressed'],'false');
  assert.equal(app.e('diagnostics').children.map(x=>x.textContent).includes('Saída WASAPI confirmada'),true);
  assert.equal(app.e('audioState').textContent.includes('nas seis caixas'),false);
});

test('route and source controls send distinct commands rather than inferring stereo from silence', async()=>{
  const app=await ui();app.routes.set('/api/audio',()=>({accepted:true}));
  for (const id of ['routePcm','routeOptical','upmixStereo','upmixNative']) {app.e(id).onclick();await jobs();}
  assert.deepEqual(app.calls.filter(x=>x.route==='/api/audio').map(x=>x.body.action),['Pcm','Optical','Stereo','Nativo']);
});

test('delay display shows the active session values including the requested LFE delay', async()=>{
  const data=fixture();data.audioStatus={Modo:'Pcm',InputMode:'Stereo',Ligado:true,AtrasosMs:{FL:76.8,FR:76.8,CEN:5.8,LFE:5.8,SL:71,SR:71}};
  const app=await ui(data);
  assert.equal(app.e('delayState').textContent,'Atrasos aplicados na rota ativa.');
  assert.equal(app.e('delayFront').textContent,'76,8 ms');assert.equal(app.e('delayCenter').textContent,'5,8 ms');
  assert.equal(app.e('delayLfe').textContent,'5,8 ms');assert.equal(app.e('delaySurround').textContent,'71,0 ms');
  data.audioStatus.AtrasosMs={FL:16.2,FR:18.3,CEN:6.4,LFE:4.5,SL:70.1,SR:69.2};
  await app.run('poll()');
  assert.equal(app.e('delayFront').textContent,'16,2 / 18,3 ms');
  assert.equal(app.e('delayLfe').textContent,'4,5 ms');assert.equal(app.e('delaySurround').textContent,'70,1 / 69,2 ms');
});

test('missing or invalid delay metadata cannot advertise preset delays as applied', async()=>{
  const data=fixture();const app=await ui(data);
  assert.equal(app.e('delayState').textContent,'Aguardando valores confirmados nesta rota.');
  assert.equal(app.e('delayLfe').textContent,'—');
  data.audioStatus.AtrasosMs={FL:76.8,FR:76.8,CEN:5.8,LFE:5.8,SL:71,SR:-1};await app.run('poll()');
  assert.equal(app.e('delayLfe').textContent,'—');
  data.audioStatus.AtrasosMs.SR=71;data.audioRunning=false;await app.run('poll()');
  assert.equal(app.e('delayState').textContent,'Atrasos configurados nesta sessão.');
});

test('Netflix catalog navigation and playback work without a media session or extension', async()=>{
  const data=fixture();data.sessions=[];data.windows=[netflixWindow];
  const app=await ui(data);app.routes.set('/api/input',()=>({accepted:true}));
  assert.equal(app.e('navTarget').value,'app:netflix');
  assert.equal(app.e('playPause').disabled,false);
  assert.equal(app.nav.find(x=>x.dataset.nav==='tab').disabled,false);
  app.run("navigate('tab')");await jobs();app.e('playPause').onclick();await jobs();
  app.e('forward').onclick();await jobs();
  assert.deepEqual(app.calls.filter(x=>x.route==='/api/input').map(x=>x.body),[
    {window:netflixWindow.id,action:'tab',mode:'mouse'},
    {window:netflixWindow.id,action:'space',mode:'keyboard'},
    {window:netflixWindow.id,action:'forward',mode:'keyboard'}
  ]);
});

test('mouse mode clicks and typed search text are sent only to the selected window', async()=>{
  const data=fixture();data.windows=[netflixWindow];const app=await ui(data);
  app.routes.set('/api/input',()=>({accepted:true}));
  app.e('navMode').value='mouse';app.e('navMode').onchange();
  assert.equal(app.e('touchpad').hidden,false);assert.equal(app.e('textForm').hidden,false);
  app.run("navigate('select')");await jobs();
  app.e('remoteText').value='ação e aventura';await app.e('textForm').onsubmit({preventDefault(){}});
  assert.deepEqual(app.calls.filter(x=>x.route==='/api/input').map(x=>x.body),[
    {window:netflixWindow.id,action:'select',mode:'mouse'},
    {window:netflixWindow.id,action:'text',mode:'keyboard',text:'ação e aventura'}
  ]);
});

test('queued clicks retain their target and changing windows clears pending repeats', async()=>{
  const data=fixture();data.windows=[netflixWindow,{id:'window:aaaa:200:234567',title:'Outro aplicativo',app:'other',current:true}];
  const app=await ui(data),pending=deferred();let count=0;
  app.routes.set('/api/input',()=>++count===1?pending.promise:{accepted:true});
  app.choose('win:win1');
  app.run("navigate('up')");app.run("navigate('down',true)");
  app.e('navTarget').value=data.windows[1].id;app.e('navTarget').onchange();
  assert.equal(app.e('target').value,'');assert.equal(app.run('chosen().window'),data.windows[1].id);
  app.run("navigate('select')");pending.resolve({accepted:true});await jobs();
  const requests=app.calls.filter(x=>x.route==='/api/input');
  assert.equal(requests.length,2);assert.equal(requests[0].body.window,netflixWindow.id);
  assert.equal(requests[1].body.window,data.windows[1].id);assert.equal(requests[1].body.action,'select');
  assert.equal(app.nav.find(x=>x.dataset.nav==='skipintro').disabled,true);
});

test('a saved window from a previous Netflix launch reconnects and routes transport away from Opera', async()=>{
  const data=fixture();data.windows=[netflixWindow];
  const app=await ui(data,{nav:'window:dead:100:123',target:'win:win1',navMode:'keyboard'});
  app.routes.set('/api/input',()=>({accepted:true}));
  assert.equal(app.e('navTarget').value,'app:netflix');assert.equal(app.e('target').value,'');
  assert.equal(app.e('navMode').value,'mouse');assert.equal(app.e('touchpad').hidden,false);
  app.e('playPause').onclick();await jobs();
  assert.equal(app.calls.filter(x=>x.route==='/api/input').at(-1).body.window,netflixWindow.id);
  data.windows=[{...netflixWindow,id:'window:bbbbbb:200:999999'}];
  await app.run('poll()');app.e('forward').onclick();await jobs();
  assert.equal(app.calls.filter(x=>x.route==='/api/input').at(-1).body.window,data.windows[0].id);
});

test('Controlar Netflix switches navigation and playback together from a Jellyfin session', async()=>{
  const data=fixture();data.windows=[netflixWindow];data.jellySessions=[{id:'jellyA',device:'PC',title:'Outro filme',itemId:'1'}];
  const app=await ui(data,{nav:'jellyA',target:'jelly:jellyA'});
  app.routes.set('/api/input',()=>({accepted:true}));app.e('controlNetflix').onclick();
  app.run("navigate('select')");await jobs();
  assert.equal(app.e('navTarget').value,'app:netflix');assert.equal(app.e('target').value,'');
  assert.deepEqual(app.calls.filter(x=>x.route==='/api/input').at(-1).body,{window:netflixWindow.id,action:'select',mode:'mouse'});
});
