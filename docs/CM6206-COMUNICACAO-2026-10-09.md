# Comunicação óptica CM6206 — 09/10/2026

**Estado final da sessão:** seis caixas confirmadas pelo usuário em USB direto; frontais também confirmadas na rota óptica completa. REG2.DRIVERON foi necessário para abrir o analógico. Mapa surround, calibração e estabilidade óptica permanecem abertos. A versão A34 0.4.0 foi instalada e validada offline; veja o [relatório consolidado e plano atualizado](PROGRESSO-A34-E-UPMIX-2026-10-09.md). As subseções seguintes preservam a sequência dos testes; “pendente” ou “preparado” em uma etapa não substitui os resultados posteriores.

A rota **PC HDMI 2 → Sony KD-55X705E → óptico → CM6206 → USB no PC** recebeu Dolby Digital 5.1 nesta bancada. Uma janela de aproximadamente nove segundos entregou 279 quadros AC-3 completos, todos com CRC correto, decodificados em 428.544 amostras por canal, seis canais a 48 kHz. Os seis canais apresentaram janelas independentes no PCM decodificado. Isso valida a recepção/decodificação desse sinal sintético, sem comprovar saídas físicas, latência ou operação prolongada.

## Configuração confirmada pelo usuário

### Atualização: teste auditivo das frontais

Posteriormente, o usuário conectou apenas o amplificador frontal em **FRONT OUT**. Os testes pela óptica/USB (`front-listen-14`, ganho linear 4%, e `front-listen-15`, 20%) negociaram saída USB de seis canais, mas não produziram som audível. Um teste direto PCM estéreo pela USB (`front-direct-usb-16`) também ficou sem som. Volume do endpoint USB aproximadamente 99,55%, sem mute.

