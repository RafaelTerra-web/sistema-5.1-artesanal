# Bancada PCM da CM6206 no Windows

Protótipo em validação, 09/10/2026: player decodifica → VB-CABLE render de seis canais → loopback WASAPI → mpv/DSP → USB analógico CM6206 de oito slots. Não usa TV/óptica nem recodifica Dolby. É uma bancada distinta da [comunicação óptica](CM6206-COMUNICACAO-2026-10-09.md).

**Estado mais recente:** leia a [retomada](RETOMADA-PROXIMO-CHAT.md) e a seção final deste documento. Os ensaios intermediários abaixo são históricos. O usuário confirmou a recuperação das caixas e som limpo; continuam pendentes estabilidade prolongada e mapa REAR definitivo.

O [script](../scripts/pc-cm6206-pcm.ps1) exige IDs locais completos, mpv e SoundVolumeView obtidos separadamente. Faz preflight da captura float32/48 kHz/seis canais e verifica as interfaces. Usa mutex e rejeita o gerenciador Dolby legado em execução. Não altera HID, instala APO nem reinicia serviços. Formato/configuração Windows multicanal precisam ser preparados e verificados separadamente; oito controles de volume não provam formato compartilhado de oito canais.

## Modo de entrada

- `Native`: conserva os seis slots, inclusive canais silenciosos. Usar para a fonte E-AC-3 informada pelo usuário. Bass management/trims continuam ativos, sem gerar central/surround a partir das frontais.
- `Auto` (padrão): conserva os seis slots da captura. Pode receber upmix anterior da extensão quando ela confirma codec não Dolby e origem mono/estéreo. O APO genérico foi desativado; não decide pelo silêncio nos outros canais.
- `Stereo`: override para uma fonte conhecida como estéreo **não Dolby**. Não usar para AC-3/E-AC-3 nem só porque há sinal nas frontais: um decoder negociado em estéreo pode já ter feito downmix de 5.1.

