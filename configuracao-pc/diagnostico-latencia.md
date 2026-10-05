# Diagnóstico da latência e dos cortes de áudio

**Nota posterior:** os números de atraso abaixo registram as medições anteriores aos ajustes entre caixas. A configuração atual é FL/FR 76,8 ms nominais, CEN 5,8 ms, SL/SR 71 ms e LFE 0 ms.

Estado observado em 03/10/2026. A rota global continua CABLE Input 5.1 → captura WASAPI → mpv → Dolby Digital/AC-3 pelo HDMI Sony → UD851B. Os atrasos atuais constam da nota acima; as medições antigas nas seções seguintes pertencem a configurações anteriores.

**Atualização posterior nesta data:** o perfil ativo agora usa **AC-3 640 kbit/s**, **buffer confirmado de 32 ms**, buffers reutilizados no relay e calibração fixa de aproximadamente +41,67 ppm antes dos atrasos. O teste de **13 minutos** registrou zero descartes, zero silêncio inserido e zero underruns, com mediana da fila de 40 ms tanto nos primeiros quanto nos últimos dois minutos. O usuário confirmou áudio normal nas seis caixas. Veja [melhorias-fidelidade-2026-10-03.md](melhorias-fidelidade-2026-10-03.md). As seções abaixo preservam o diagnóstico da primeira otimização, que usava 448 kbit/s e buffer de 64 ms.

## Tentativa de reduzir a latência preservando a sincronização (03/10/2026)

Os atrasos exatos no filtro são `3686S|3686S|278S|0S|3408S|3408S` a 48 kHz, equivalentes a FL/FR 76,792 ms, CEN 5,792 ms, LFE 0 ms e SL/SR 71 ms. Como o LFE já está em zero, não há valor comum que se possa subtrair dos seis atrasos sem alterar as diferenças entre caixas.

Testes controlados no perfil Fidelidade, sempre restaurando o padrão entre eles:

| Configuração | Fila estimada pelo IPC do mpv | Observações |
| --- | --- | --- |
| Padrão: `audio-buffer=0.032`, período HDMI 10 ms | mediana 65,6 ms; p90 70,5 ms (15 amostras) | Sem descartes, silêncio inserido ou underruns. |
| Solicitação de buffer de 24 ms | mediana 76,6 ms; p90 87,3 ms (15 amostras) | O mpv manteve buffer efetivo de 32 ms; sem benefício observado. |
| Período HDMI mínimo de 3,33 ms | mediana 69,05 ms; p90 77,9 ms (20 amostras) | Sem melhora convincente; ocorreu um descarte de 30 ms apenas na inicialização da rota, sem novos descartes durante a observação. |

A rota foi restaurada e verificada no perfil Fidelidade: buffer efetivo do mpv de 32 ms, dispositivo HDMI em 10 ms, AC-3 640 kbit/s e os mesmos atrasos por canal. As filas IPC são **estimativas de software**, não medidas da latência acústica ou audiovisual; amostras curtas de sessões diferentes não permitem atribuir diferenças pequenas à configuração testada. O AC-3 trabalha em quadros de 1.536 amostras/32 ms a 48 kHz, o que também limita reduções simples de buffer nesta rota. Para quantificar uma eventual melhora menor, seria necessária medição física de loopback ou sincronismo audiovisual.

## Picotes durante reprodução 4K no Jellyfin (03/10/2026)

Durante a conversão de HEVC HDR 2160p para H.264 SDR, o FFmpeg consumia cerca de 66,6% da CPU total; a carga total observada era 82%. O mpv estava com prioridade Normal. A janela anterior ao ajuste registrou 92 avisos de underrun nos últimos 20 segundos do log e 330 ms descartados pelo relay em aproximadamente 23 segundos. A captura não registrou amostras acima de 1,0 em nenhum canal, indicando que saturação na entrada não explicava os cortes observados.

A prioridade do mpv foi alterada ao vivo para AboveNormal e salva como `priority=abovenormal` nos dois arquivos de configuração. O buffer continua em 32 ms, o AC-3 em 640 kbit/s e os atrasos por canal permanecem idênticos. Não houve reinício da reprodução para esse ajuste. Depois da estabilização, o contador de descarte ficou parado; uma janela adicional de 30 segundos registrou zero novos underruns e zero áudio descartado. O usuário confirmou som contínuo. Os dados dessa janela estão em [diagnostico-picotes-2026-10-03.json](diagnostico-picotes-2026-10-03.json). Isso sustenta falta de tempo de execução da saída de áudio sob carga como causa dos cortes desta sessão; não constitui medição do sinal após o equalizador ou nos amplificadores.