A leitura HID mostrou `HEADPON=1` nas flags da resposta e `REG2=0x6004`, com `DRIVERON=0`. O [datasheet C-Media](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf), página impressa 15, documenta que essa combinação silencia as saídas de linha, mantendo a seleção de headphone. O [driver Linux CM6206](https://github.com/torvalds/linux/blob/master/sound/usb/quirks.c) também habilita os drivers de saída ao inicializar `0D8C:0102`.

Nos ensaios `front-driver-on-17` e `front-driver-on-18`, somente `REG2.bit15` foi alterado: `0x6004 → 0xE004`. Leitura confirmou o valor durante a reprodução PCM; **o usuário confirmou os tons nas duas caixas frontais**. Ao terminar, o bit foi restaurado e `0x6004` foi conferido. Não houve INIT completo, escrita em EEPROM, reset, mudança de S/PDIF/copyright ou volume global.

O novo `test-analog-driver.py` registra o estado antes de escrever e restaura apenas esse bit, preservando alterações concorrentes nos demais bits. O teste usa o mesmo arquivo sintético fixado por SHA-256. O relay agora permite teste frontal explícito, com os outros quatro canais zerados, mantendo seu modo silencioso anterior.

Os ganhos frontais são aplicados linearmente no PCM por `pan`, depois da decodificação. Não usar o slider mpv como ganho linear: ele tem curva cúbica. O teste offline conferiu pico de aproximadamente 0,004 para ganho 4% e zeros exatos nos demais quatro canais.

**Rota completa com som confirmada:** `front-optical-driver-on-19` manteve DRIVERON habilitado enquanto transmitia AC-3 à Sony e executava o relay óptico/USB. A saída foi PCM16/48 kHz/5.1(side), com ganho linear de 20% apenas em FL/FR. O usuário confirmou som nas duas caixas frontais, nesta rota completa. Foram recebidos/enviados 719.040 frames transportadores, 2.876.160 bytes, sem descontinuidade reportada na captura. Fonte e relay encerraram com código zero; o estado foi restaurado para REG2=`0x6004` e conferido. O relatório privado registra a confirmação humana separadamente das verificações automáticas.

O modo `test-analog-driver.py --relay-fronts --volume 20` aplica essa inicialização apenas durante o teste e preserva o modo direto USB anterior. Os demais quatro canais são zerados por software. Som nas duas frontais está aprovado neste teste; orientação esquerda/direita, quatro saídas restantes, calibração e estabilidade prolongada continuam pendentes. A alteração de DRIVERON ainda precisa ser portada ao ciclo de execução do aplicativo A34.

Cinco testes isolados de inicialização/restauração passaram, com HID e processos simulados: modo direto, relay correto, falha do relay, DRIVERON previamente ativo e preservação de alterações concorrentes nos demais bits. O controle requer o módulo Python HIDAPI (`hid`), disponível nesta bancada; os validadores de áudio utilizam NumPy/SciPy. Executáveis e capturas permanecem na cópia local de bancada, fora do Git.

### Teste de seis canais e áudio falado preparado

O ensaio `six-optical-driver-on-20` habilitou todos os canais, ganho linear de 20%, DRIVERON ativo. Recebeu/enviou 719.040 frames; o relay abriu os seis canais, encerrou com código zero e restaurou o registrador. O usuário considerou o volume muito baixo e não confirmou que todas as caixas tocaram. **O mapa físico completo continua pendente.**

A pedido do usuário, foi preparado um novo teste com a voz local **Microsoft Maria Desktop (pt-BR)**, anunciando frontal esquerda, frontal direita, central, subwoofer e as duas surrounds. Cada fala ocupa somente seu canal; o nome do subwoofer é anunciado por FL/FR, seguido de tom de 60 Hz apenas no LFE. Há três segundos de silêncio inicial, pausas e um segundo final. Duração PCM 23,722 s / AC-3 23,744 s, seis canais, 48 kHz, 640 kbit/s.

Fonte privada: `spoken-2026-10-09/spoken-channels-ready-02.ac3`, SHA-256 `a927e03f9e5ba5c3b51c496f74f5178159e1610ac31e3a8b17a4b97aba829e72`. O gerador `prepare-spoken-channels.ps1` usa SAPI e codificação mpv em arquivo, sem reprodução. O modo `test-analog-driver.py --spoken-six --volume 50` usará ganho linear de 50%, teste nativo limitado a 30 s e repetição da fonte. O pico decodificado foi conferido **em arquivo**: aproximadamente 0,125 nas caixas e 0,100 no LFE, sem clipping ou erro CRC.

Dez testes simulados do coordenador passaram, incluindo duração/ganho do modo falado e restauração em falhas. O usuário pediu que fosse avisado quando pronto e autorizaria a reprodução depois. **Esse novo áudio falado foi apenas preparado e verificado; não foi reproduzido nas caixas.**

### Resultado posterior: áudio falado e comparação sem central/sub

`spoken-six-optical-21` executou 30 segundos, 1.439.040 frames transportadores, saída com ganho linear 50%, limpeza e restauração confirmadas. O usuário relatou reprodução confusa, anúncio percebido na central, grave excessivo e ausência das falas seguintes. O log automático passou, mas **não houve confirmação do mapa físico**. O LFE deve ser reduzido antes de nova reprodução com CEN/BASS.

Foi preparado `spoken-four-satellites-03.ac3` apenas com FL/FR/SL/SR e com FC/LFE zerados também após a decodificação. O usuário confirmou CEN/BASS desconectado. A tentativa 22 ficou interrompida, sem relatório final; a tentativa 23 encontrou falha de acesso HID. Depois de reconectar USB, a tentativa 24 voltou a acessar os registradores, mas apresentou **erros CRC AC-3 e underruns**, apesar da ausência de flags de descontinuidade na captura. O usuário ouviu apenas as frontais. A limpeza/restauração de REG2 foi verificada.

Esse resultado não permite atribuir a ausência das traseiras apenas à pinagem. Primeiro separar a saída USB física do transporte óptico que falhou nesta repetição. O próximo diagnóstico deve comparar os dois pares surround da saída 7.1 diretamente por USB, com central e LFE zerados, e aguardar autorização do usuário para reproduzir.

O usuário autorizou a repetição direta. A fonte `spoken-rear-map-7point1-04.wav` foi preparada com oito slots, máscara `0x63F`, ordem FL/FR/FC/LFE/BL/BR/SL/SR. Apenas BL/BR falam na primeira dupla, SL/SR na segunda; frontais/central/LFE ficam zerados. O teste 25 não abriu a saída USB (erro de E/S `0x800703e3`); o teste 26 terminou com código zero e log confirmou `48000Hz 7.1 8ch s16`. DRIVERON foi restaurado e conferido. O resultado auditivo dos dois pares ainda precisa ser informado pelo usuário.

O usuário pediu todos os canais e confirmou FRONT/REAR/CEN-BASS reconectados. `direct-usb-all-map-27` reproduziu diretamente por USB a fonte falada de 31,035 segundos, com ambas as posições surround testadas. A central teve pico reduzido de 0,05 na fonte; o LFE teve apenas um tom de 60 Hz com pico 0,005, seguido de silêncio. O ganho comum foi 0,50. Isso reduz muito o grave comparado ao primeiro ensaio falado. Saída confirmada no log: `48000Hz 7.1 8ch s16`; processo terminou com código zero e DRIVERON foi restaurado/conferido. Aguarda-se o relato auditivo das posições; a negociação de oito slots não prova oito saídas físicas.

Após repetições 28/29 do mesmo teste direto, o usuário confirmou **“foi tudo”**, com central e subwoofer mais baixos. Essa diferença foi deliberada na fonte: voz central 20% do pico das demais falas e tom LFE muito reduzido após o grave excessivo anterior. As seis caixas físicas foram reportadas audíveis, mas o par exato BL/BR ou SL/SR do conector REAR ainda não foi distinguido. O ensaio 29 terminou com código zero e restauração de DRIVERON conferida. A calibração relativa dos amplificadores e o transporte óptico estável ainda precisam de trabalho.

### Correção da exceção do painel Windows

Durante o teste, o usuário informou `CmdletProviderInvocationException`/`IOException` (“Não há mais dados disponíveis”), passando por `RegistryKey.GetValueNames` e `Windows.Forms.Timer.OnTick`. O processo aberto era o painel antigo `Controle do sistema 5.1.ps1`.

A descoberta Sony foi modificada nas duas cópias de código para consultar somente valores nomeados com `RegistryKey.OpenSubKey` em modo somente leitura, fechar os handles e tolerar mudanças de endpoints durante a enumeração. O callback do timer também captura falhas transitórias e continua no próximo tick, mostrando estado de atualização. Não foi habilitada depuração JIT nem alterado o Registro. Teste isolado do handler passou para falha simulada, recuperação, ação em andamento e descoberta Sony somente leitura. A versão já carregada na janela requer fechar/reabrir o painel para usar a correção.

- TV: **Sistema de áudio**.
- Saída de áudio digital: **Auto 1**.
- Dolby Digital Plus Out: **Não**.
- Óptico: TV OUT → **SPDIF IN** da CM6206.
- CM6206 ligada ao PC por USB; amplificadores inicialmente desconectados, conectados nos testes auditivos posteriores.
- Gerenciador antigo do sistema Windows desligado pelo usuário.

O manual Sony documenta Auto 1 para áudio comprimido e saída óptica Dolby Digital/DTS. A opção DD+ “Não” converte DD+ para DD; “Auto” pode silenciar a óptica enquanto DD+ é enviado pelo ARC. O teste utilizou **AC-3**, portanto não comprova que desligar DD+ tenha sido isoladamente a correção. [Manual oficial Sony, páginas 25, 31 e 46](https://www.sony.com/electronics/support/res/manuals/W000/W0006624M.pdf).

## Comparação realizada

| Captura privada | Condição | Resultado |
| --- | --- | --- |
| `pcm-tv-ddplus-off-07` | PCM estéreo, DD+ desativado | 143.040 frames de transporte, todas as amostras zero; CANREC=0 |
| `ac3-tv-ddplus-off-08` | Primeiro teste AC-3 | 93 quadros AC-3, CRC correto, seis canais decodificados; CANREC=1 |
| `ac3-tv-fullsequence-09` | Usuário selecionou Alto-falantes da TV | 430.560 frames de transporte, todas as amostras zero; CANREC=0 |
| `ac3-tv-system-audio-11` | Usuário voltou para Sistema de áudio | 279 quadros AC-3 completos, CRC correto, seis canais; CANREC=1 |

As capturas exclusivas não reportaram descontinuidades. Volume da entrada 100%, sem mute. Os scripts não modificaram dispositivos padrão, volumes globais ou registradores. A reprodução sintética foi dirigida explicitamente ao HDMI da Sony e encerrou automaticamente.

No último PCM decodificado, cada canal teve pelo menos 45 janelas de 20 ms com RMS acima de 0,01 e todos os demais canais abaixo de 0,001. Isso demonstra separação no arquivo, sem determinar a correspondência dos conectores P2. A captura começou no meio da sequência repetida; uma comparação deve permitir atravessar a fronteira do loop.

## Copyright e fidelidade

Os testes anteriores A–B–A com a própria saída óptica da placa demonstraram sensibilidade à sinalização de copyright de um sinal sintético. Porém, **não há bloqueio geral de Dolby nesta configuração**: o AC-3 da Sony foi recebido e decodificado. O PCM continuou bloqueado nas condições testadas. Os bits completos de channel status enviados pela Sony não foram medidos.

CANREC é somente leitura e não informa a causa da recusa. REG0 descreve a **saída** óptica da placa, não os indicadores recebidos da TV; alterar REG0 não é uma correção documentada da entrada. [Datasheet original C-Media](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf), páginas impressas 14–16.

Os payloads AC-3 capturados não são idênticos ao arquivo transmitido, embora seus CRCs sejam válidos e os seis tons sejam recuperados. A Sony alterou o fluxo em algum ponto; não está determinado se houve apenas mudança de metadados ou processamento adicional. A comparação PCM de toda a janela teve correlação agregada de aproximadamente 0,76, portanto **fidelidade integral, alinhamento e relógios continuam sem aprovação**. Não interpretar os picos/RMS do carrier IEC61937 como áudio analógico.

## Código e evidências

### Retorno USB ao vivo

Revisão posterior dos logs encontrou um aviso de underrun no relay 13 e outro no transmissor. Ausência de descontinuidade na captura WASAPI não significa ausência de underruns de reprodução. Esse resultado permanece um teste funcional curto, sem aprovação de estabilidade.

O protótipo `live-system-audio-13` concluiu a rota **óptico → captura USB → mpv/AC-3 → saída USB da CM6206**. O log confirmou entrada AC-3 de seis canais e saída WASAPI exclusiva `48000Hz 5.1(side) 6ch s16`, no endpoint analógico da placa. A verificação CRC ficou habilitada; não houve erro de decoder. O player encerrou com código zero e a limpeza foi concluída. Saída obrigatoriamente silenciada (`volume=0`, `mute=yes`).

Foram recebidos/enviados 394.560 frames transportadores, 1.578.240 bytes; 256 quadros AC-3 completos passaram na verificação CRC posterior. Nenhuma descontinuidade foi reportada pelo driver. **10.948 ms de tempo de captura corresponderam a 8,22 segundos de dados recebidos**, portanto o teste não aprova continuidade desde o início nem latência. O tempo máximo de escrita no pipe foi aproximadamente 0,50 ms. A diferença entre tempo e dados precisa ser investigada em uma execução mais longa, incluindo inicialização USB e relógios.

Na tentativa anterior `live-system-audio-12`, o player abriu a mesma saída de seis canais, mas gastou cerca de cinco segundos na análise inicial e excedeu o prazo de drenagem de quatro segundos. A limpeza encerrou somente o player pertencente ao teste. O helper foi corrigido para `--demuxer-lavf-analyzeduration=0.1` e `--demuxer-lavf-probesize=8192`; a repetição acima terminou normalmente. O resultado agora exige também confirmação do dispositivo, formato, CRC e ausência de erros no log, não apenas envio de bytes.

O trabalho local anterior, incluindo aplicativo Android 0.3.0, foi preservado e trazido para esta cópia do repositório. O GitHub ainda descrevia etapas anteriores. Posteriormente, o upmix 0.4.0 foi instalado e validado no A34; os resultados estão no relatório consolidado.

- `scripts/optical-tests/run-windows-signal-capture.py`: fonte sintética fixada por SHA-256, preflight somente leitura, endpoints explícitos, captura de 1–10 segundos e falhas/limpeza registradas.
- `WindowsSpdifCapture.cs` e `capture-windows-spdif.ps1`: captura exclusiva PCM16 estéreo a 48 kHz, preservando os bytes transportadores; API anterior de três segundos mantida.
- `validate-captured-ac3.py`: extração IEC61937, CRC ANSI16, decodificação estrita para arquivo e comparação. A verificação CRC segue [FFmpeg ac3dec.c](https://github.com/FFmpeg/FFmpeg/blob/master/libavcodec/ac3dec.c).
- `WindowsSpdifRelay.cs` e `test-windows-spdif-relay.ps1`: protótipo limitado de captura → decodificação mpv → USB PCM de seis canais, com saída silenciada.

Evidências brutas foram preservadas na cópia local de bancada, sob `android-a34/artifacts/windows-optical-2026-10-09` e `windows-optical-relay-2026-10-09`. Capturas, áudios decodificados e logs permanecem privados e ignorados pelo Git.

Verificações offline: seis casos do validador, doze casos do coordenador, seis casos do analisador IEC61937 e nove casos do validador do relay; compilação C# e sintaxe PowerShell passaram. A escuta direta posterior confirmou seis caixas; ainda faltam mapa físico exato, calibração, latência/deriva e captura AC-3 ao vivo no Android. O backend Android comprimido continua pendente; AudioRecord PCM não deve receber IEC61937 e convertê-lo diretamente para float/DSP.
