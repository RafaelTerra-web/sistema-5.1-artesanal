# Fire TV → A34: implementação contínua 0.8.3 — 09/10/2026

> **Adendo 10/10/2026:** a política atualizada passa a fazer upmix de AC-3 mono/estéreo, como o Spotify entregue em AC-3 estéreo, e mantém AC-3 5.1 discreto intocado. O texto histórico abaixo registra a regra que estava em vigor quando a 0.8.3 foi escrita. Consulte [a atualização 0.10.0 e o relatório da correção de sala](ATUALIZACAO-AC3-ESTEREO-CORRECAO-DE-SALA-2026-10-10.md). Auto 2/PCM óptico continua sem captura aprovada nesta CM6206.

## Estado desta continuação

O usuário pediu a integração do decoder confiável, DSP e saída contínua e informou que os amplificadores estão ligados. A **0.8.0 implementa essa cadeia**; a 0.8.2 acrescentou o controle LAN com PIN curto, e a 0.8.3 acrescenta volumes por canal, equalizador gráfico aplicado aos seis canais e sincronização do perfil local com mudanças remotas. A validação física está em andamento. Este documento descreve o código e os testes independentes disponíveis, sem promover os ensaios curtos da 0.7.0 a aprovação de reprodução contínua.

A configuração que recebeu estéreo e 5.1 íntegros na etapa anterior permanece: **Fire TV em Dolby Digital Plus**, **Bravia em Sistema de áudio / Auto 1 / DD+ Out desativado**, HDMI 2, óptica TV OUT → SPDIF IN da CM6206, placa no A34 por OTG simples. A fonte efetivamente observada na óptica foi **AC-3 a 48 kHz**, mesmo com DD+ selecionado no Fire TV. O hub PD e a alimentação permanente ainda exigem ensaio próprio.

**Política atual pedida pelo usuário:** upmix apenas para mono/estéreo PCM ou identificado como não Dolby. AC-3/E-AC-3 não recebe upmix, inclusive em mono/estéreo; multicanal nativo mantém os canais. A decisão acompanha o codec original, não o silêncio/energia dos canais. Na rota óptica atualmente implementada, AC-3 estéreo é mapeado às frontais e os outros canais ficam silenciosos; nenhum canal é fabricado. Essa regra substitui a política experimental anterior que fazia upmix de Dolby estéreo.

## Caminho implementado

```text
CM6206 SPDIF IN
  → USBFS isócrono IN 0x85, interface 2 / alternate 1
  → parser incremental IEC61937 + validação do quadro AC-3 e CRC
  → FFmpeg AC-3 de software, PCM float a 48 kHz
  → identificação dos canais + política mono/estéreo/5.1
  → DSP, perfil existente, trims/EQ/cortes/atrasos/mute
  → resampling contínuo para diferenças entre os relógios
  → mapa USB e PCM16 de seis canais
  → USBFS isócrono OUT 0x06, interface 1 / alternate 4
```

`Cm6206DuplexTransport` verifica a topologia medida da unidade `0d8c:0102`, os formatos e o seletor UAC 7. Salva journal durável antes de selecionar o **pin 3 S/PDIF**; restaura e verifica o seletor original no fechamento. O transporte usa a conexão autorizada pelo Android, sem root, sem mixer AudioRecord/AudioTrack no caminho óptico e sem gravações em EEPROM/reset. Capturas, journals, chaves e APKs permanecem locais e ignorados pelo Git.

`Iec61937Ac3Stream` mantém um buffer limitado. Separa o payload, valida taxa de 48 kHz, tamanho, mapa e CRC AC-3; acompanha o espaçamento de 6.144 bytes entre bursts. Carrier desconhecido nunca chega ao DSP como PCM. A cadeia atual aceita **AC-3 mono, estéreo ou 5.1 completo**. E-AC-3 direto, DTS, outros mapas multicanal e PCM óptico direto ainda não são rotas aprovadas; a configuração final da TV deve entregar AC-3 compatível.

