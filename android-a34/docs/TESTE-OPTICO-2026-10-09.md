# Verificação física do sinal em 09/10/2026

**Atualização posterior:** a investigação Windows avançou para AC-3. A TV em Sistema de áudio/Auto 1/DD+ Não entregou 279 quadros com CRC correto e seis canais decodificados; o retorno USB silenciado também abriu seis canais. Alto-falantes da TV voltou a produzir silêncio. O registro abaixo preserva os testes anteriores. Veja [o relatório atualizado](../../docs/CM6206-COMUNICACAO-2026-10-09.md) para resultados, limitações e scripts.

O fluxo completo **ainda não está aprovado**. O áudio do PC chegou à TV pelo HDMI 2 e foi ouvido nos alto-falantes da Sony. A captura S/PDIF da CM6206 no Windows permaneceu em silêncio. No A34, a captura USB recebeu DC e ruído sem os tons transmitidos. Nenhum amplificador foi utilizado.

| Trecho | Evidência | Resultado |
| --- | --- | --- |
| PC → HDMI 2 → Sony | WASAPI exclusivo aceitou PCM16 estéreo a 48 kHz; usuário ouviu os tons na TV | Confirmado para este sinal PCM |
| Saída óptica → cabo | Usuário observou luz vermelha e confirmou encaixe em SPDIF IN | Luz presente; conteúdo digital não aprovado |
| CM6206 S/PDIF → Windows | Três capturas exclusivas, entrada sem mute e volume 100%; todas contêm apenas zeros | Recepção do áudio não confirmada |
| CM6206 USB ↔ A34 → DSP | Serviço por 20,25 s, 949.920 quadros capturados/escritos, saída silenciada, zero underruns | Transporte e execução passaram neste teste curto |
| Seis saídas analógicas → amplificadores/caixas | Não conectado/testado | Pendente |

## Sinais, condições e medidas

TV em HDMI 2, Saída Digital de Áudio em PCM; comparados Sistema de Áudio e Alto-falantes da TV. Ao terminar, o usuário retornou a Sistema de Áudio mantendo PCM. A CM6206 ficou no PC para a comparação óptica; o A34 continuou acessível por ADB na rede, com o DSP parado.

Os sinais sintéticos têm cinco segundos, PCM16 estéreo/48 kHz, janelas de 400 Hz à esquerda, 700 Hz à direita e ambos juntos. Foram utilizados picos nominais de −36 dBFS e −24 dBFS. O segundo permitiu a confirmação auditiva nos alto-falantes da TV. Cada reprodução teve três repetições e encerrou automaticamente; captura limitada a três segundos.

Nenhum teste alterou dispositivos padrão ou volume global do Windows. Os controles HDMI e S/PDIF foram consultados: sem mute e em 100%. Não foram instalados drivers nem enviados comandos de escrita aos registradores. Capturas e relatórios ficam locais em `android-a34/artifacts`, ignorados pelo Git.

No A34, preferência e rota efetiva de AudioRecord coincidiram com a única entrada USB. Isso confirma a placa, mas não a fonte interna MIC/Line/S/PDIF. As três capturas com tons ficaram praticamente iguais à referência sem reprodução: DC de aproximadamente 322/260 unidades PCM16 e baixo ruído residual. Nenhuma apresentou identidade dos tons; correlação comum dos dois canais inferior a 0,009. O analisador utiliza um mesmo alinhamento para ambos os canais e não declara bit perfect por correlação.

O serviço de produção rodou por 20.251 ms com perfil temporário silenciado e saída zero: 949.920 quadros de entrada e saída, zero underruns e clipping. Consulta de estado da UI: média 1,38 ms, máximo 2,30 ms. Parada concluída em cerca de 72 ms e perfil anterior restaurado. Não são medidas de latência analógica nem garantia de estabilidade prolongada.

## Fonte interna e estado óptico

Os descritores reais mostraram AudioControl interface 0 e USB capture terminal 10 ligado ao selector 7:

- Pin 1 → feature 8 → MIC terminal 4.
- Pin 2 → feature 15 → Line terminal 6.
- Pin 3 → feature 16 → S/PDIF terminal 5, tipo `0x0605`.
- Pin 4 → feature 2 → mixer MIC/Line.

