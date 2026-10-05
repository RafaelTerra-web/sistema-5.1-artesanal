# Auditoria de deriva, latência e fidelidade — 03/10/2026

**Nota posterior:** a medição abaixo precede os ajustes entre caixas. A configuração atual é FL/FR 76,8 ms nominais, CEN 5,8 ms, SL/SR 71 ms e LFE 0 ms.

O perfil com calibração de relógio, AC-3 a 640 kbit/s e buffer de saída de 32 ms manteve os seis canais e os atrasos relativos de 70/70/0/0/70/70 ms. A observação de 13 minutos cobriu o intervalo em que a rota anterior descartava áudio a cada aproximadamente 12 minutos: houve zero descartes, silêncio inserido e underruns, com mediana da fila do relay estável em 40 ms.

## Evidência anterior

Fonte: [backup do relay](backup-fidelidade-2026-10-03/audio-sistema.log), de 01:14:18 a 02:33:18, e [backup do mpv](backup-fidelidade-2026-10-03/audio-sistema.log.mpv.log).

O relay descartou seis blocos de 1.440 frames, equivalentes a 30 ms por evento e 180 ms ao todo. Nos eventos, a fila atingiu 90 ms e foi reduzida para 60 ms pelo limite de 80 ms. O máximo de escrita de 82,5 ms já existia no primeiro segundo e não aumentou durante os eventos. As duas discontinuidades ocorreram no início, não nos descartes recorrentes.

| Horário do descarte | Frames descartados acumulados | Intervalo desde o anterior |
|---|---:|---:|
| 01:30:34.962 | 1.440 | — |
| 01:42:46.699 | 2.880 | 731,737 s |
| 01:54:57.095 | 4.320 | 730,396 s |
| 02:06:52.097 | 5.760 | 715,002 s |
| 02:19:00.441 | 7.200 | 728,344 s |
| 02:30:39.405 | 8.640 | 698,964 s |

A média dos cinco intervalos é 720,8886 s. Dividir os 30 ms descartados por esse intervalo indica uma diferença de taxa de aproximadamente **41,6153 ppm**, perto de dois frames por segundo. Isso sustenta a hipótese de diferença entre os relógios CABLE e HDMI. Não é uma medição direta de seus osciladores.

O fluxo alocava aproximadamente 100 arrays de 11.520 bytes por segundo: 1,152 MB/s, ou 4,15 GB/h de alocação transitória. O processo anterior apresentou 289 coletas Gen0, uma Gen1 e nenhuma Gen2 na consulta feita durante a auditoria. Essa carga era evitável, mas o padrão regular dos descartes e a ausência de novos máximos de escrita não apontavam o GC como causa da deriva.

## Perfil aplicado e medição

O processamento está nesta ordem:

```text
PCM 5.1/48 kHz → asetrate=48002 → aresample=48000:filter_size=64:phase_shift=10:cutoff=0.97
→ adelay=70|70|0|0|70|70 → AC-3/640 kbit/s → WASAPI exclusivo
```

Declarar a entrada em 48.002 Hz e reamostrar para 48.000 Hz produz uma correção de **41,6667 ppm**, próxima dos 41,6153 ppm observados. Todos os canais passam pelo mesmo reamostrador, e os atrasos são aplicados depois, em 48 kHz. A diferença residual estimada, se os relógios mantiverem suas taxas, é cerca de 0,185 ms/h.

O log ativo confirmou buffer do dispositivo HDMI de 480 frames/10 ms e buffer de software de **1.536 frames/32 ms**, anteriormente 3.072 frames/64 ms. Esses números descrevem buffers; não são uma medida do atraso acústico total.

A fila do player usa `NewestInputPtsSeconds = SentFrames / 48002`, pois `SentFrames` conta frames antes da calibração e `audio-pts` segue a linha de tempo após a calibração. O helper expõe `CalibratedInputRateHz` e detecta `asetrate` pelos filtros ativos. Dividir por 48.000 após a calibração criaria uma falsa deriva de aproximadamente 32,5 ms em 13 minutos.

Cinco amostras IPC anteriores tiveram mediana de 114,8 ms; as cinco primeiras após a ativação tiveram mediana de 74,6 ms. Uma amostra às 02:43:35 estimou 80,3 ms; a amostra final às 02:52:02 estimou 74,6 ms. São estimativas de fila de software obtidas por interpolação entre logs de um segundo, sujeitas à fase dos blocos AC-3 e às oscilações da fila. A latência física dos equipamentos não foi medida.

## Testes offline

Execução reproduzível: `C:\Python314\python.exe configuracao-pc\auditoria-deriva-20261003\testar-calibracao.py`. O script cria sinais e arquivos de análise apenas nessa pasta e usa saídas PCM em arquivo e `ao=null`, sem reprodução no hardware. Resultados: [resultados.json](auditoria-deriva-20261003/resultados.json).

