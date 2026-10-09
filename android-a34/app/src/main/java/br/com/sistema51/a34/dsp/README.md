# Núcleo DSP A34

Java puro, sem classes Android. Formato fixo: **48.000 Hz**, `float[]` PCM intercalado, saída **FL, FR, FC, LFE, SL, SR**. `AudioProfile` valida todos os valores e copia seus arrays; editar um `Builder` não altera um perfil publicado.

```java
AudioProfile profile = AudioProfile.defaultProfile();
DspEngine engine = new DspEngine(480); // máximo de 10 ms por bloco
engine.setProfile(profile);
engine.process(inputFloat, 6, outputFloat, frames);
// Para uma fonte efetivamente estéreo:
engine.setProfile(profile.toBuilder()
    .inputMode(AudioProfile.InputMode.STEREO_UPMIX).build());
engine.process(stereoFloat, 2, outputFloat, frames);
```

O modo é explícito. `NATIVE_5_1` aceita somente seis canais e `STEREO_UPMIX` somente dois. Uma cena 5.1 com somente FL/FR ativos permanece 5.1. Não existe detecção por silêncio, piso de atividade ou espera para decidir o formato. Mono e outras taxas/layouts devem ser convertidos pela camada de entrada, com decisão explícita, antes do DSP. Buffers diferentes são obrigatórios ao expandir estéreo. O processamento nativo suporta entrada e saída no mesmo array.

O grafo segue esta ordem:

1. Upmix, somente se solicitado para a fonte estéreo.
2. Crossover LR4 das surrounds e envio da parte baixa ao LFE.
3. Cópia opcional LR4 dos graves da central ao LFE, mantendo a central inteira.
4. Atrasos individuais em amostras.
5. Margem do LFE, nove biquads paramétricos RBJ.
6. Trims individuais, master, mute e clamp final em `[-1, 1]`.

