(()=>{
 if(window.__remote51Installed)return;window.__remote51Installed=true;
 let lastError=null;
 function player(){try{const api=window.netflix?.appContext?.state?.playerApp?.getAPI()?.videoPlayer;const id=api?.getAllPlayerSessionIds()?.find(id=>!id.includes('preview'))||api?.getAllPlayerSessionIds()?.[0];return id?api.getVideoPlayerBySessionId(id):null;}catch{return null;}}
 function tracks(p,type){try{return (type==='audio'?p?.getAudioTrackList?.():p?.getTimedTextTrackList?.())||[];}catch{return [];}}
 function snapshot(){const p=player(),v=document.querySelector('video'),id=location.pathname.match(/^\/watch\/(\d+)/)?.[1];if(!id||!v)return {player:null,tracks:[],subtitles:[]};
  function describe(t){return [t.displayName||t.languageDescription||t.language||'Faixa',t.isForcedNarrative?'Forçada':null,t.channels===6?'5.1':null].filter(Boolean).join(' · ');}
  let audio=p?.getAudioTrack?.(),text=p?.getTimedTextTrack?.();
  return {player:{id,title:document.title.replace(/\s*[-|]\s*Netflix.*$/,'')||'Netflix',paused:v.paused,position:v.currentTime,duration:Number.isFinite(v.duration)?v.duration:0},tracks:tracks(p,'audio').map(t=>({label:describe(t),selected:t.id===audio?.id})),subtitles:tracks(p,'subtitle').map(t=>({label:t.isNoneTrack?'Sem legendas':describe(t),selected:t.id===text?.id})),message:lastError||(!p?'A API de faixas não está disponível nesta versão do player.':null)};
 }
 window.addEventListener('message',async event=>{
  if(event.source!==window||event.origin!==location.origin||event.data?.type!=='remote51-page-command')return;
  const c=event.data.command,p=player(),v=document.querySelector('video');if(!v||c.player!==location.pathname.match(/^\/watch\/(\d+)/)?.[1])return;
  try{switch(c.action){case 'pause':p?.pause?p.pause():v.pause();break;case 'play':p?.play?p.play():await v.play();break;case 'toggle':if(v.paused){p?.play?p.play():await v.play();}else{p?.pause?p.pause():v.pause();}break;case 'seek':{const ms=Math.max(0,(v.currentTime+Number(c.seconds))*1000);p?.seek?p.seek(ms):v.currentTime=ms/1000;break;}case 'audio':case 'subtitle':{const t=tracks(p,c.action)[c.index];if(!t)throw Error('Faixa não disponível. Atualize as faixas.');if(c.action==='audio'){if(!p.setAudioTrack)throw Error('Troca de áudio indisponível.');await p.setAudioTrack(t);}else{if(!p.setTimedTextTrack)throw Error('Troca de legendas indisponível.');await p.setTimedTextTrack(t);}break;}}
   lastError=null;
  }catch(e){lastError=e.message;}
 });
 setInterval(()=>window.postMessage({type:'remote51-page-snapshot',snapshot:snapshot()},location.origin),1000);
})();
