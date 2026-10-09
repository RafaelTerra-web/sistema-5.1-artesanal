# Upmix automático antes do mixer do Windows

Este pacote experimental processa o áudio do YouTube no próprio navegador. O
`MediaElementAudioSourceNode` entrega o número de canais do áudio decodificado;
um `AudioWorklet` recebe esse número em `inputs[0].length`, usando
`channelCountMode: max` e `channelInterpretation: discrete`.

- **Um ou dois canais com codec não Dolby confirmado na faixa selecionada:**
  gera central, LFE com passa-baixas LR4 de 120 Hz e surrounds. Os canais
  sintetizados entram gradualmente em 100 ms.
- **AC-3 ou E-AC-3/DD+:** preserva o PCM recebido, mesmo quando possui só um ou
  dois canais. Não sintetiza central, LFE ou surrounds.
- **Codec desconhecido, informação ausente ou seleção ambígua:** preserva o
  PCM e mostra **falta confirmar codec selecionado**. Não estima o codec pelo
  número de canais, título, itag isolado, volume ou energia dos canais.
- **Origem mono/estéreo confirmada, codec não Dolby e seis slots PCM do navegador:**
  pode gerar upmix a partir de FL/FR. Alguns navegadores já entregam estéreo
  preenchido com slots extras; a permissão vem dos canais da faixa selecionada,
  nunca do silêncio ou da energia nesses slots.
- **Origem de seis canais ou origem desconhecida com seis slots PCM:** copia os
  seis canais, inclusive durante passagens só nas frontais. Se a origem não foi
  confirmada, mostra **PCM 6 preservado — origem não confirmada**, sem afirmar que
  o stream original é 5.1.
- **Ausência de entrada ou outros layouts:** não classifica como estéreo. Outros
  layouts retornam à ligação direta do navegador, sem declarar 5.1 validado.

O número observado pertence à entrada PCM deste nó, antes do mixer global do
Windows. Ele pode conter slots acrescentados pelo navegador e, isoladamente,
não comprova o número original de canais. `AudioNode.channelCount` isolado
também pode ser um valor padrão. A execução real precisa ser verificada.
Se a confirmação fornecer `audioChannels` do player ou `channels` no MIME maior
que dois, a extensão também preserva a entrada, mesmo se o navegador a entregar
em estéreo. Quando esse metadado estiver ausente, só permite upmix com um/dois
canais PCM recebidos pelo worklet e codec não Dolby confirmado. Se recebe seis
slots, preserva até confirmar os canais originais. Não recupera canais perdidos
em um downmix anterior.

## Confirmação do codec da faixa em reprodução

O campo tipado `originalAudioCodec` descreve o codec do stream selecionado antes
da decodificação, não o codec do arquivo que o autor enviou ao YouTube.

O caminho preferido registra os MIME declarados em `MediaSource.addSourceBuffer`
desde `document_start` e associa o objeto `MediaSource` ao blob exato do vídeo
através de `URL.createObjectURL`. Consulta somente `activeSourceBuffers` dessa
fonte e exige um único buffer de áudio com codec conhecido; buffers oferecidos
mas inativos não autorizam upmix. `changeType`, remoção de buffer e alterações
na lista ativa revogam a confirmação anterior. O histórico de MIME do buffer é
mantido: trocar AC-3 por Opus no mesmo buffer continua desconhecido, pois frames
Dolby já armazenados podem continuar em reprodução. Um novo buffer/fonte permite
uma nova confirmação. As chamadas conservam retorno, assinatura e exceções do
navegador; não alteram appends, downloads ou conteúdo da mídia.

Quando não há uma fonte MSE associada, a página usa um fallback que consulta
apenas os métodos locais e de leitura `getStatsForNerds`,
`getPlayerResponse` e, quando disponível, `getAudioTrack`/`getVideoData` do player.
Exige identidade do vídeo atual e seleção de áudio nas estatísticas (`afmt` ou
codec com seu identificador), cruza essa seleção com o MIME explícito do formato
correspondente e resolve a faixa atual se houver múltiplas faixas com o mesmo
identificador. A lista `adaptiveFormats` sozinha não confirma a reprodução.
Um estado MSE associado mas ambíguo não é substituído por esse fallback.

Quando o MSE confirma o codec mas não os canais originais, a extensão pode
completar essa informação com `audioChannels` do formato atualmente selecionado
do player. Exige o mesmo vídeo atual, seleção explícita de áudio, faixa sem
ambiguidade e codec compatível com o objeto MSE associado ao blob exato. Essa
ponte permite processar uma origem estéreo confirmada mesmo se o worklet recebe
seis slots. Formatos apenas oferecidos não bastam. Metadados conflitantes são
preservados; um histórico de `changeType` com canais desconhecidos não reutiliza
a declaração estéreo anterior nem é completado pelo fallback.

