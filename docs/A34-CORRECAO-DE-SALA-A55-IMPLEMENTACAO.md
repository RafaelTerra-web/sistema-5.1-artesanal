# Correção de sala com microfone sem fio do A55

Atualização de 10/10/2026: **DSP A34 0.10.0 / Controle A55 0.2.0**. Ver também o [relatório da publicação, upmix AC-3 estéreo e limites de uso](ATUALIZACAO-AC3-ESTEREO-CORRECAO-DE-SALA-2026-10-10.md).

Os APKs passaram pela compilação, lint e testes automatizados; a instalação nos aparelhos permanece separada da publicação para não interromper os processos atualmente em execução. Esta é uma primeira versão experimental de **correção assistida de graves**; a gravação física no A55 e o benefício acústico ainda precisam ser validados.

## Uso após uma instalação autorizada

1. No controle A55, conectar ao DSP e abrir **Medir sala**. O assistente reaproveita a sessão do painel; se ela expirou, pede o código local do A34.
2. Manter uma fonte estável tocando no A34. Conferir previamente o mapa das caixas, especialmente central/sub e surrounds. O assistente não identifica a localização física pelo microfone.
3. Apoiar o A55 na altura dos ouvidos, com a mesma orientação, no ponto principal. Escolher uma caixa e a posição 1.
4. Iniciar a medição pelo botão. A permissão de microfone é pedida apenas para a captura nativa. O A55 começa a gravar antes de solicitar o sinal ao A34.
5. Repetir para posições próximas à esquerda/direita e para as demais caixas. Manter os ganhos físicos e o perfil constantes entre as medições; alterar o perfil invalida o conjunto.
6. Conferir a sugestão. **Aplicar cortes sugeridos** salva somente a correção de sala. **Desativar correção de sala** e **Ativar correção salva** permitem comparação no mesmo volume.

O A34 deve estar executando áudio e, na rota óptica, recebendo sinal válido. O assistente não abre uma segunda conexão USB, inicia a fonte nem aumenta o mestre para tornar o teste audível.

## Sinal de medição e sincronismo

O programa continua atravessando o decoder e o DSP. Durante o teste, um gerador substitui temporariamente o bloco de saída, depois do DSP e antes do mapa físico e do resampler. Assim, o sinal de uma caixa não se espalha por upmix, cópia CEN → FL/FR, envios ao LFE ou EQ existente. Ao terminar, a saída volta ao programa, com transição de 10 ms.

O teste utiliza o mestre/trim existentes como limite, sem aumentar valores salvos. Pico máximo: aproximadamente 0,0316, ou −30 dBFS, antes da conversão USB. Isso não certifica um SPL seguro: o nível acústico também depende dos amplificadores e das caixas.

- Silêncio inicial de 0,5 s.
- Marcador acústico de 0,55 s, varrendo 55–125 Hz com envelope suave.
- Intervalo de 0,4 s.
- Tons de 40–250 Hz em passos próximos de 1/6 de oitava; LFE limita os tons a aproximadamente 127 Hz.
- Cada tom dura 0,45 s, com rampas de 25 ms e intervalo de 0,12 s.
- Satélites também recebem 500/1.000/2.000 Hz para diagnóstico.
- Silêncio final de 0,5 s. Duração: aproximadamente 13,35 s nos satélites e 8,22 s no LFE.

O A55 grava PCM16 mono a 48 kHz, por até 20 s. Uma correlação com o marcador localiza os tons na gravação; o instante de chegada das mensagens Wi-Fi não é usado como atraso acústico. Não há estimativa de distância, ajuste de delay, resposta ao impulso completa ou correção de fase nesta versão.

Perda de sinal/discontinuidade óptica, mute, cancelamento ou limite de duração abortam o teste. A gravação é cancelada ao sair da tela do A55. Cancelamentos identificam a gravação para não cancelar uma medição posterior.

## Microfone e transporte

A captura usa o microfone interno do A55. Quando o Android anuncia suporte a UNPROCESSED, o app solicita essa fonte e confirma a configuração ativa e o roteamento. Captura processada fica disponível para diagnóstico, mas não gera filtros. Uma mudança para captura processada durante o teste também bloqueia a sugestão. O fallback tenta desligar efeitos acessíveis, sem assumir que isso torna a captura bruta.

Não é uma calibração do microfone: a resposta do A55 é desconhecida e pode influenciar os picos encontrados. Três posições e critérios conservadores reduzem erros, mas não comprovam que cada pico vem da sala.

O áudio é enviado pela rede local em blocos PCM codificados em Base64 de até 16.384 bytes. Permanecem o limite HTTP de 32 KiB por requisição, autenticação pelo controle local e verificação da sessão do servidor. A recepção exige offsets em ordem, tamanho exato e SHA-256 ao finalizar. Há uma única gravação em recepção, até 1.920.000 bytes, com expiração de 120 s. A gravação fica em memória e é descartada após a análise; não é publicada ou enviada a serviço externo.

A análise ocorre fora da thread de áudio, em prioridade de segundo plano. O trabalho do DSP mantém seus buffers e históricos.

