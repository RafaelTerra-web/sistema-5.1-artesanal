# Upmix automático antes do mixer do Windows

Este pacote experimental processa o áudio do YouTube no próprio navegador. O
`MediaElementAudioSourceNode` entrega o número de canais do áudio decodificado;
um `AudioWorklet` recebe esse número em `inputs[0].length`, usando
`channelCountMode: max` e `channelInterpretation: discrete`.

- **Um ou dois canais:** gera central, LFE com passa-baixas LR4 de 120 Hz e
  surrounds. Os canais sintetizados entram gradualmente em 100 ms.
- **Seis canais:** copia os seis canais, inclusive durante passagens com apenas
  as frontais. Não usa silêncio, codec, itag ou volume para decidir.
- **Ausência de entrada ou outros layouts:** não classifica como estéreo. Outros
  layouts retornam à ligação direta do navegador, sem declarar 5.1 validado.

O número observado pertence à entrada decodificada deste nó, antes do mixer
global do Windows. `AudioNode.channelCount` isolado pode ser um valor padrão e
não serve como número original de canais. Um navegador pode já ter alterado o
layout antes desse ponto; por isso a execução real precisa ser verificada.

## Instalação e uso no Opera

1. Deixe o gerenciador CM6206 ativo na rota **PCM** com entrada **Auto** ou
   **Native**. O mixer recebe seis canais já processados pela extensão. Uma
   segunda seleção manual **Stereo** no gerenciador substituiria o 5.1 nativo.
2. Abra `opera://extensions`, ative **Modo de desenvolvedor** e escolha
   **Carregar sem compactação**. Selecione esta pasta, que contém `manifest.json`.
3. Atualize a página do YouTube com F5. Inicie o vídeo e clique em **Ativar upmix
   automático 5.1**, no canto inferior direito. Esse clique permite iniciar o
   `AudioContext` conforme a política de reprodução do navegador.
4. Confira a indicação **Upmix 2 → 5.1** em uma fonte estéreo e **5.1 nativo
   preservado** em uma fonte de seis canais. A indicação descreve o processamento
   no navegador; a audição e a captura dos canais na CM6206 continuam necessárias.

O botão alterna entre upmix automático e preservação da fonte. Mudanças de fonte
no mesmo elemento de vídeo usam o novo número de canais e reiniciam os filtros.
Se o site criar outro elemento de vídeo, ative o botão para o novo player.

A extensão não abre vídeos nem inicia testes sonoros. Não escolhe dispositivo
USB, não muda o volume do Windows e não aplica os atrasos acústicos do sistema.
O ganho e o gerenciamento de graves posteriores permanecem no gerenciador PCM.

## Pré-condições e retorno ao áudio direto

Antes de vincular o vídeo, exige saída com capacidade de pelo menos seis canais,
contexto em 48 kHz, módulo carregado e contexto em execução. Falha nessas etapas
mantém a ligação original do vídeo. Fontes de outra origem, `srcObject` e conteúdo
com `mediaKeys` são recusados antes dessa vinculação.

Um erro do processador tenta ligar a fonte diretamente ao destino de seis
canais e pede F5 para reativação. Essa ligação direta ainda passa pelo Web Audio.
Após a vinculação, uma mudança para CORS/EME pode silenciar a própria fonte
Web Audio; a extensão avisa para atualizar com **F5**. Para restaurar a ligação
original do navegador, desative a extensão e atualize a página.

O pacote não contorna DRM e não faz passthrough do bitstream AC-3/E-AC-3.
Preserva o PCM de seis canais entregue pelo decodificador. A rota óptica/AC-3
do sistema é uma implementação separada.

## Dados e validação

Não possui permissões de rede, cookies, histórico, processo em segundo plano ou
serviço de coleta. Um estado temporário na própria página informa número de
canais, modo, sessão da página e geração da fonte. Não inclui URLs de mídia,
identificadores de conta nem amostras PCM.

Os testes de núcleo e de integração simulada podem ser executados sem áudio:

```powershell
node --test configuracao-pc/browser-upmix-extensao.test.cjs
```

Eles verificam preservação de seis canais, inclusive dez segundos só nas
frontais, transição estéreo → nativo, ganhos, filtros, blocos de tamanhos
diferentes e falhas anteriores à vinculação. Esses testes não comprovam ainda
o comportamento do Opera, a política CORS do vídeo atual ou a saída física.

Referências: [MediaElementAudioSourceNode e segurança CORS](https://www.w3.org/TR/webaudio-1.1/#MediaElementAudioSourceNode),
[entrada do AudioWorkletProcessor](https://www.w3.org/TR/webaudio-1.1/#dom-audioworkletprocessor-process)
e [capacidade do destino](https://developer.mozilla.org/en-US/docs/Web/API/AudioDestinationNode/maxChannelCount).