GET_CUR UAC1 do selector 7 retornou `−1`: seleção atual desconhecida. Nenhum SET_CUR, detach ou fallback foi executado. O app escolhe a placa USB, mas não altera esse seletor. A [especificação USB Audio 1.0](https://www.usb.org/sites/default/files/audio10.pdf), seção 5.2.2.3, define a consulta. O [usbfs do Linux](https://raw.githubusercontent.com/torvalds/linux/master/drivers/usb/core/devio.c) verifica a posse da interface para controles de classe; isso oferece uma explicação possível para a falha, sem comprovar o errno que não foi exposto ao Java.

No Windows, HID leu REG3 como `0x177E`: campo de taxa S/PDIF de 48 kHz e CANREC=0. O [datasheet C-Media](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf), página impressa 16, define esse bit como entrada S/PDIF não gravável. A leitura não identifica a causa; não comprova defeito do cabo, copyright, perda de sincronismo ou erro de driver. Luz vermelha e taxa isolada não validam conteúdo.

As capturas Windows tiveram 143.040, 142.560 e 142.560 quadros, sem descontinuidades reportadas, todas em zero. Na terceira, o usuário ouviu os tons na TV durante a captura. Houve uma desconexão USB inesperada no PC; após reconectar em outra porta, a placa reapareceu e continuou ativa nas verificações posteriores.

## Conclusão e limites

O bloqueio observado está no trecho **TV → óptico → captura S/PDIF da CM6206**. A seleção interna no A34 é uma pendência adicional. O loopback descrito abaixo posteriormente confirmou placa e cabo recebendo o PCM conhecido e demonstrou sensibilidade à sinalização de copyright do sinal sintético. Os indicadores enviados pela TV ainda não foram medidos. Driver Windows não se converte diretamente em driver Android.

HDMI 2 funcionou como entrada. ARC da HDMI 3 oferece retorno de áudio HDMI a equipamento compatível; não é necessário neste caminho óptico. BRAVIA Sync oferece controle entre aparelhos HDMI. [Manual Sony](https://helpguide.sony.net/tv/fbrl2/v1/br-PT/070opt-01.html). Fixa/Variável pertence ao menu Fone de Ouvido/Saída de Áudio; PCM pertence à Saída Digital de Áudio. [Ajustes Sony](https://helpguide.sony.net/tv/fbrl2/v1/pt-BR/060nhm-06-01-06.html).

Não foram aprovados AC-3/IEC61937, seis canais discretos por óptico, fidelidade do decoder, ordem dos conectores analógicos, deriva de relógios ou latência acústica. PCM estéreo com Upmix é diferente de entrada 5.1 nativa. Não houve reprodução comprimida nesta sessão, pois a etapa PCM óptica não passou.

## Ferramentas e verificações

Android debug: modos explícitos `capture`, `capture-source` e `silent-duplex`. Novos scripts em `scripts/optical-tests` coordenam reprodução dirigida ao HDMI, captura limitada e análise privada. O analisador PCM passou oito casos sintéticos, incluindo captura parcial em loop, troca/mistura de canais, ruído, silêncio e deriva. O helper C# compilou e os coordenadores Python passaram na compilação de sintaxe.

Build Android, testes JVM e lint passaram: zero erros de lint e 17 avisos existentes. APK instalado no A34: 0.3.0 debug, SHA-256 `F5DB414EC1D3C3DFAC2DE06A8ED3D01C5B346B8846C7748719FD73766FD1C4CC`.

## Repetição solicitada às 01:14 (horário de Brasília)

O sinal estéreo de −24 dBFS foi transmitido novamente à Sony por WASAPI exclusivo em PCM16/48 kHz. A captura explícita da entrada S/PDIF da CM6206 entregou 142.560 quadros, sem descontinuidades e com limpeza concluída. O volume de captura continuou em 100%, sem mute.

A análise direta encontrou **zero amostras diferentes de zero**, nos dois canais. REG3 permaneceu `0x177E`, com CANREC=0. A placa estava ativa no PC antes e após o teste. A repetição confirmou o bloqueio anteriormente observado, sem identificar a causa. Nenhum amplificador ou alteração de driver/registrador foi utilizado. Evidências privadas: `artifacts/windows-optical-2026-10-09/pcm-retry-04*`.

## Loopback externo confirmado pelo usuário

O usuário ligou o mesmo cabo entre SPDIF OUT e SPDIF IN da CM6206, mantendo o USB no PC e os amplificadores desconectados. O teste enviou os mesmos tons sintéticos de 400/700 Hz, PCM16 estéreo/48 kHz, diretamente ao endpoint de reprodução da placa.

A saída óptica estava desabilitada. O controle temporário usou apenas bits documentados em REG0/1/5: PCM consumidor a 48 kHz, par frontal USB, saída válida habilitada, loopback interno e mistura desabilitados. Não houve INIT completo, escrita em EEPROM ou mudanças em REG2/3/4. Um journal persistiu valores e máscaras antes das escritas; leitura durante a reprodução verificou a configuração. Ao encerrar, somente os bits alterados foram restaurados e conferidos.

Foi feita a comparação A–B–A com o mesmo áudio e cabo, alterando apenas REG0 bit2, referente ao indicador de copyright do nosso sinal sintético:

| Fase | REG0 / REG1 / REG5 durante captura | REG3 | Captura | Restauração |
| --- | --- | --- | --- | --- |
| A: copyright não declarado | `2004 / 3000 / 3000` | `177F`, CANREC=1 | 143.040 quadros; tons corretos L400/R700, sem troca | Verificada |
| B: copyright declarado | `2000 / 3000 / 3000` | `177E`, CANREC=0 | 142.560 quadros; todas as amostras zero | Verificada |
| A novamente | `2004 / 3000 / 3000` | `177F`, CANREC=1 | 143.520 quadros; identidade dos tons recuperada | Verificada |

Os três testes encerraram o transmissor com código zero e não reportaram descontinuidades de captura. A janela de 143.040 quadros da primeira fase A coincidiu exatamente, nos dois canais e com um único deslocamento, com as amostras do WAV de referência. Isso é uma comparação da janela capturada, não aprovação geral de bit perfect, latência, estabilidade prolongada ou AC-3. Na última fase A, o melhor alinhamento inteiro comum encontrou diferenças em 2.836 das 287.040 amostras, aproximadamente 0,99%; sua identidade dos tons foi recuperada, mas fidelidade integral não foi aprovada. O ajuste com correlação assinada positiva descartou uma interpretação de inversão física que poderia surgir da ambiguidade de fase dos tons puros.

**Conclusão:** placa e cabo funcionam para este PCM no Windows. Nesta combinação de channel status, mudar apenas o bit de copyright fez a CM6206 bloquear a gravação; desfazer a mudança recuperou o sinal. Isso sustenta a hipótese de incompatibilidade de sinalização/SCMS com a TV, que apresentou o mesmo CANREC=0 e silêncio, mas não comprova quais flags a Sony envia nem autoriza chamar esses valores de Copy Once/No More Copy. O [datasheet](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf) descreve copyright e SCMS; a [definição ALSA de IEC60958](https://raw.githubusercontent.com/torvalds/linux/master/include/sound/asoundef.h) distingue copyright, categoria e ORIGINAL/L.

A leitura final conferiu REG0=`2000`, REG1=`3002` e REG5=`3000`, iguais às configurações iniciais; nenhum amplificador foi usado. O cabo permaneceu no loop externo. O fluxo da TV e a seleção de entrada no A34 continuam pendentes.

Ferramentas: `scripts/optical-tests/Cm6206OpticalGuard.cs` e `test-external-optical-loop.ps1`. Evidências privadas em `artifacts/optical-loopback-2026-10-09`: `external-03`, `external-copyright-04`, `external-restored-05`, `aba-comparison.json` e `final-readonly`.

Ocorrências do diagnóstico: a primeira tentativa (`external-01`) travou antes da transmissão por agrupamento de relatórios HID no buffer do helper; o journal permitiu restaurar REG0, com confirmação em `recovery-result.json`. O buffer passou a enviar relatórios individualmente. `external-02` recebeu os tons, mas o coordenador interpretou um ExitCode indisponível como erro; a retenção do handle do processo corrigiu a leitura. As três fases da tabela acima usam os helpers corrigidos, com execução e restauração completas.
