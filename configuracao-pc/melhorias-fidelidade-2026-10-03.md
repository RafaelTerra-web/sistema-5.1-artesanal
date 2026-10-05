# Melhorias de fidelidade e latência — 03/10/2026

**Nota posterior:** as medições deste relatório foram feitas antes dos ajustes entre caixas. A configuração atual é FL/FR 76,8 ms nominais, CEN 5,8 ms, SL/SR 71 ms e LFE 0 ms.

Rota: Windows/CABLE Input 5.1 → captura WASAPI → processamento float32 → AC-3 → HDMI Sony → UD851B. **FL/FR/SL/SR continuam com 70 ms adicionais; CEN/LFE continuam com 0 ms adicionais.** O usuário confirmou que as seis caixas continuam tocando normalmente, sem falhas ou distorção, após a ativação do perfil novo.

## Aplicado

| Etapa | Antes | Agora | Motivo |
| --- | --- | --- | --- |
| Codificação Dolby Digital | 448 kbit/s | 640 kbit/s | Mais bits disponíveis para codificar os mesmos seis canais; a compressão continua sendo com perdas |
| Buffer de saída mpv/WASAPI | 3.072 frames / 64 ms | 1.536 frames / 32 ms, confirmado no log | Menos áudio aguardando a saída |
| Relógios CABLE/HDMI | Descarte de 30 ms a cada ~12 min | Calibração fixa de aproximadamente +41,67 ppm antes dos atrasos | Compensar a taxa que fazia a fila crescer lentamente |
| Alocação na captura | Novo array em cada pacote, aproximadamente 100/s | 16 buffers reutilizáveis de 11.520 bytes | Reduzir trabalho do coletor de memória |
| LFE do fallback | Butterworth de quarta ordem | Duas etapas 120 Hz/Q 0,7071, iguais ao APO | Manter a mesma resposta de grave nos dois caminhos do upmix |
| Diagnóstico de margem | Sem medição por canal | Picos e contagem de amostras acima de ±1 antes da codificação | Verificar possível saturação antes de alterar ganho/dinâmica |

Não há custo adicional de hardware ou software.

## Calibração dos relógios

O log anterior registrou descartes repetidos de 1.440 frames = 30 ms. Cinco incrementos entre 01:30:34,962 e 02:30:39,405 somam 150 ms em 3.604,443 s, equivalentes a cerca de **41,615 ppm**. O contador de descontinuidades ficou em 2 e o maior bloqueio de escrita não cresceu nesses eventos. Isso sustenta a hipótese de diferença de taxa entre os dispositivos, em vez de pausas repetidas do programa.

A cadeia nova é:

```text
asetrate=48002
aresample=48000:filter_size=64:phase_shift=10:cutoff=0.97
adelay=70|70|0|0|70|70
lavcac3enc=tospdif=yes:bitrate=640:minch=6
```

A reamostragem produz aproximadamente 48.000/48.002 amostras por amostra de entrada. A saída HDMI continua a **48 kHz**, com seis canais. Esse ajuste é fixo e específico do comportamento observado: não é um controlador automático e pode precisar de nova medição se o dispositivo ou a rota mudar. A mudança nominal de velocidade/pitch é cerca de **0,00417% / 0,072 cent**. O atraso dos quatro canais é aplicado depois da reamostragem: continua sendo exatamente 3.360 amostras a 48 kHz.

O diagnóstico `Medir fila do player.ps1` lê os filtros ativos por IPC e considera a taxa calibrada na comparação entre frames enviados e posição do player. Usar sempre 48.000 nessa comparação faria a medição apresentar uma falsa deriva ao longo do tempo.

## Testes de código e sinal