## Análise e filtros

RoomMeasurementAnalyzer rejeita marcador ausente, saturação relevante, sequência truncada ou poucas bandas acima do ruído. Nas bandas fracas, registra ausência de medida válida em vez de tentar compensar a falta de graves da caixa. O nível de cada tom é extraído por projeção senoidal com janela Hann de 220 ms e normalizado pelo ganho digital do teste.

RoomCorrectionPlanner só sugere um corte quando:

- Há pelo menos três posições **distintas** da mesma caixa, com captura bruta.
- A banda e suas vizinhas são utilizáveis em todas as posições.
- Ela forma um máximo local, excedendo as vizinhas imediatas por pelo menos 0,75 dB.
- A proeminência sobre a referência local de duas bandas para cada lado excede 3,5 dB em todas as posições.

O ganho sugerido é **−min(6, 0,6 × menorProeminência) dB**, com Q 3. Há até três filtros por canal, separados para evitar cortar repetidamente o mesmo pico. Nenhum filtro admite ganho positivo. Esses limiares são escolhas conservadoras de engenharia desta versão, não parâmetros normativos ou uma calibração acústica validada.

Isso procura atenuar picos estreitos e repetíveis de graves. Não tenta preencher cancelamentos, corrigir agudos por um microfone sem calibração ou garantir redução do tempo de reverberação. Tampouco altera automaticamente níveis relativos, atrasos, crossovers, mapa central/sub ou recursos de diálogo. Os tons adicionais dos satélites são diagnóstico, não um ajuste automático de trims.

## Integração com o DSP

O novo campo roomCorrection do perfil contém ativação e até três filtros paramétricos por saída. Perfis legados sem o campo recebem correção vazia/desligada. EQ manual, volume mestre, seis trims, atrasos, nome do perfil e demais configurações são preservados.

Os filtros atuam nas saídas **FL, FR, FC, LFE, SL, SR**, depois do EQ geral e antes dos trims/mestre. Coeficientes são compilados fora da thread de áudio e mudam com rampa de 10 ms. Ativar/desativar a correção não limpa o histórico dos atrasos. O processamento continua respeitando a política existente de fonte: 5.1 discreto não recebe novo upmix por causa da correção.

O A55 apresenta a proposta antes de aplicar e oferece comparação ligada/desligada. O A34 também possui controle de ativação da correção salva. A sugestão e as medições são temporárias no serviço de controle; os filtros aplicados são persistidos no perfil.

## Validação local e pendências

Os testes JVM cobrem sinais conhecidos, início acústico com deslocamento arbitrário, LFE, saturação, truncamento, ausência de marcador, posições duplicadas, captura processada, vales confundidos com picos, upload e hash, revisão de perfil, fluxo completo de proposta/aplicação, comparação ligada/desligada, JSON legado, isolamento dos canais e continuidade dos atrasos. A biblioteca JSON adicionada é dependência apenas dos testes JVM.

Compilação dos dois APKs aprovada; 88 testes JVM no módulo A34 e três no controle A55 passaram (91 no total, incluindo 23 novos testes da correção). O teste Node do painel remoto também passou. Lint: zero erros, 20 avisos no A34 e 19 no controle, principalmente compatibilidade/ferramentas e textos sem recursos de tradução. A verificação de espaços do diff passou.

APKs: `android-a34/artifacts/sistema51-a34-0.10.0-debug.apk` e `android-a34/artifacts/sistema51-controle-0.2.0-debug.apk`; hashes e links de download estão no [relatório de publicação](ATUALIZACAO-AC3-ESTEREO-CORRECAO-DE-SALA-2026-10-10.md). O script de build agora lê as versões dos módulos para não copiar uma atualização com o nome de um APK antigo.

Os resultados não comprovam gravação bruta disponível no Samsung A55 nem áudio físico melhor na sala.

Após instalar os APKs nos aparelhos, validar:

1. Continuidade da rota atual com correção vazia/desligada.
2. Layout do assistente e permissão no A55; microfone interno e fonte real anunciada.
3. Um teste curto em uma caixa, confirmando saída física isolada e retorno ao programa.
4. Rejeição de ruído, saturação e interrupção real da fonte.
5. Três posições na mesma caixa e comparação da sugestão no mesmo volume.
6. Conjunto completo, confirmação do mapa central/sub e surrounds e comportamento após reiniciar os apps.

Antes de ampliar para EQ de banda larga, atrasos automáticos ou certificação de nível, é necessário obter uma referência confiável de microfone e validar o sincronismo físico.

Referências: [Android: fontes de captura](https://developer.android.com/reference/android/media/MediaRecorder.AudioSource#UNPROCESSED), [estudo de medições com microfones de celulares](https://pmc.ncbi.nlm.nih.gov/articles/PMC5102154/), [Mäkivirta et al.: equalização modal](https://aes.org/publications/elibrary/elibrary-page/?id=12226), [W3C: filtros RBJ](https://www.w3.org/TR/audio-eq-cookbook/).
