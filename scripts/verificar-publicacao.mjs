import fs from 'node:fs';
import path from 'node:path';
import {execFileSync} from 'node:child_process';
import {fileURLToPath} from 'node:url';

const root=path.dirname(path.dirname(fileURLToPath(import.meta.url)));
const files=execFileSync('git',['ls-files','-z'],{cwd:root,encoding:'utf8'}).split('\0').filter(Boolean);
if(!files.length)throw Error('O indice Git esta vazio. Selecione os arquivos antes de verificar.');
const forbidden=/(?:^|\/)(?:node_modules|__pycache__|\.gradle|\.cxx|build|artifacts|mpv-portatil|analysis-libs|ud851b-rootfs|ferramentas|auditoria-cookie-[^/]+|revisao-[^/]+|validacao-audio-[^/]+)\/|(?:connection-private|connection|estado|cm6206-local|cm6206-state|cm6206-panel)\.json$|(?:^|\/)(?:endereco\.txt|local\.properties|signing\.properties|\.env[^/]*)$|backup-cadmiumconfig|\.(?:exe|dll|zip|bin|db|sqlite\d*|pyc|pid|wav|raw|pcm|spdif|apk|aab|dex|class|jks|keystore)$|\.log(?:\.|$)/i;
const tokenPatterns=[/\b(?:ghp_|gho_|github_pat_)[A-Za-z0-9_]{20,}/,/\bAKIA[0-9A-Z]{16}\b/,/\bsk-[A-Za-z0-9_-]{30,}/,/Bearer\s+[A-Za-z0-9._-]{30,}/];
const secrets=[];
const local=path.join(root,'configuracao-pc','controle-remoto','connection-private.json');
if(fs.existsSync(local)){
  const state=JSON.parse(fs.readFileSync(local,'utf8'));
  if(typeof state.bridgeKey==='string' && state.bridgeKey.length>=32)secrets.push(state.bridgeKey);
}
const issues=[];let bytes=0;
for(const file of files){
  if(forbidden.test(file)){issues.push(`${file}: categoria local/privada ou binaria`);continue;}
  if(/\.(?:jar|ac3)$/i.test(file)&&!['android-a34/gradle/wrapper/gradle-wrapper.jar','android-a34/app/src/debug/assets/fixtures/tones-51.ac3'].includes(file)){
    issues.push(`${file}: binario fora da lista de fixture/wrapper`);continue;
  }
  const data=execFileSync('git',['show',`:${file}`],{cwd:root,maxBuffer:8*1024*1024});bytes+=data.length;
  if(data.length>5*1024*1024)issues.push(`${file}: maior que 5 MiB; revisar origem`);
  const text=data.toString('utf8');
  if(tokenPatterns.some(re=>re.test(text))||secrets.some(secret=>text.includes(secret)))issues.push(`${file}: possivel credencial literal; revisar sem imprimir o valor`);
}
for(const localPath of ['configuracao-pc/controle-remoto/connection-private.json','configuracao-pc/controle-remoto/extension/connection.json','configuracao-pc/netflix-dolby51-automatico/backup-cadmiumconfig-exemplo.json','configuracao-pc/mpv-sistema-dolby.conf','configuracao-pc/equalizador-lfe.json','android-a34/artifacts/test/report.json','android-a34/local.properties','android-a34/app/build/outputs/apk/debug/app-debug.apk','android-a34/private-test.pcm','android-a34/debug.keystore']){
  try{execFileSync('git',['check-ignore','-q',localPath],{cwd:root});}catch{issues.push(`${localPath}: esperado como ignorado`);}
}
if(issues.length){process.stderr.write(issues.join('\n')+'\n');process.exitCode=1;}
else console.log(JSON.stringify({ok:true,files:files.length,bytes,credentialsPublished:false,localArtifactsPublished:false,allowedBinaries:['officialGradleWrapper','syntheticDebugAc3Fixture']}));