- Os três C# compilaram no Windows PowerShell 5.1/.NET Framework. Testes do pool cobriram blocos parciais, reutilização, trim, cancelamento, corrida com a captura e exceção de escrita. Os dados de observação dos picos não alteram o sinal.
- O filtro do fallback foi comparado em seis frequências com o filtro APO esperado. A interface em bytes e os blocos nativos foram preservados nos testes em memória. O upmix continua sendo uma heurística para fluxos declarados como seis canais mas contendo apenas os frontais.
- Teste offline da calibração: 1.296.000 frames de entrada produziram 1.295.947 frames após reamostragem; o filtro de atraso adicionou exatamente 3.360 frames. O bloco final da reamostragem continha 33 amostras, cerca de 0,69 ms de retenção nesse teste.
- Impulsos simultâneos: picos de FL/FR/SL/SR ficaram exatamente 3.360 amostras depois de CEN/LFE.
- Ganho medido da reamostragem: **+0,0000213 dB em 1 kHz**, **−0,0000527 dB em 10 kHz**, **+0,0000138 dB em 20 kHz**. Os seis canais foram verificados.
- A cadeia completa até AC-3 640 foi testada com saída nula. O log da rota real confirmou WASAPI exclusivo, bitstream AC-3 a 48 kHz e buffer de 1.536 frames. O usuário confirmou o funcionamento físico das seis caixas.

Relatórios e testes detalhados: `auditoria-fidelidade-dsp.md`, `auditoria-relay-pool.md`, `auditoria-deriva-20261003`.

## Latência de software

Cinco amostras anteriores pelo IPC: **107,4 / 120,8 / 116,6 / 113,7 / 114,8 ms**. As cinco primeiras amostras com o perfil novo ficaram entre **73,2 e 76,9 ms**, média **74,98 ms**. São estimativas da fila entre entrega ao pipe e a posição informada pelo mpv, com interpolação entre logs de um segundo. Não medem atraso acústico, decoder, TV, amplificadores ou toda a latência desde o aplicativo.

## Perfis e recuperação

- **Audio - maior fidelidade.cmd:** AC-3 640 kbit/s, buffer 32 ms.
- **Audio - maior estabilidade.cmd:** AC-3 448 kbit/s, buffer 64 ms.

Ambos mantêm os atrasos, o upmix e a calibração de relógio. A troca reinicia a rota ligada; o estado desligado é preservado. O controle principal da Área de Trabalho continua responsável por ligar/desligar o sistema. A configuração anterior à revisão está em `backup-fidelidade-2026-10-03`; os backups específicos do C# estão ao lado dos arquivos de origem.

## Observação da rota real por 13 minutos

Janela de **02:38:21,246 a 02:51:24,437**, duração **783,191 s**, 719 linhas de métricas. O mesmo relay e o mesmo mpv permaneceram ativos, sem reinício.

| Medida | Resultado |
| --- | --- |
| Frames descartados / silêncio inserido | 0 / 0 |
| Avisos de falta de dados na saída mpv (`underrun`) | 0 |
| Erros de timestamp | 0 |
| Descontinuidades de captura | 1 no início; nenhuma adicional |
| Mediana da fila do relay, primeiros / últimos 2 min | 40 / 40 ms |
| Média da fila, primeiros / últimos 2 min | 36,45 / 37,03 ms |
| Arrays do pool alocados | 16, sem crescimento |
| Amostras com magnitude maior que 1 | 0 nos seis canais |
| Maior pico observado antes da codificação | 0,406639 no FR |

A janela supera o intervalo anterior de cerca de 12 minutos entre descartes. A fila deixou de mostrar crescimento contínuo nessa observação; isso não garante estabilidade para toda carga futura do PC ou outra ligação HDMI. Os dados e o log completo foram salvos em `auditoria-deriva-20261003`. A margem digital observada é anterior à reamostragem/codificação; não mede distorção acústica ou saturação dos amplificadores.

Referências primárias: [mpv — codificador AC-3](https://github.com/mpv-player/mpv/blob/master/audio/filter/af_lavcac3enc.c), [FFmpeg — reamostragem](https://ffmpeg.org/ffmpeg-resampler.html), [FFmpeg — asetrate](https://ffmpeg.org/ffmpeg-filters.html#asetrate), [Equalizer APO — filtros](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/).