`ec-3 (328)` nas Estatísticas para nerds confirma o codec recebido pelo Opera, não o formato PCM entregue ao Windows ou o mapa físico. `ec-3` identifica [Dolby Digital Plus](https://ott.dolby.com/OnDelKits/DDP/Dolby_Digital_Plus_Online_Delivery_Kit_v1.5/Documentation/Content_Creation/SDM/help_files/topics/ddp_mpeg_dash_c_mpd_adaptation.html). Atualizar o vídeo depois de mudar a rota/formato e verificar os seis canais antes de aprovar preservação de 5.1.

## Grafo e mapa provisório

LR4 a 90 Hz nas cinco caixas; soma dos graves ao LFE original; margem LFE 1/6; subsônico Butterworth 20 Hz; ganho mestre padrão 0,1; trim central inicial −12 dB. São ajustes conservadores de bancada, não calibração final. Não inclui os atrasos da cadeia HDMI antiga, que precisam de nova medição.

`-SwapCenterLfe` coloca FC no slot físico 3/R e LFE no 2/L, somente se o teste físico confirmar esse mapa. O teste atual colocou a fala central no sub, portanto o teste isolado posterior confirmou central no slot 2 e sub no slot 3: usar sem inversão nesta montagem Windows. Volumes por canal do Windows precisam acompanhar essa troca. Surrounds são duplicadas em BL/BR e SL/SR provisoriamente; identificar o par do conector REAR antes do mapa definitivo. O A34 usa seis canais e não replica automaticamente esses oito slots.

## Execução, parada e teste em arquivos

Pare o gerenciador Dolby antes. Variáveis abaixo representam caminhos/IDs locais; use ganhos baixos e confirme autorização antes de testes sintéticos.

```powershell
.\scripts\pc-cm6206-pcm.ps1 -MpvPath $mpvLocal -SoundVolumeViewPath $volumeToolLocal `
  -CaptureEndpointId $vbCableRenderId -RenderEndpointId $cm6206RenderId `
  -InputMode Native -Shared
```

`-ValidateOnly` faz preflight e gera o grafo sem roteamento/reprodução. `-Shared` abre compartilhado; sem ele, exclusivo. O script muda padrões console/multimídia e preferência do player para VB-CABLE. Na parada, restaura os padrões anteriores se ainda forem seus e direciona o player ao antigo padrão multimídia. Não recupera uma preferência por aplicativo anterior diferente do padrão; conferir manualmente se houver personalização.

Crie `android-a34/artifacts/pc-cm6206-pcm/pcm.stop` para parar. Logs ficam no diretório ignorado. Processo vivo ou linha `running` não bastam: confirmar log novo, abertura `AO: [wasapi] ... 8ch`, avanço de `sentFrames` e ausência de erro. O watchdog para se stdin bloquear; não há correção adaptativa de relógio.

O [teste de sinais](../scripts/test-pc-crossover.py) usa `--ao=pcm`, sem caixas: resposta a 40/1.000 Hz, isolamento, trim, swap, duplicação surround e soma dos seis graves.

```powershell
python .\scripts\test-pc-crossover.py --mpv $mpvLocal --config .\android-a34\artifacts\pc-cm6206-pcm\pcm.conf
```

## Evidência e limites

O usuário ouviu frontais limpas, surrounds e sub baixo no protótipo estéreo; central ausente antes de informar sua ligação no R. Isso não confirma E-AC-3 preservado. A captura Native anterior só mostrava FL/FR; o endpoint CM estava compartilhado em dois canais. Depois foi configurado e lido como oito canais.

As aberturas Native posteriores falharam, inclusive após parar o gerenciador legado e retirar o mute do endpoint: `AUDCLNT_E_DEVICE_INVALIDATED`, com parada por stdin bloqueado. A [Microsoft documenta](https://learn.microsoft.com/en-us/windows/win32/coreaudio/recovering-from-an-invalid-device-error) invalidação de endpoint e reconstrução da sessão. Após reconexão física, a enumeração PnP apareceu OK, mas a leitura HID também falhou com dispositivo não funcionando. Recuperação do dispositivo continua pendente; não atribuir esses erros a copyright.

O vídeo fornecido testa baixas frequências. Com LR4 a 90 Hz, 40 Hz fica aproximadamente 28 dB abaixo nas caixas pequenas; identificar a central exige também voz/tom adequado em canal isolado. Confirmar mapa, ganhos, cabos e polaridade em teste autorizado, depois medir atrasos e estabilidade.

Atualização da recuperação: restaurados formato padrão estéreo e máscaras originais a partir do backup. A tentativa de saída exclusiva de oito canais continuou falhando com `0x80070001`, embora IsFormatSupported aceitasse o formato. A leitura HID permaneceu com erro. Solicitado retirar a óptica temporariamente e usar outra porta USB para isolar a condição física. O formato padrão atual voltou a dois canais; isso não altera o grafo Native de seis canais, mas a nova sessão USB ainda não abriu.

Nova porta USB, óptica retirada: leitura dos seis registradores voltou a funcionar, REG2=0x6004/DRIVERON desligado. Após o ajuste dos volumes por canal via Windows, leitura HID voltou a falhar; o wrapper parou antes de escrever DRIVERON ou iniciar reprodução. A sequência sugere investigar a interação de controle de volume/driver, mas não estabelece causalidade. Próximo ensaio solicitado: reconectar na mesma porta e usar só ganho baixo no DSP, sem alterar controles de volume da placa.

Recuperação sem ajuste dos volumes do driver: leitura HID dos seis registradores passou; REG2 0x6004 foi journalado e somente DRIVERON foi mantido em 0xE004 pelo wrapper privado, com restauração verificada a 0x6004 na parada. Saída exclusiva abriu como WASAPI 48 kHz/7.1/oito canais. Após atualizar o vídeo, a captura apresentou sinal em FL/FR/FC/LFE/SL/SR, com autoFrames=0 e modo Native. Isso confirma sinal na captura, não o mapa físico nem independência dos canais originais. Óptica permaneceu retirada: teste PC→USB, sem aprovação adicional do passthrough.

Ganho DSP 2% foi baixo; aumentado para 10% por pedido do usuário. Ele relatou som somente nas frontais, sem central, surrounds ou sub. Sessões curtas começaram sem quedas, mas depois registraram 7.200 frames descartados (~150 ms), escrita máxima ~200 ms e uma descontinuidade inicial: estabilidade prolongada não aprovada. Preparado teste falado direto de oito slots, central no R/slot 3, LFE no L/slot 2 e comparação separada BL/BR versus SL/SR. Sem crossover, EQ ou upmix nesse teste. Picos: frontais/traseiras 0,10, central 0,05, tom LFE 60 Hz/0,5 s a 0,003 com rampas. Anúncio do sub nas frontais. Preparação somente, aguardando autorização de reprodução; arquivos/identificadores ficam privados.

Teste falado direto executou em WASAPI 48 kHz/7.1/oito canais, sem crossover/EQ/upmix. O usuário relatou que a fala Central, enviada ao slot USB 3 (índice zero), saiu no subwoofer. Portanto a informação de ligação da central no R não autoriza presumir a inversão FC/LFE: o mapa precisa de confirmação empírica. Não manter a troca apenas por essa descrição. Preparado teste isolado de duas falas nos slots 2 e 3, pico igual 0,05, demais slots zerados e sem tom grave; aguardando reprodução. A saída analógica foi restaurada a REG2 0x6004 com leitura verificada depois do teste. Uma leitura HID falhou na transição, mas a repetição somente de leitura recuperou os seis registradores, sem nova reconexão física; falhas anteriores não estabelecem causalidade exclusiva do volume Windows.

Mapa CEN/BASS confirmado por escuta: teste isolado de duas falas executado até o fim, saída WASAPI 48 kHz/7.1/oito canais, sem filtro/EQ/upmix, demais seis slots zerados. O usuário ouviu a primeira fala na central e a segunda no sub. Primeira = slot USB 2 (terceiro canal), segunda = slot 3 (quarto canal). Nesta rota Windows, preservar a ordem lógica FC/LFE normal, sem SwapCenterLfe; a ligação R no amplificador não muda essa evidência. REG2 restaurado e verificado em 0x6004 ao terminar. Mapas Android ainda exigem escuta própria porque usam seis posições/HAL distinto.

Retirada a inversão do runner local e do grafo PCM ativo em arquivo; não reiniciada música neste momento. Ganho padrão do script de bancada reduzido de 0,5 para 0,1, mantendo trim central −12 dB, margem LFE 1/6 e cortes. Teste de sinais em arquivo com mapa normal/ganho 0,1 passou. Corrigida a descrição da opção no A34 para exigir confirmação física, sem prometer que uma central ligada no R precisa de inversão. Preparado teste de traseiras separado: quatro falas, slots 4/5 e depois 6/7; frontais/central/LFE zerados, pico 0,1, ~25 s; aguardando autorização de reprodução.

## Estado após restauração do upmix

PCM USB foi restaurado com guard HID nativo, placa em oito canais e ganhos por slot equilibrados; o usuário confirmou todas as caixas e som melhor. Perfil final: master0,15, LFEtrim+3dB, envio centralgrave0,5/corte90Hz, atrasos3686/3686/278/278/3408/3408. Envio central entra uma vez no LFE antes dos delays; headroom1/5,5 antecede trim. Aplicação porIPC confirmada, testes de sinais e de atrasos passaram; usuário confirmou limpo e sub presente. Ainda há perdas periódicas na fila em sessões prolongadas.

AC-3/E-AC-3 não recebem upmix, mesmo com dois canais. A matriz APO genérica foi desativada porque não conhece o codec original. A extensão 0.2.1 usa metadados do buffer/player e preserva ausência/ambiguidade; arquivos desta versão passaram53testes. O modo Stereo manual exige fonte estéreo não Dolby confirmada. Não declarar que todos os apps do Windows expõem codec ao mixer.
