# Retomada do sistema 5.1 — 09/10/2026

Este é o ponto de retomada mais recente. Os relatórios anteriores contêm etapas históricas com configurações que foram substituídas. Consultar este arquivo primeiro, depois o [relatório da rota](RELATORIO-ROTA-FIRE-TV-A34-2026-10-09.md), a [bancada PCM](PC-CM6206-PCM.md) e o [plano de volume Fire TV](FIRE-TV-CONTROLE-DE-VOLUME-A34.md).

## Resultado confirmado pelo usuário

A rota **Opera → extensão de upmix → VB-CABLE → DSP no PC → USB CM6206 → amplificadores** voltou a tocar em todas as categorias de caixas. Depois do equilíbrio e da redução de ganho, o usuário confirmou som limpo e sub mais presente. Isso é uma confirmação de escuta nessa música, não calibração acústica, estabilidade prolongada ou aprovação da rota óptica.

A óptica TV → CM pode continuar conectada fisicamente, mas o funcionamento atual depende da rota PCM do PC. Não iniciar outro gerenciador óptico junto dela. A cadeia independente **Fire TV → HDMI 2 Bravia → óptica → CM6206 → A34 DSP → USB CM6206 → caixas** ainda precisa ser implementada e validada.

## Configuração final da bancada

| Parâmetro | Valor confirmado |
|---|---|
| Mestre DSP PC | Ganho linear **0,15** |
| Trim central DSP | 0 dB |
| Trim sub DSP | **+3 dB** |
| Graves da central enviados ao sub | **50%**, abaixo de 90 Hz, uma vez antes dos atrasos |
| Crossover satélites | LR4, 90 Hz |
| Subsônico LFE | 20 Hz |
| Margem antes do trim LFE | 1/5,5 |
| FL/FR | **76,8 ms**, 3.686 amostras em 48 kHz |
| FC e LFE | **5,8 ms**, 278 amostras em 48 kHz |
| SL/SR | **71 ms**, 3.408 amostras em 48 kHz |
| USB Windows | Oito canais PCM16/48 kHz, máscara 0x63F |
| Volumes físicos Windows | FL/FR e os dois pares surround −23 dB; FC −14 dB; LFE −12 dB |
| Modo do relay | PCM / **Native**; extensão decide o upmix antes do mixer |
| Inversão FC/LFE no Windows | **Desligada** |

O volume do mpv usa escala cúbica: o controlador converte o ganho linear por `100*cbrt(ganho)`. Não confundir 0,15 com 15% da interface do mpv. Não copiar os ganhos físicos Windows para o A34 sem nova calibração.

## Regras obrigatórias

- **AC-3 e E-AC-3/DD+ nunca recebem upmix**, mesmo em mono/estéreo. Preservar o áudio decodificado; cortes, trims e atrasos continuam possíveis. Não fabricar central/surround a partir de uma cena nativa só nas frontais.
- Aplicar upmix somente a mono/estéreo com codec não Dolby confirmado. Outro conteúdo multicanal também permanece nativo. Metadados desconhecidos/ambíguos não autorizam upmix.
- Seis slots no mixer não provam que a fonte era 5.1. A extensão considera codec e canais da origem selecionada. O mixer Windows perde essa informação; o APO genérico foi desativado por esse motivo.
- O DSP atua sobre PCM decodificado, nunca sobre palavras IEC61937 que carregam AC-3.
- Manter a ordem lógica **FL, FR, FC, LFE, SL, SR** em processamento, medidores e arquivos. Aplicar mapa físico apenas na saída.
- Preparar testes sintéticos, avisar o usuário e **esperar “toca”**. Não disparar graves fortes; começar baixo. Ajustes na música já autorizada não exigem outra autorização genérica.
- Não usar INIT/reset/EEPROM da CM indiscriminadamente. O guard habilita apenas REG2.DRIVERON, bit 15, com journal anterior e restauração verificada depois de fechar os streams.

## Mapa central/sub e surrounds

A informação “central ligada no R” levou inicialmente a uma inversão incorreta. O teste isolado confirmou **slot USB 2 → central e slot USB 3 → sub**, índices a partir de zero. Com o swap ligado, a fala central saiu no sub. Portanto, manter **swap desligado na bancada Windows atual**.

