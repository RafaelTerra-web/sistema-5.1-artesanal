let configPromise;
function config(){return configPromise||=fetch(chrome.runtime.getURL('connection.json')).then(r=>r.json());}
chrome.runtime.onMessage.addListener((message,sender,reply)=>{
 if(message?.type!=='remote51-snapshot'||!sender.url?.startsWith('https://www.netflix.com/'))return;
 (async()=>{try{const c=await config();const r=await fetch(c.origin+'/bridge',{method:'POST',headers:{'Content-Type':'application/json','X-Bridge-Key':c.key},body:JSON.stringify(message.snapshot)});if(!r.ok)throw Error('Ponte indisponivel');reply(await r.json());}catch{reply({commands:[]});}})();return true;
});