## Mudança aplicada

O relay novo (`RelayLoopbackLowLatency.cs`) usa captura por eventos e uma thread separada para escrever no stdin do mpv; a fila do relay é limitada a 80 ms. Ele envia um WAV contínuo com seis canais, 48 kHz, float32 e máscara 5.1. O demuxer WAV do FFmpeg recebe pacotes de **11.520 bytes = 480 frames = 10 ms**, definidos por `max_size=11520`. Isso substitui o demuxer de áudio bruto, que entregava blocos de **125 ms** nessa frequência. O mpv recebeu `audio-buffer=0.064`; seu log confirmou buffer de **3.072 frames = 64 ms** em AC-3. O dispositivo HDMI reportou buffer de 480 frames/10 ms. Esses valores são capacidades e tamanhos de blocos, não uma medição do atraso total.

| Registro | Relay anterior | Relay atual, cerca de 5 min de observação |
| --- | --- | --- |
| Pacote de entrada no mpv | áudio bruto, 125 ms | WAV, 10 ms |
| Buffer de saída mpv | 6.144 frames, 128 ms | 3.072 frames, 64 ms |
| Avisos `Audio device underrun detected.` | 20 no log anterior | 0 em cerca de 5 min |
| Fila do relay | com ticks parcialmente vazios (~4 `underflows`/s em trecho com sinal) | tipicamente 0–10 ms; pico observado de 30 ms |
| Perdas/silêncio inserido | contador antigo não separava as causas | `droppedFrames=0`; `paddingSilenceFrames=0` |

No log atual, `discontinuities=1` apareceu no início e permaneceu em 1; `timestampErrors=0`, `droppedFrames=0` e `paddingSilenceFrames=0` na janela lida. As threads de captura e escrita registraram MMCSS ativo. A consulta IPC **Medir fila do player.ps1** estimou cerca de **88–117 ms** entre o áudio já entregue ao pipe e a posição de reprodução informada pelo mpv (última amostra: **112,5 ms**). Essa estimativa depende da interpolação dos contadores registrados uma vez por segundo; **não mede** a latência acústica, a TV, o decoder ou os amplificadores. O usuário confirmou que os cortes audíveis pararam no teste atual. Sessões futuras e mudanças no HDMI ainda exigem observação.

## Falha de HDMI e recuperação

Numa execução intermediária, aos ~109 s, o mpv recebeu aviso de mudança de estado da saída Sony e, ao reinicializar, não encontrou o GUID HDMI. A captura continuou por algum tempo sem entregar áudio, acumulando descartes. O iniciador atual (`rodar-audio-sistema.ps1`) define CABLE Input como saída padrão ao iniciar, procura o endpoint Sony/NVIDIA ativo e reinicia o relay após saída ou erro, com espera de 1 s; preserva logs de recuperação. Isso evita que aquela falha deixe o processo parado indefinidamente **quando o endpoint voltar a ficar disponível**. Desconexões ou mudanças de HDMI ainda podem interromper o som e exigem nova confirmação física.

## Como comparar novas sessões

Leia `audio-sistema.log` para `capturedFrames`, `sentFrames`, `paddingSilenceFrames`, `droppedFrames`, `queueMs`, `discontinuities` e `maxWriteMs`. Leia `audio-sistema.log.mpv.log` para o buffer efetivo e os avisos de underrun. Execute **Medir fila do player.ps1** com o relay ativo e compare os campos JSON `QueueMs`, `RelayDroppedFrames`, `RelayPaddingSilenceFrames` e `MpvDeviceUnderruns` em dois momentos. Os contadores reiniciam a cada sessão.

Fontes técnicas: [demuxer WAV do FFmpeg](https://ffmpeg.org/ffmpeg-formats.html#wav-1), [manual do mpv: buffers e demuxer](https://mpv.io/manual/stable/), [WASAPI: flag de descontinuidade](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiocaptureclient-getbuffer).
