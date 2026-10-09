# Rota óptica AC-3 no PC

A entrada SPDIF da CM6206 fornece o carrier IEC61937 ao PC como bytes PCM16 estéreo a 48 kHz. Esses bytes precisam permanecer intactos até o demultiplexador SPDIF. Ganho, equalizador, upmix e conversão para float antes dessa etapa corrompem o áudio codificado. Depois da decodificação AC-3, os seis canais PCM podem receber o DSP e sair pelas conexões analógicas da placa.

`scripts/pc-cm6206-optical.ps1` é o receptor sustentado dessa rota. Ele captura o endpoint SPDIF explícito em modo exclusivo, entrega os bytes ao mpv, exige decodificação AC-3 com `err_detect=crccheck+explode` e abre o endpoint analógico USB explícito com oito posições PCM. Não altera volumes do Windows, registros HID ou dispositivos padrão. O controlador do sistema deve cuidar da seleção da fonte, da posse do DRIVERON e da restauração após encerrar o filho.

## Fonte e canais

A rota inicial aceita AC-3 realmente decodificado em seis canais a 48 kHz. Não aplica upmix e não deduz estéreo pela ausência de atividade em central/sub/surrounds. AC-3 decodificado em dois canais provoca um erro explícito; o upmix baseado nesses metadados pode ser acrescentado depois. E-AC-3/DD+ no navegador não prova que a saída óptica contém DD+: a TV pode converter para AC-3, como precisa ocorrer nesta rota.

O mapa atual é `FL FR FC LFE SL SR SL SR` nas oito posições USB. Os testes falados confirmaram central na primeira posição CEN/BASS e subwoofer na segunda; não deve haver inversão FC/LFE. A duplicação de SL/SR nos dois pares traseiros é provisória até identificar o par físico usado pelo driver. A ordem lógica dos seis canais continua independente.

O ganho público `-Gain` é amplitude PCM linear: `0.1` equivale a −20 dB. O runner converte para a escala cúbica do controle de volume do mpv (`100 * Gain^(1/3)`), permitindo ajuste pelo IPC sem escrever volumes da placa. Ganho zero mantém a decodificação com saída silenciosa. `-Muted` aplica mute antes de abrir o renderer, evitando som durante a validação inicial. `-IpcPath` aceita somente um named pipe local. Se `-ConfigPath` for fornecido, somente sua única linha `af=lavfi=[...]` é lida; o restante do arquivo não configura processos, scripts ou dispositivos. Essa linha deve ser um DSP de entrada nativa de seis canais.

## Entrada HDMI gerada pelo PC

`scripts/pc-cm6206-encoder.ps1` recebe o loopback PCM float32 de seis canais do dispositivo virtual e produz um carrier AC-3 de 384, 448 ou 640 kbps na saída HDMI Sony explícita. Não aplica cortes, atrasos, EQ, ganho ou upmix. A marca de modo nativo é criada antes dos workers para bloquear a antiga heurística baseada na atividade dos canais.

Essa rota **recodifica o PCM já decodificado pelo navegador**. Não preserva os pacotes comprimidos originais do YouTube. O passthrough de um arquivo AC-3 original diretamente ao HDMI é outro modo e não deve ser confundido com esse encoder. A preservação aqui se refere à independência dos seis canais, não à transparência do codec.

O encadeamento completo é: player/navegador → dispositivo virtual PCM6 → encoder AC-3 → HDMI da Sony → saída óptica → SPDIF IN da CM6206 → receptor AC-3 → PCM8 analógico. Para uma fonte externa da TV, o encoder do PC deve ficar desligado.

## Execução e encerramento

Os IDs são obrigatórios e devem vir de uma enumeração atual dos endpoints. Reconectar a CM6206 em outra porta pode mudar seus IDs. `android-a34/scripts/windows-cm6206/probe-formats.ps1` oferece uma enumeração de leitura; não reutilize IDs antigos sem conferir nome, direção e estado ativo. O receptor exige SPDIF/USB Sound Device na captura e USB Sound Device analógico na saída. O encoder confirma a negociação no endpoint Sony configurado.

