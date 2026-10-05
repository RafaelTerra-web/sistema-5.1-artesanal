# Auditoria da fidelidade DSP — filtro LFE do fallback

**Nota posterior:** os atrasos citados nesta auditoria registram o estado anterior aos ajustes entre caixas. Agora, FL/FR têm 76,8 ms nominais, CEN 5,8 ms, SL/SR 71 ms e LFE 0 ms.

Verificação em 03/10/2026. A alteração de código nesta etapa foi limitada ao construtor de `StereoUpmix.cs`: as duas etapas do passa-baixas de 120 Hz agora usam **Q = 0,7071**, os mesmos parâmetros encontrados no `upmix-sistema-5.1.txt` do projeto e em `C:\Program Files\EqualizerAPO\config\upmix-sistema-5.1.txt`.

Antes, o fallback usava Q 0,541196100146197 e 1,306562964876377, uma resposta Butterworth de quarta ordem. O APO usa duas etapas Q 0,7071, uma aproximação Linkwitz–Riley de quarta ordem. Essa diferença deixava o LFE do fallback aproximadamente 2,51 dB mais forte em 100 Hz e 3,01 dB mais forte em 120 Hz que o filtro do APO, para a mesma entrada do filtro. A unificação torna a resposta consistente entre os dois caminhos.

## Backup e mudança

Backup do código anterior: `StereoUpmix.antes-unificacao-lfe-20261003-023049.cs`.

| Arquivo | SHA-256 |
| --- | --- |
| Backup / código anterior | `B167270EBBE1ECF66B86A172882FFC35728AADBDF04231D75AD7F70BEF0563B5` |
| `StereoUpmix.cs` após a alteração | `97FBF25D84EEE9445F467DD8CCB97B090D56C12266AA0DA740D66051A1CD8282` |

A comparação do conteúdo identificou somente a troca dos dois valores Q e do comentário que descreve o filtro. O atraso continua no mpv: `adelay=70|70|0|0|70|70`, com FL/FR/SL/SR em 70 ms e CEN/LFE em 0 ms adicionais. Nenhuma configuração mpv ou APO foi modificada por esta etapa.

## Testes em memória

O código atualizado e um harness C# foram compilados juntos por `Add-Type`. O teste não abriu dispositivos de áudio nem reiniciou o relay. Cada falha de verificação lançaria uma exceção; a execução final terminou com código 0.

Para medir a resposta, foram enviados sinais senoidais float32 com FL = FR, amplitude 0,25 e frequência de amostragem de 48 kHz pela interface pública do fallback. Cada frequência recebeu 3 segundos de sinal em blocos de 100 ms. Os primeiros 2 segundos foram descartados da medição para completar a detecção, a entrada gradual e a acomodação do filtro. O último segundo foi medido por razão de energias RMS entre LFE e sua entrada mono `0,25 × (FL + FR)`.

O alvo abaixo é a resposta calculada dos dois biquads de 120 Hz/Q 0,7071, correspondentes aos parâmetros APO. Não é uma captura da saída física do APO.

| Frequência | Resposta medida do fallback | Resposta alvo | Resultado |
| ---: | ---: | ---: | --- |
| 60 Hz | −0,526626 dB | −0,526626 dB | Passou |
| 80 Hz | −1,565792 dB | −1,565793 dB | Passou |
| 100 Hz | −3,418532 dB | −3,418532 dB | Passou |
| 120 Hz | −6,020767 dB | −6,020767 dB | Passou |
| 160 Hz | −12,383462 dB | −12,383462 dB | Passou |
| 240 Hz | −24,611074 dB | −24,611074 dB | Passou |

O maior erro absoluto foi **0,000000030744 dB**, inferior à tolerância de 0,00001 dB. Também foi verificado por reflexão que os cinco coeficientes de cada biquad correspondem exatamente aos coeficientes esperados para Q 0,7071.

| Verificação funcional | Resultado |
| --- | --- |
| Upmix após 1,7 s de FL = FR = 0,25 | Ativou; FL/FR permaneceram 0,25; CEN = 0,25; SL/SR = 0,125; LFE convergiu para 0,125 |
| Sinal nativo em FC/LFE/SL/SR no meio do bloco após upmix ativo | Todo o buffer preservado byte a byte; estado de síntese resetado |
| `forceNative=true` com entrada somente FL/FR após upmix ativo | Todo o buffer preservado byte a byte; estado de síntese resetado |
| Interface `byte[]` em 20 blocos de 100 ms, deslocamento de 13 bytes | Resultado idêntico byte a byte à interface `float[]`; prefixo e sufixo sentinela preservados |

## Limites da verificação

O relay carrega o código C# ao iniciar. Esta etapa não reiniciou áudio; a alteração do filtro passa a valer quando o relay for iniciado novamente. Não foi realizada comparação auditiva ou medição acústica.

A detecção continua sendo uma heurística: uma fonte nativa com sinal somente nos frontais por tempo suficiente pode receber upmix no modo automático. O comando **Preservar 5.1 nativo** continua sendo o controle para preservar esse caso; o upmix APO das fontes declaradas como estéreo ou mono permanece disponível nesse modo. Quando aparece atividade nos outros canais, a devolução do bloco nativo é imediata, sem fade de saída da síntese.

Esta alteração não mede nem altera headroom, clipping após a mistura de aplicativos, taxa AC-3, buffers ou processamento do decoder. O log consultado da rota anterior à mudança mostra 48 kHz float intercalado convertidos para 48 kHz float planar antes do AC-3; a operação `auto_aresample` observada não mudou a frequência de amostragem.

Referências: [Equalizer APO — parâmetros de filtros e Copy](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/), [mpv — implementação do codificador AC-3 e transporte IEC 61937](https://github.com/mpv-player/mpv/blob/master/audio/filter/af_lavcac3enc.c), [FFmpeg — opções AC-3](https://ffmpeg.org/ffmpeg-codecs.html#ac3-and-ac3_005ffixed).