Os métodos do fallback são privados do YouTube e podem mudar. Se não fornecerem
evidência suficiente, o estado permanece desconhecido e o áudio é preservado.
Não há requisição de rede, leitura de URLs assinadas, interceptação de mídia ou
contorno de proteção para obter o codec.

Cada ativação e troca anunciada de vídeo/faixa começa em preservação. Navegação,
`loadstart`, `emptied`, metadados e eventos do player invalidam o codec anterior,
reiniciam os filtros e avançam a geração. A confirmação precisa de duas leituras
consecutivas da mesma seleção; o worklet rejeita mensagens de gerações anteriores.
Se a página parar de renovar a informação, a permissão expira após um segundo de
PCM processado. Mudanças internas sem evento só podem ser detectadas na próxima
consulta do player, feita a cada 200 ms; a proteção depende de dados atuais do
player. MSE criado em worker, fonte anterior à captura e MIME não registrado
podem precisar do fallback ou permanecer desconhecidos. A execução no Opera
ainda precisa validar a captura e os campos realmente expostos.

## Instalação e uso no Opera

1. Deixe o gerenciador CM6206 ativo na rota **PCM** com entrada **Auto** ou
   **Native**. O mixer recebe seis canais já processados pela extensão. Uma
   segunda seleção manual **Stereo** no gerenciador substituiria o 5.1 nativo.
2. Abra `opera://extensions`, ative **Modo de desenvolvedor** e escolha
   **Carregar sem compactação**. Selecione esta pasta, que contém `manifest.json`.
3. Atualize a página do YouTube com F5. Inicie o vídeo e clique em **Ativar upmix
   automático 5.1**, no canto inferior direito. Esse clique permite iniciar o
   `AudioContext` conforme a política de reprodução do navegador.
4. Confira a indicação **Upmix 2 → 5.1 (Opus/AAC)** em uma fonte estéreo com codec
   confirmado e **5.1 da origem preservado** quando os seis canais originais
   estiverem confirmados. **PCM 6 preservado — origem não confirmada** informa
   apenas os slots recebidos do navegador. A indicação descreve o processamento
   no navegador; a audição e a captura dos canais na CM6206 continuam necessárias.
5. O texto abaixo do botão mostra **Codec**, **Origem**, **PCM do navegador** e
   motivo de preservação. **Copiar diagnóstico** copia somente esse resumo;
   também é possível selecionar o texto. Não é necessário abrir o DevTools.

O botão alterna entre upmix automático e preservação da fonte. Mudanças de fonte
no mesmo elemento de vídeo revogam a confirmação anterior e reiniciam os filtros.
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
Preserva o PCM multicanal sem síntese quando a origem não autoriza upmix. A rota óptica/AC-3
do sistema é uma implementação separada.

## Dados e validação

Não possui permissões de rede, cookies, histórico, processo em segundo plano ou
serviço de coleta. Um estado temporário na própria página informa número de
canais PCM recebidos, codec confirmado, canais originais quando informados,
origem da evidência do codec, motivo de preservação, modo, sessão da página e
geração da fonte. Não inclui URLs de mídia,
identificadores de conta nem amostras PCM.

Os testes de núcleo e de integração simulada podem ser executados sem áudio:

```powershell
node --test configuracao-pc/browser-upmix-extensao.test.cjs
```

Eles verificam preservação de seis canais, inclusive dez segundos só nas
frontais, Opus/AAC mono/estéreo autorizado, AC-3/E-AC-3 estéreo preservado, codec
desconhecido, seleção ambígua, expiração da confirmação, navegação sem reutilizar
codec antigo, identidade MSE/blob, buffer ativo em vez de formato oferecido,
contratos e erros nativos, histórico de `changeType`, origem estéreo confirmada
em seis slots PCM, ausência de prova de origem em seis slots, diagnóstico copiável,
metadado multicanal como veto,
transição estéreo → nativo, ganhos,
filtros, blocos de tamanhos diferentes e falhas anteriores à vinculação. Esses testes não comprovam ainda
o comportamento do Opera, a política CORS do vídeo atual ou a saída física.

Referências: [MediaElementAudioSourceNode e segurança CORS](https://www.w3.org/TR/webaudio-1.1/#MediaElementAudioSourceNode),
[entrada do AudioWorkletProcessor](https://www.w3.org/TR/webaudio-1.1/#dom-audioworkletprocessor-process)
e [capacidade do destino](https://developer.mozilla.org/en-US/docs/Web/API/AudioDestinationNode/maxChannelCount).
Identificadores de codecs: [registro de AAC](https://www.w3.org/TR/webcodecs-aac-codec-registration/)
e [MP4 Registration Authority](https://mp4ra.org/registered-types/object-types).
Captura da fonte: [Media Source Extensions — buffers ativos e changeType](https://www.w3.org/TR/media-source-2/).