O receptor aceita `-CaptureEndpointId`, `-RenderEndpointId`, `-MpvPath`, `-Gain`, `-Shared`, `-Muted`, `-StopPath`, `-LogPath`, `-StatusPath`, `-ConfigPath`, `-IpcPath`, `-MaximumSeconds` e `-StartupTimeoutSeconds`. `MaximumSeconds=0` mantém a execução até o sentinel de parada. O encoder usa os mesmos IDs/caminhos de controle, com `-Bitrate` e `-StartupTimeoutSeconds`. Os wrappers aceitam `-ValidateOnly`, que monta/valida a configuração sem abrir áudio.

Log, status e sentinel permanecem em `android-a34/artifacts`, que não é publicado. Cada execução exige caminhos novos para impedir que logs antigos sejam confundidos com prontidão. Criar o arquivo em `StopPath` pede encerramento; aguarde o processo terminar e `CleanupComplete` antes de restaurar o guard HID. O receptor e a rota PCM compartilham um mutex; o encoder tem mutex separado para poder operar junto com o receptor. O gerenciador Dolby antigo precisa estar parado.

Na Bravia, as condições verificadas anteriormente foram **Sistema de áudio**, saída digital **Auto 1** e **Dolby Digital Plus Out desativado**. O cabo deve estar conectado de TV OUT para CM6206 SPDIF IN. A comparação física desta nova execução sustentada ainda depende desses cabos e do teste nas caixas.

## Evidências de prontidão e limites

O status do receptor só fica `running`/`Ready=true` depois de confirmar fonte AC-3 seis canais, seleção do decoder, opção CRC estrita, saída USB `48000Hz 7.1 8ch s16` e envio de carrier. `CaptureFrames` e `CarrierBytesSent` medem o carrier codificado; não contam amostras PCM decodificadas. `Ready` indica configuração aberta, não afirma que uma caixa específica foi ouvida.

O encoder só fica pronto depois do preflight PCM6, do envio positivo de frames e da negociação HDMI `48000Hz stereo 2ch spdif-ac3` no endpoint Sony. As duas posições são o carrier do bitstream, não uma redução do conteúdo AC-3 a dois canais.

O startup tem timeout, a escrita stdin é limitada e o encerramento mata somente o mpv pertencente ao runner se ele não terminar. Chamadas COM nativas ainda dependem do driver; desconexão/driver travado pode exigir reinicialização física. Erros de CRC, decoder ou negociação encerram a rota com diagnóstico, sem fallback silencioso para estéreo/null. O receptor não grava o carrier continuamente em disco.

Em 09/10/2026, passaram 17 checks de comando/log do receptor, dez do encoder e os nove checks do probe óptico anterior. O teste em arquivos com 282 bursts IEC61937 sintéticos produziu 433.152 frames: erro absoluto zero frente à decodificação AC-3 nativa com ganho 1 e máximo `9,31 × 10⁻¹⁰` com ganho linear 0,1. Ganho zero preservou a mesma duração com saída exatamente silenciosa. Confirmou FC/LFE independentes e as duas cópias traseiras. Esses testes não validam TV, DAC, caixas, latência ou estabilidade de longa duração.

Comandos de validação sem som: `scripts/optical-tests/test-continuous-spdif-relay.ps1`, `scripts/optical-tests/test-ac3-encoder.ps1` e `scripts/optical-tests/test-optical-native-files.py --mpv <mpv.exe>`. O último usa somente `ao=pcm` para arquivos sintéticos e depende de NumPy.

## Atualização de integração

O gerenciador usa o mesmo grafo nativo de bass management do PCM e aplica delays uma vez no receptor. Encoder Stereo é override de fonte confirmada; Auto/Native preservamPCM6. Saída óptica agora tem -OutputChannels6(padrão)/8; seis evita duplicação dos paresREAR. CRCcontinuaestrito no decoder; quadros inválidos são rejeitados, contabilizados e a sessão pode retomar quadros válidos. Isso não garante fluxoíntegro.

Os ensaios atuais reconheceramAC36 e USB8 mas registraramCRC, underruns e travamentosWASAPI. O testeUSB6não chegou a reproduzir porfalhaHID. MapaPCconfirmadoFCslot2/LFEslot3semswap; demaislimites/plano em[relatórioFireTV/A34](RELATORIO-ROTA-FIRE-TV-A34-2026-10-09.md).