`SoftwareAc3Decoder` usa FFmpeg **8.0.3** compilado das fontes para arm64, com DRC e aplicação de dialnorm desativados. Cada quadro deve devolver exatamente **1.536 amostras**, 48 kHz e os canais esperados. A contagem é conferida antes de usar o PCM. O decoder Samsung permanece separado e experimental no laboratório histórico. Fontes/build/licenças do decoder estão descritos em `android-a34/third-party/README.md` e nos avisos incluídos no APK.

`OpticalAudioSession` concentra a cadeia em uma thread de áudio. Uma mudança de canais, descontinuidade ou falta de quadro válido recente bloqueia a saída e limpa os estados de DSP/decoder/resampler antes de retomar. A preparação começa com mute no transporte. O bloqueio também atende mute/parada recebidos pelo controle, preservando o ganho e os ajustes salvos. URBs já entregues ao controlador USB podem conter uma cauda curta; o transporte informa uma janela de até 16 ms para o áudio já submetido.

`ClockDriftResampler` mantém fase fracionária e histórico entre blocos. A razão significa frames de saída / frames de entrada; fila mais cheia reduz a razão. O PI usa fila filtrada, espera dois segundos de preparação, limita a mudança a 20 ppm/s e a correção a **±1.000 ppm**. O alvo padrão é 1.536 frames de fila de software, que não inclui o áudio já submetido em URBs. A correção altera o PCM intencionalmente e precisa de medição na montagem final.

## Controle e diagnóstico

O botão principal do app inicia **Fire TV óptico**. O painel LAN permite escolher essa rota automática ou o caminho PCM Android manual. A API `/api/start` recebe `route: "optical"` ou `"pcm"`, sessão de controle e confirmação explícita; sem rota, usa optical. Não há início automático ao abrir o app, reinício de sessão após falha, aumento de ganho ou retirada automática do mute salvo.

O estado mostra codec/canais, upmix ativo, mute efetivo, falha de execução, quadros decodificados/submetidos, erros USB, interrupções, fila, correção de relógio e medidores. Os ajustes continuam compartilhando o perfil local com a UI Android. O PIN aparece em Diagnóstico; o painel troca o PIN por um token temporário de sessão e conserva a proteção contra gravações com revisão antiga.

O comando **Silenciar agora** altera somente mute e elimina uma retirada de mute que ainda estivesse pendente no formulário. Respostas atrasadas de polling não podem substituir a confirmação de um comando mais novo. A parada cancela imediatamente o worker e envia o comando de serviço ordenado para cancelar também um início ainda aguardando entrega.

## Verificação independente concluída

- Testes Node do painel passaram: permissão da rota PCM separada da óptica, estado automático da fonte, mute imediato, conflito de revisão e resposta de polling atrasada.
- Cinco testes JUnit do resampler passaram por execução isolada: preservação dos seis canais constantes, seno com razão não unitária dividido em blocos irregulares, preparação/jitter, limite/NaN e simulação de dez minutos para cada deriva de **+350 e −350 ppm**. A fila permaneceu dentro da capacidade; ao final, correção dentro de 15 ppm da necessária e fila dentro de 20 frames do alvo.
- Os resultados completos de build, decoder no A34, cadeia óptica física e instalação serão registrados nesta seção após a execução pelo responsável pela integração. Testes anteriores da 0.7.0 estão em [Fire TV e controle local](FIRE-TV-A34-CONTROLE-LOCAL-2026-10-09.md).

## Validação física em andamento

Registrar aqui a versão/hash do APK efetivamente instalado, preservação do perfil, contagem de amostras do decoder, janelas de execução, quadros válidos/CRC, estado das filas, correção de relógio, erros/underruns, mute/parada e restauração do seletor. Separar a execução silenciada da confirmação de som nas caixas.

Ainda precisam de evidência na montagem final: reprodução audível, ordem dos conectores analógicos, volume físico/guard analógico, transições estéreo ↔ 5.1 e pausa/seek, continuidade prolongada, latência audiovisual/acústica, temperatura e alimentação com hub PD. Nenhuma contagem de frames, medidor ou CRC isoladamente confirma essas etapas.