| Teste | Resultado |
|---|---|
| Contagem de 27 s de entrada | 1.296.000 frames originais; 1.295.947 após calibração, compatível com a razão 48.000/48.002 e arredondamento final |
| Duração com adelay | Acréscimo exato de 3.360 frames/70 ms ao comprimento de saída |
| Impulsos simultâneos nos seis canais | Picos FL/FR/SL/SR no frame 8.160 e CEN/LFE no frame 4.800: atrasos relativos exatos de 70/70/0/0/70/70 ms |
| Reamostrador, ganho a 1 kHz | +0,0000213 dB |
| Reamostrador, ganho a 10 kHz | −0,0000527 dB |
| Reamostrador, ganho a 20 kHz | +0,0000138 dB |
| Cadeia completa com AC-3/640 e buffer configurado em 32 ms | Saída `48000Hz stereo 2ch spdif-ac3`, término normal, nenhum erro de filtro ou codificação |

O ganho foi calculado por ajuste de seno e cosseno à frequência corrigida, excluindo 100 ms das extremidades. As frequências foram repetidas nos seis canais. Não houve mudança relevante de ganho nos três pontos da banda testados. A mudança nominal de frequência é de 41,6667 ppm e serve à compensação de relógio.

Na contagem por blocos, o primeiro pacote de 960 frames produziu 927, com 33 frames drenados ao final: aproximadamente 0,6875 ms de retenção inicial nessa execução. O teste `ao=null` valida a cadeia e a codificação; a confirmação do buffer WASAPI de 32 ms veio do player ativo.

## Observação do áudio ativo

Período do log preservado: **02:38:21.246 a 02:51:24.437**, 783,191 s, 719 linhas de métricas. Os PIDs permaneceram runner 30560 e mpv 33084. Foram coletados CPU e métricas a cada 30 segundos, sem reiniciar áudio nem modificar a configuração durante a observação.

| Métrica | Resultado |
|---|---:|
| Mediana da fila do relay, primeiros 2 minutos | 40 ms |
| Mediana da fila do relay, últimos 2 minutos | 40 ms |
| Média da fila, primeiros / últimos 2 minutos | 36,45 / 37,03 ms |
| Frames descartados / silêncio inserido | 0 / 0 |
| Underruns do dispositivo registrados pelo mpv | 0 |
| Discontinuidades / erros de timestamp | 1 no início / 0 |
| Arrays do pool alocados | 16, sem crescimento |
| Máximo de escrita / intervalo de captura | 68,3 / 22,3 ms, sem novos máximos na janela |
| Amostras acima de amplitude absoluta 1 | 0 em todos os canais observados |
| Maior pico registrado | FR: 0,406639 |
| CPU média do runner / mpv | 0,0705% / 0,2432% de um núcleo lógico |
| CPU máxima em janela de 30 s, runner / mpv | 0,2083% / 0,5206% de um núcleo lógico |
| Tempo médio da medição por bloco de 10 ms | 0,0057 ms |

A consulta CLR às 02:45:33 apresentou uma coleta Gen0 e nenhuma Gen1/Gen2; às 02:52:06 apresentou duas Gen0, uma Gen1 e nenhuma Gen2. O pool permaneceu em 16 arrays e nenhuma dessas coletas coincidiu com descartes ou novos máximos de escrita. O heap gerenciado final foi de 6,53 MB.

Arquivos preservados: [log após calibração](auditoria-deriva-20261003/relay-apos-calibracao.log), [snapshots de 30 segundos](auditoria-deriva-20261003/observacao-30s.csv), [resumo da observação](auditoria-deriva-20261003/observacao-resumo.json), [IPC final](auditoria-deriva-20261003/ipc-amostra-final.json) e [CLR final](auditoria-deriva-20261003/gc-final.json).

## Limites e fontes técnicas

A calibração é fixa e foi escolhida a partir do relógio observado. A janela de 13 minutos cobriu uma recorrência anterior, mas não certifica estabilidade por horas ou após mudanças de dispositivo, relógio ou períodos de captura parada. A observação não teve silêncio inserido, portanto não valida o caminho de preenchimento de silêncio.

`speed` com `audio-pitch-correction=no` foi descartado para esta cadeia. Na versão local `a1f50f2c3`, o ajuste por reamostragem é enviado ao conversor final após os filtros do usuário; nessa posição o áudio já é AC-3, e o conversor ignora pedidos de reamostragem de áudio não PCM. Fontes: [player/audio.c](https://github.com/mpv-player/mpv/blob/a1f50f2c3/player/audio.c#L48), [f_output_chain.c](https://github.com/mpv-player/mpv/blob/a1f50f2c3/filters/f_output_chain.c#L502) e [f_autoconvert.c](https://github.com/mpv-player/mpv/blob/a1f50f2c3/filters/f_autoconvert.c#L357).

O FFmpeg documenta que `asetrate` altera a taxa declarada e que `aresample` converte a taxa, com opções de filtro, fases e cutoff: [filtros de áudio](https://ffmpeg.org/ffmpeg-filters.html#asetrate) e [reamostrador](https://ffmpeg.org/ffmpeg-resampler.html). Para uma futura calibração adaptativa, a captura já recebe posição de dispositivo e timestamp QPC, hoje descartados; o timestamp retornado por WASAPI usa unidades de 100 ns: [IAudioCaptureClient::GetBuffer](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudiocaptureclient-getbuffer).