No A34, repetir a identificação isolada antes de decidir. O app já permite inverter FC/LFE somente na saída. Os seis canais Android/HAL podem ter mapa diferente dos oito slots Windows.

O par físico REAR ainda precisa de identificação definitiva. Atualmente o PC duplica SL/SR nos pares BL/BR e SL/SR para compatibilidade provisória. Identificar qual par alimenta REAR, remover a duplicação quando demonstrado e não somar ambos na mesma caixa.

## Código, instalação e testes existentes

- `scripts/pc-cm6206-system.ps1`: controlador unificado; configurações privadas locais e estado com PID/horário para rejeitar processos antigos.
- `scripts/pc-cm6206-engine.py`: supervisão, encaminhamento de aplicativos, leitura de estado e IPC. Falha temporária ao escrever JSON agora não encerra o áudio.
- `scripts/pc-cm6206-analog.ps1`: guard HID Windows nativo, separado, com journal e restauração do bit. A alternativa hidapi falhou em escrita nessa condição.
- `scripts/pc-cm6206-pcm.ps1`: grafo, cortes, atrasos, trim LFE e envio parcial central. `ValidateOnly` permite preparar configuração sem reprodução.
- Extensão `configuracao-pc/browser-upmix-extensao`, pacote **0.2.1**: instalada manualmente pelo usuário no Opera. O pacote atualizado no disco não garante que uma aba antiga executa esse código; conferir versão carregada e diagnóstico antes de atribuir comportamento a uma alteração recente.
- Painel/controle remoto usam controlador canônico para evitar preferências antigas Stereo ou outro estado. Uma janela aberta antes da atualização só lê código novo ao ser reaberta.
- APK A34 **0.6.0/versionCode 6** instalado. Vetor de atrasos solicitado aplicado ao perfil salvo com backup e demais ajustes preservados. Seis trims manuais já existem. A captura óptica/saída simultânea no telefone ainda não foi aprovada.
- Testes locais: 73 Node, incluindo 53 da extensão; 30 do supervisor Python; 5 de roteamento. Testes PowerShell de estado, caminhos, volume e painel passaram. Sinais e atrasos foram renderizados em arquivos, sem caixas. Android: 27 JUnit, lint sem erros; teste instrumentado dos atrasos passou.

