window.addEventListener('message',event=>{
 if(event.source!==window||event.origin!==location.origin||event.data?.type!=='remote51-page-snapshot')return;
 chrome.runtime.sendMessage({type:'remote51-snapshot',snapshot:event.data.snapshot},response=>{
  if(chrome.runtime.lastError)return;
  for(const command of response?.commands||[])window.postMessage({type:'remote51-page-command',command},location.origin);
 });
});