O crossover usa duas etapas Butterworth de segunda ordem, Q = `sqrt(0.5)`, por ramo. Em 90 Hz cada ramo tem ganho 0,5 (-6,02 dB); a soma tem resposta all-pass. A cópia grave entra **antes** dos atrasos, seguindo o atraso do LFE e não o da caixa original. Filtros causais alteram fase mesmo quando o atraso explícito é zero. Os coeficientes low-pass, high-pass e peaking seguem o [Audio EQ Cookbook de Robert Bristow-Johnson, publicado pelo W3C](https://www.w3.org/TR/audio-eq-cookbook/).

O upmix mantém `FL=L`, `FR=R`, `FC=0,5L+0,5R`, `SL=0,5L`, `SR=0,5R` e `LFE=LR4LP120(0,25L+0,25R)`. A matriz corresponde aos coeficientes do APO, com níveis limitados para entrada normalizada. A diferença em relação ao fallback `StereoUpmix.cs` do PC é a seleção explícita por formato: sem heurística de atividade, grace period de 1,5 s ou fade de 100 ms. O LFE utiliza Q exato `sqrt(0.5)` em vez da aproximação `0,7071` do arquivo antigo. O upmix distribui estéreo; não recupera os seis canais de uma faixa originalmente multicanal.

Na versão 0.4.0, os valores acima são defaults compatíveis com perfis antigos. `upmixCenterGain`, `upmixSurroundGain`, `upmixBassGain` e `upmixDifference` aceitam 0–1; `upmixBassCutoffHz` aceita 40–160 Hz. `FC=gC*(L+R)/2`, `SL=gS*(L-d*R)/(1+d)`, `SR=gS*(R-d*L)/(1+d)` e `LFE=LR4LP(corte,gB*(L+R)/2)`. O modo Ambiência da UI usa d=1, cancelando mono coerente nas surrounds; FL/FR permanecem intactos. Essas opções não atuam na entrada nativa. Exemplo:

```java
engine.setProfile(profile.toBuilder()
    .inputMode(AudioProfile.InputMode.STEREO_UPMIX)
    .upmixCenterGain(.7071f).upmixSurroundGain(.5f)
    .upmixBassGain(.25f).upmixDifference(1).upmixBassCutoffHz(80).build());
```

`bypass=true` pula crossover, cópia grave, atrasos, margem LFE e EQ. Conserva trims, master, mute, normalização final e a matriz do modo estéreo já escolhido, incluindo seu passa-baixas LFE.

## Defaults e limites

- Master 0,04 (4%), mute e bypass desativados; trims 1.
- Atrasos `[3686, 3686, 278, 0, 3408, 3408]`; faixa permitida 0–12.000 amostras (250 ms).
- EQ LFE ativo: frequências `[20,25,30,40,50,60,80,100,120]` Hz, ganhos `[6,6,6,5.5,1.5,-4,1,1,-2.5]` dB, Q 2.
- Surrounds: crossover ativo em 90 Hz, envio 1 (0 dB). Central: cópia desativada; corte 120 Hz e envio 1 quando ativada.
- Margem automática ativada no app. Ganho manual 1/3 disponível ao desativar a opção automática.
- Master e margem manual: 0–1; trims: 0–4; envios de graves: 0–1; cortes: 40–120 Hz; EQ: frequência 10–200 Hz, ganho -12–6 dB, Q 0,3–10. NaN/infinito são rejeitados no perfil.

Os atrasos e a curva EQ são valores herdados da montagem anterior; devem ser medidos novamente com a CM6206 e os amplificadores reais. A margem automática calcula `10^(-somaDosGanhosPositivos/20) / (1 + 2*envioSurround + envioCentral)`, incluindo somente recursos ativos. No default, ganho LFE = aproximadamente 0,01489. Essa opção difere do preset PC manual porque inclui a sobreposição das nove bandas positivas. É uma margem conservadora de amplitude, não um limitador dinâmico; trims altos e transientes ainda podem alcançar o clamp final. A contagem de samples clampados permite verificar essa condição.

## Concorrência, memória e troca de perfil

Uma única thread de áudio chama `process`. Threads de controle podem chamar `setProfile` e `reset`. `setProfile` compila coeficientes fora da thread de áudio e publica um snapshot por escrita `volatile`. A próxima chamada válida de `process` aplica o snapshot inteiro antes de qualquer amostra. `getProfile()` retorna o perfil mais recentemente enviado.

Mudanças em ganhos, envios, margem, trims, master e mute preservam os históricos. Mudar modo, bypass, ativação/frequência/Q/ganho do EQ, crossover ou atrasos limpa os históricos na fronteira do bloco. `reset()` também solicita limpeza de histórico e contadores para a próxima fronteira. Ajustes estruturais podem produzir uma breve interrupção correspondente ao preenchimento dos atrasos; a UI deve comunicar isso.

Buffers de atraso, estados IIR, amostra de trabalho e medidores são alocados uma única vez. Chamadas válidas de `process`, inclusive aplicação de snapshot/reset, não alocam objetos. Atrasos e filtros mantêm precisão double para evitar overflow intermediário ao somar entradas float finitas extremas. Entrada PCM NaN/infinita vira zero e incrementa `getNonFiniteInputSamples()`. A saída sempre é finita e limitada.

Telemetria: `getFramesProcessed()`, `getClippedSamples()`, `getNonFiniteInputSamples()` e `getChannelPeak(channel)` (último bloco). Os três contadores são cumulativos até `reset`; peaks são uma leitura simples por canal, sem snapshot transacional conjunto.

O núcleo não decodifica AC-3, não mede latência física, não reamostra nem corrige deriva de relógios. Essas responsabilidades ficam na camada de transporte/entrada.

## Testes JVM

`DspEngineTest.main` executa 11 cenários com sinais determinísticos e expectativas independentes: atraso exato, ring wrap e máximo de 250 ms; separação nativa e sinais abaixo do antigo piso; matriz estéreo; ganhos RBJ medidos por RMS; resposta LR4 analítica com warp bilinear; bass-before-delay; central integral; headroom e PCM extremo; contratos de entrada; troca de snapshot/reset e igualdade amostra por amostra entre partições de blocos.

`DspGoldenVectors.main(outputDirectory)` exporta entradas e saídas `f32le` para confrontar o grafo completo com outro processador. O manifesto descreve as opções exatas; esses arquivos são vetores de bancada, não áudio para reproduzir nos amplificadores.