Os IDs de endpoints, caminhos de binários, estados, journals, capturas e seriais ficam locais/ignorados. Não publicar esses dados nem mídia de terceiros. A documentação e o código ficam no [GitHub](https://github.com/RafaelTerra-web/sistema-5.1-artesanal).

## Próximas etapas, em ordem

### 1. Consolidar a rota PCM recuperada

Antes de mexer, ler configuração e estado vivos, confirmar um único gerenciador e preservar o perfil final. Verificar versão/diagnóstico da extensão com uma fonte estéreo não Dolby e uma AC-3/E-AC-3 conhecida. Confirmar troca de fonte sem canais sintetizados no Dolby; incluir Dolby estéreo como caso obrigatório. Testar REAR em pares separados somente após “toca”.

Medir sessão prolongada: bloqueios de escrita próximos a 200 ms e frames descartados já ocorreram. Registrar continuidade, filas e recuperação. Som audível não encerra essa pendência. Não aumentar novamente o mestre como solução automática para equilíbrio.

### 2. Isolar e estabilizar a óptica no PC

O usuário adiou o loop óptico para recuperar o upmix. Quando autorizar retomar, desligar amplificadores e mover a ponta da TV para **CM SPDIF OUT → CM SPDIF IN**, preparando sinal sintético conhecido. Aguardar “toca”. Comparar PCM/captura conhecida, captura isolada e reprodução simultânea; depois repetir pela TV. Documentar CRC AC-3, intervalos de 1.536 frames, máscaras, relógios, filas e USB.

A captura isolada da TV já trouxe quadros corrompidos e intervalos irregulares. Duplex de seis canais abriu brevemente, mas encerrou com erro de bitstream. O guard HID recuperado não resolveu a integridade. Não desativar checagem CRC para declarar sucesso, nem concluir copyright como causa. O usuário não tem outro cabo USB e **Fire TV ainda não está disponível**.

Critério de avanço: formato real identificado, captura íntegra, mapa ouvido e reprodução contínua sem perdas recorrentes ou reconexões. Caso a TV entregue PCM estéreo, não chamar upmix de recuperação do 5.1 original.

### 3. Implementar transporte e controle USB no A34

Integrar HID com permissão Android, claim apenas da interface correta, journal persistente e recuperação após falha. Implementar o backend real de `EncodedUsbAudioTransport` com interfaces/endpoints UAC da CM, preservando carrier IEC61937 sem volume/resampling. Demultiplexar quadros válidos e distinguir PCM estéreo de AC-3 pela estrutura/metadados.

Validar decoder ARM64 e licenças: o decoder Samsung experimental perdeu 1.536 frames no fixture. Resolver duração/priming/mapa/ganho antes de aceitá-lo. Propagar **codec original** até a decisão do upmix, incluindo Dolby estéreo; apenas contar canais após decoder não satisfaz a regra pedida.

### 4. Completar DSP, calibração e saída Android

Manter atrasos e ordem lógica; confirmar mapa FC/LFE/REAR no telefone, seis canais reais, captura/saída simultâneas e drift. Implementar envio parcial central independente dos graves frontais, com headroom e sem duplicar o ramo antigo. Não transplantar a compensação de relógio específica do PC.

Formalizar os seis trims em dB com perfis, reset por canal, mute/solo temporários, backup e calibração. O master remoto não pode alterar o equilíbrio. Validar picos, EQ, suavização de ganhos e recuperação de USB, mantendo partida silenciosa/baixa.

### 5. Validar a montagem Fire TV e alimentação

Com Fire TV disponível, testar HDMI 2 → Bravia em **Sistema de áudio / Auto 1 / DD+ Não** → óptica CM → A34. Confirmar formato realmente recebido para cada player. Ensaiar mono/estéreo não Dolby, Dolby estéreo e 5.1, mudanças de formato, pausa/seek, hub PD com carga e host simultâneos, tela apagada, temperatura, sessões longas e retorno de energia. O PC só sai da cadeia após esses testes.

### 6. Fazer o controle Fire TV comandar o master do A34

Seguir [FIRE-TV-CONTROLE-DE-VOLUME-A34.md](FIRE-TV-CONTROLE-DE-VOLUME-A34.md). Primeiro identificar se volume/mute usam IR, CEC ou telemetria útil no equipamento real. A óptica não leva esses comandos; não deduzir slider a partir da amplitude musical.

Se IR for comprovado, estudar ponte receptora → API local autenticada do A34. CEC precisa de ponte no HDMI e teste de mensagens. App comum Fire TV não é interceptador universal de teclas volume/power. O A34 mantém a autoridade de master/mute, deduplica eventos, confirma aplicação e preserva os seis trims. Nenhuma ponte física foi implementada nesta sessão.

## Prompt sugerido para o próximo chat

> Continue o projeto https://github.com/RafaelTerra-web/sistema-5.1-artesanal. Leia primeiro docs/RETOMADA-PROXIMO-CHAT.md e os relatórios vinculados. A rota PCM no PC voltou a tocar; preserve mestre 0,15, sub +3 dB e envio de 50% dos graves da central. AC-3/E-AC-3 nunca recebem upmix, inclusive Dolby estéreo; preserve multicanal. Atrasos FL/FR 76,8 ms, FC/LFE 5,8 ms, SL/SR 71 ms. Não inverter central/sub no Windows sem novo teste: mapa confirmado FC slot 2, LFE slot 3. Consolide versão/diagnóstico da extensão e estabilidade PCM; depois retome os testes de integridade óptica e a implementação USB/decoder/DSP no A34. Prepare testes sintéticos e espere meu “toca”. O loop óptico foi adiado, Fire TV ainda não está disponível e não tenho outro cabo USB. Atualize e publique relatórios; implemente equilíbrio manual por canal e controle Fire TV conforme os planos, sem declarar a cadeia A34 pronta antes dos testes físicos.
