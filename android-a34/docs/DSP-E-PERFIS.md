# DSP, perfis e evidências da base Android

A base Android usa o mesmo `DspEngine` Java no laboratório de arquivos e no serviço PCM USB. O núcleo está implementado e foi comparado numericamente com o mpv do repositório. A captura óptica AC-3 pela CM6206, reprodução simultânea no hardware e calibração acústica continuam dependendo da interface e do hub reais.

## Perfil inicial

| Ajuste | Valor no app |
|---|---|
| Formato interno | 48.000 Hz; FL, FR, FC, LFE, SL, SR |
| Entrada | `NATIVE_5_1`, escolhida explicitamente |
| Master | 0,04 = 4%; mute desligado |
| Trims | 1 em todos os canais |
| Atrasos em amostras | 3686, 3686, 278, 0, 3408, 3408 |
| Crossover surrounds | Ativo; LR4 a 90 Hz; envio grave 1 |
| Cópia grave da central | **Desativada**; 120 Hz/envio 1 se ativada |
| EQ LFE | Ativo; Q 2; nove bandas abaixo |
| Margem LFE | **Automática ativada**; manual 1/3 disponível |

As bandas são 20, 25, 30, 40, 50, 60, 80, 100 e 120 Hz, com ganhos respectivos de 6, 6, 6, 5,5, 1,5, -4, 1, 1 e -2,5 dB. Os atrasos e a curva foram herdados da configuração PC. Precisam de nova medição na montagem com o A34; nenhum desses números representa latência completa do sistema.

A central começa com a cópia desativada para resolver explicitamente a divergência entre o preset PC salvo e grafos antigos que ainda continham o ramo central. O app usa o estado do seu perfil validado ao construir o processamento.

A margem automática também é uma decisão explícita da versão Android. O preset PC local tinha `AutoHeadroom=false`, com margem de aproximadamente 1/3 para a soma dos graves. No app, a margem considera tanto as fontes somadas quanto a sobreposição das bandas EQ positivas:

`ganho = 10^(-somaDosGanhosPositivos/20) / (1 + 2*envioSurround + envioCentral)`

Somente recursos ativos entram na conta. No perfil inicial, a soma positiva é 27 dB, o denominador é 3 e o ganho efetivo LFE é aproximadamente 0,014889453. Isso pode deixar o sub mais baixo que o preset PC manual. Ao desativar a opção automática, vale `lfeHeadroom`, inicialmente 1/3. A margem é conservadora; trims altos e transientes ainda podem alcançar o clamp final, cujo contador aparece nos diagnósticos.

## Grafo e modos de entrada

O grafo aplica: upmix explícito → crossover surround → cópia central opcional → delays individuais → margem LFE → EQ LFE → trims/master/mute → clamp. O grave copiado é somado **antes dos delays**, seguindo o atraso do LFE. A central original mantém sua faixa completa. Os filtros são causais e alteram fase; delay explícito zero não elimina a fase dos filtros.

`NATIVE_5_1` exige seis canais. Uma cena 5.1 que deixe FC/LFE/surrounds silenciosos continua preservada. `STEREO_UPMIX` exige dois canais e usa FL=L, FR=R, FC=0,5L+0,5R, SL=0,5L, SR=0,5R e LFE=LR4LP120(0,25L+0,25R). Não há detector de atividade, piso de silêncio, grace period ou fade de decisão. A matriz distribui estéreo, sem recriar canais nativos ausentes.

Na **0.4.0**, essa continua sendo a configuração padrão e o fallback de perfis antigos. A matriz passa a ser editável: `M=(L+R)/2`, `FC=ganhoCentral*M`, `SL=ganhoSurround*(L-d*R)/(1+d)`, `SR=ganhoSurround*(R-d*L)/(1+d)` e `LFE=LR4LP(corte, ganhoGraves*M)`. Os ganhos/d ficam entre 0 e 1, corte entre 40 e 160 Hz. FL/FR permanecem L/R. O divisor limita a soma absoluta dos coeficientes das surrounds ao ganho selecionado; o processamento posterior ainda pode somar graves e alcançar o clamp.

**Preencher caixas** usa ganhos central/surround/graves `1 / 0,5 / 0,5`, d=0 e 120 Hz. **Ambiência** usa `0,7071 / 0,5 / 0,25`, d=1 e 80 Hz; mono coerente cancela nas surrounds. A soma central não faz extração de diálogo; voz diferente entre L/R pode aparecer nas surrounds. Não há algoritmo proprietário Dolby. Os presets alteram somente esses cinco parâmetros; trims, EQ, delays e master mantêm seus valores. O controle de graves gerados não desativa o envio grave do crossover, que continua independente.

Bypass pula crossover, cópia central, delays, margem e EQ. Master, trims e mute permanecem ativos. Se o modo explícito for estéreo, sua matriz e o passa-baixas usado para formar o LFE permanecem ativos.

Perfis são snapshots imutáveis, com arrays copiados e valores finitos dentro dos limites. A publicação é atômica na fronteira do próximo bloco. Ganhos/master/mute/trims preservam o histórico; mudanças estruturais de delay, EQ, crossover ou modo limpam os históricos na thread de áudio. Trocar a quantidade de canais de uma sessão USB exige parar e reiniciar a entrada.

O editor importa/exporta JSON e o armazenamento normaliza um perfil ativo nas preferências Android. Um nome de perfil não implica uma biblioteca de presets múltiplos. Consulte a [API e seus limites](../app/src/main/java/br/com/sistema51/a34/dsp/README.md).

## Arquivos e decodificador

O laboratório aceita RIFF/WAV PCM16 ou float32, a 48 kHz, com 1, 2 ou 6 canais. Aceita layouts extensible frontal estéreo e 5.1 com o par surround back ou side. Mono é duplicado para L/R somente quando o usuário escolhe Upmix; WAV 5.1 exige o modo nativo. Arquivos com outra taxa, mapa incompatível, truncamento ou PCM não finito são rejeitados. O núcleo DSP também tem proteção para entradas não finitas, independente dessa validação de arquivos.

A exportação gera WAV float32 de seis canais. O laboratório acrescenta silêncio até o maior delay do perfil para descarregar os atrasos. A cauda IIR posterior a esse limite não é exportada, e o relatório informa essa política. O tempo de processamento de um arquivo é medido como tempo de parede; não é percentual de CPU nem latência física.

Arquivos AC-3 **elementares** a 48 kHz podem ser testados com `PlatformAc3Decoder`, que usa `MediaCodec` do firmware Android. Contêineres, DTS, E-AC-3 e bursts IEC 61937 não entram nesse teste. O decoder relata canais, taxa, quantidade de frames recebida/produzida e diferenças de duração. Não compara o ganho ou SNR automaticamente com uma referência externa. Os testes anteriores no A34 observaram ganho variável e diferença de duração, portanto `fidelityValidated` permanece falso.

O APK não incorpora uma biblioteca FFmpeg Android. O mpv/FFmpeg do PC é usado somente como referência offline. Uma futura biblioteca de decodificação controlada deve ter contrato explícito para formato, layout, frames e EOS e receber sua própria comparação de ganho/duração. Essa etapa permanece planejada. `EncodedUsbAudioTransport` já define um ponto de extensão para bytes USB ordenados, mas não possui implementação validada de captura óptica AC-3.

## O que os testes demonstram

O autoteste dentro do APK verifica impulsos separados, atrasos exatos, PCM finito, escrita WAV e roundtrip de perfil, com EQ/crossovers desativados. Não se apresenta como uma validação de todo o grafo, do decoder ou do transporte físico.

Os testes JVM do núcleo cobrem 11 cenários: ring wrap e delay máximo de 250 ms; isolamento nativo; sinais abaixo do antigo piso de atividade; matriz estéreo; ganhos RBJ; resposta LR4 analítica; grave antes do delay; central inteira; margem/clamp/entradas extremas; contratos de buffers; snapshots/reset e invariância entre tamanhos de bloco. Passaram 1.251.095 verificações.

A comparação externa usa [compare-mpv.py](../scripts/compare-mpv.py), exclusivamente com `--ao=pcm`, sem abrir dispositivo de áudio. Vetores de quatro segundos contêm três segundos de sinais e um segundo de silêncio. O relatório completo é recriado em `app/build/validation/mpv-full-graph/comparison.json`.

| Grafo | Maior erro absoluto | Menor SNR entre canais com diferença |
|---|---:|---:|
| Nativo, margem manual | 9,31×10⁻¹⁰ | 146,75 dB |
| Nativo, margem automática | 2,91×10⁻¹¹ | 145,31 dB |
| Estéreo, matriz equivalente explícita | 9,31×10⁻¹⁰ | 148,46 dB |
| Cópia central, grafo PC original | 9,88×10⁻⁷ | 77,67 dB |
| Cópia central, controle com Q LR4 exato | 9,31×10⁻¹⁰ | 146,24 dB |

FL/FR/FC foram idênticos amostra por amostra; o erro máximo das surrounds foi 1,46×10⁻¹¹. A pequena diferença do ramo central foi isolada pelo controle: o PC omite Q nos dois low-pass e usa o default FFmpeg 0,707; o Android usa `sqrt(0,5)`. Igualando esse parâmetro, a diferença cai ao arredondamento float. Não houve clipping nem valores não finitos nos vetores.

Todos os 192.000 frames produzidos pelo engine foram comparados. O mpv também exportou os 3.686 frames adicionais do maior delay, totalizando 195.686. A diferença de comprimento e a cauda são relatadas separadamente; o maior pico restante nessa cauda foi 7,70×10⁻¹⁴. O engine processa o span fornecido, enquanto o laboratório de arquivos decide quanto silêncio acrescentar.

Os coeficientes paramétricos seguem o [Audio EQ Cookbook publicado pelo W3C](https://www.w3.org/TR/audio-eq-cookbook/). A comparação numérica comprova este processamento de PCM, sem afirmar equivalência acústica ou transparência da cadeia Android inteira.

## Serviço e próximos testes físicos

O serviço implementa a rota Android AudioRecord PCM16 USB → DSP → AudioTrack float 5.1 USB, em blocos de 480 frames. Antes de liberar amostras reais, exige a confirmação das duas rotas USB; callbacks e verificações durante o laço tratam remoção/mudança de dispositivo. A camada UI/arquivos usa callbacks de conclusão/erro, com trabalho offline em executor separado da thread principal. Esses mecanismos estão no código; a operação contínua pela CM6206 ainda precisa ser exercitada.

Ainda faltam medir simultaneidade captura/reprodução, mapa dos conectores, AC-3 íntegro na entrada óptica, latência ponta a ponta, drift, reconexão, tela apagada, alimentação pelo hub e temperatura. O serviço não possui reamostragem adaptativa ou correção de relógios independentes. A compensação PC de 48.002→48.000 Hz não foi transplantada: ela foi calibrada para outro par de dispositivos.
