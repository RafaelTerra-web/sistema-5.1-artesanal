# Sistema 5.1 — aplicativo do A34

Versão atual **0.5.0**: crossover LR4 opcional de FL/FR/FC, filtro subsônico LFE e troca FC/LFE apenas na saída USB para a central conectada no R. O laboratório decide pelo número real de canais decodificados: 1/2 usam upmix; 6 permanecem nativos, inclusive quando o perfil selecionado era estéreo. O serviço USB PCM ainda exige modo e formato explicitamente compatíveis; a captura óptica comprimida continua pendente. Novos controles ficam desligados na migração para conservar perfis existentes.

Versão **0.5.0**, atualizada em **09/10/2026**. Aplicativo Android real com painel, edição do perfil, DSP, bancada de arquivos e diagnóstico USB. O histórico de validação no Galaxy A34 `SM-A346M`, Android 14, está nos relatórios abaixo. A CM6206 real foi reconhecida pelo telefone em testes anteriores usando um adaptador USB-A fêmea/USB-C macho OTG, sem hub alimentado.

O upmix agora tem controles de central, surrounds, graves, separação e corte, com presets **Preencher caixas** e **Ambiência**. No modo Ambiência, informação idêntica em L/R fica fora das traseiras; conteúdo estéreo com diferenças pode permanecer nelas. Perfis antigos conservam a matriz anterior. Na sessão PCM USB, selecione **Upmix estéreo** somente para uma entrada conhecida como estéreo; no laboratório, a decisão usa os canais decodificados; os presets não alteram master, trims, atrasos ou EQ. Veja [o relatório consolidado](../docs/PROGRESSO-A34-E-UPMIX-2026-10-09.md).

Nesta versão, o estado USB é consultado em segundo plano, o perfil é salvo sem I/O síncrono na tela e a criação/parada do áudio usam uma fila de controle separada. A captura e a escrita têm tempo limite e buffers reutilizados. As abas preservam rolagem; os diagnósticos são resumidos com detalhes expansíveis.

**Os atrasos ficam bloqueados por padrão.** “Editar atrasos” exige confirmação; sair da aba Perfil ou deixar o aplicativo em segundo plano bloqueia sliders e edição numérica novamente. Trims e outros controles continuam disponíveis. Importação/restauração de perfil permanecem ações deliberadas separadas.

## O que funciona

- Painel com volume mestre, mute, bypass, seis medidores e estado da entrada/saída.
- Perfil persistente e importação/exportação JSON pelo seletor de arquivos Android.
- Motor de seis canais a 48 kHz: atrasos, trims, nove bandas paramétricas no LFE, LR4 nas surrounds, envio de graves ao LFE, cópia opcional da central e margem automática.
- Preservar 5.1 ou upmix mono/estéreo explícito; a atividade dos canais não muda o modo de uma cena nativa.
- Arquivos WAV PCM16/float32 e AC-3 elementar de 48 kHz; saída WAV 5.1 e relatório exportáveis. O codec Samsung é identificado como experimental por causa das diferenças de ganho/duração observadas.
- Descritores USB, interfaces/endpoints, capacidades anunciadas pelo Android e pedido de permissão do periférico.
- Serviço foreground PCM USB com entrada e saída na mesma interface, rota verificada, saída obrigatória de seis posições de canais, métricas e parada na desconexão. O caminho foi exercitado com a CM6206 real: entrada PCM de dois canais, upmix explicitamente selecionado e saída de seis canais, com perfil temporariamente silenciado.
- Autoteste no próprio aplicativo e testes de instrumentação exclusivos do APK debug.

**A entrada óptica AC-3 da CM6206 ainda não tem backend de captura validado.** O Android padrão não garante que um AudioRecord PCM preserve os bits de S/PDIF não-PCM. A extensão `EncodedUsbAudioTransport` prepara o contrato para esse backend; o app mostra essa etapa como pendente e não passa áudio encapsulado pelo DSP como se fosse PCM. Relógio adaptativo, decodificação controlada FFmpeg/DTS, manutenção de áudio durante troca de APK e controle IR/ESP32 não estão implementados nesta versão.

## Usar no A34

Abra **Sistema 5.1 · A34**. A aba **Diagnóstico** permite rodar o autoteste e selecionar um arquivo. O laboratório escolhe pelo formato decodificado: preserva seis canais e aplica upmix a mono/estéreo. O modo do perfil continua explícito para o serviço USB PCM. O teste grava um WAV, sem tocar as caixas. **Exportar áudio** salva esse resultado pelo seletor Android.

Em **Perfil**, ajuste delays, trims, cortes, EQ e margem. O padrão conserva a calibração como referência inicial, volume mestre de 4% e cópia da central desativada. A margem LFE automática começa ligada; isso difere intencionalmente do antigo preset com reforços positivos e AutoHeadroom desligado. Os valores físicos precisam de nova medição na rota final.

Depois de conectar a interface pelo hub, consulte o diagnóstico e autorize USB. O botão iniciar só aceita captura **PCM** se o Android anunciar entrada compatível e saída de seis canais, e se as duas rotas efetivas forem a interface selecionada. A presença de conectores ópticos ou um nome de chip não aprova a captura comprimida. A permissão Android de captura é solicitada apenas ao iniciar com uma interface presente; notificações informam a execução e oferecem parar.

## Compilar e testar

Requisitos: JDK 17–23, Android SDK Platform 36 e Build Tools 35.0.0. Projeto Gradle 8.13 / Android Gradle Plugin 8.11.1, Java 8, Android 9/API 28 ou superior. O Gradle Wrapper está incluído; não há dependências externas no runtime do app. JUnit aparece apenas nos testes do PC.

No Android Studio, abra esta pasta. No Windows:

```powershell
.\scripts\build.ps1 -SdkPath 'C:\caminho\Android\Sdk' -JdkPath 'C:\caminho\jdk-21'
.\scripts\test-device.ps1 -Serial 'SERIAL_ADB'
```

Ou use `gradlew.bat :app:assembleDebug :app:testDebugUnitTest :app:lintDebug` com o JDK configurado. No Linux/macOS: `sh gradlew :app:assembleDebug :app:testDebugUnitTest :app:lintDebug`.

O script copia o APK para `artifacts/sistema51-a34-0.5.0-debug.apk`. A atualização por `adb install -r` conserva os dados se mantiver pacote e assinatura. O APK debug usa a chave de desenvolvimento local; para distribuir uma versão de produção será necessária assinatura própria. Chaves, `local.properties`, APKs, builds e resultados locais são ignorados pelo Git.

Para comparar o DSP do **APK instalado** com mpv no PC:

```powershell
python .\scripts\collect-device-results.py --serial SERIAL_ADB
python .\scripts\compare-device.py
```

Os scripts coletam somente os arquivos de teste que o próprio app produziu. A comparação usa o mesmo PCM decodificado pelo A34 para isolar o DSP da diferença do codec Samsung. Requer Python/NumPy e o mpv do projeto, ou `--mpv` apontando para outro executável.

## Validação desta versão

A 0.5.0 passou compilação, 25 casos JUnit (incluindo preservação nativa, crossover frontal/subsônico, roteamento e guard analógico), e lint com zero erros/17 avisos. Os resultados da instrumentação no A34 e o alcance de cada teste estão no [relatório de progresso](../docs/PROGRESSO-A34-E-UPMIX-2026-10-09.md). O guard `Cm6206AnalogDriver` é uma base independente do transporte; ainda não está ligado ao HID Android nem ao serviço PCM.

Resultados anteriores, dos quais a nova versão conserva o núcleo:

- Compilação debug, JUnit e lint passaram; lint ficou sem erros.
- Núcleo DSP: 11 cenários independentes, incluindo delays/cauda, EQ, crossover, matriz, mute/bypass, troca de perfil, PCM inválido e clipping.
- Grafo completo JVM versus mpv: erro máximo aproximadamente `9,31e-10` nos perfis nativos e estéreo equivalente; a diferença de Q na cópia opcional da central foi isolada e documentada.
- Aplicativo no A34, UID próprio: persistência e rejeição de perfil inválido, WAV estrito, AC-3 para seis canais, DSP completo offline e início bloqueado sem USB.
- PCM retornado pelo APK versus mpv, **435.302 frames completos**: FL/FR/central iguais; erro máximo global `3,73e-9`, SNR das surrounds acima de 147 dB. O teste não aprova fidelidade do codec, USB ao vivo nem latência acústica.

O autoteste acessível na UI verifica seis canais/delays, formato e round-trip JSON; os testes completos ficam nos scripts, JUnit e instrumentação. A interface foi conferida na tela do A34, incluindo a confirmação de edição dos atrasos e seu bloqueio automático.

## Testes com a CM6206 real

**Atualização de 09/10/2026:** no Windows, a Sony em **Sistema de áudio / Auto 1 / DD+ Não** entregou AC-3 válido pela óptica. Foram decodificados seis canais separados e o protótipo ao vivo abriu o retorno USB PCM de seis canais na CM6206, com saída silenciada. A seleção Alto-falantes da TV produziu silêncio na comparação. Isso não valida o backend comprimido Android nem a continuidade/latência; consulte [comunicação e resultados medidos](../docs/CM6206-COMUNICACAO-2026-10-09.md).

No Windows, o driver Microsoft já carregado aceitou PCM16/48 kHz em seis canais com máscara `0x60F`; a captura SPDIF estéreo e a saída abriram juntas. Sem cabo óptico, a captura SPDIF não produziu pacotes. Um pacote oficial StarTech com INF para `0D8C:0102` também foi baixado e inspecionado, sem substituir o driver Microsoft.

No A34, o sistema anunciou entrada de um/dois canais e saída de dois a oito. A aplicação usa a máscara de índices `0x3F` anunciada para os seis slots USB; essa seleção foi validada com AudioTrack real. No teste do serviço com saída silenciada, a versão otimizada capturou e escreveu **945.600 frames** durante cerca de **20 segundos**, sem underruns. A parada retornou ao chamador em **0,74 ms** e terminou em aproximadamente **72 ms** nesta execução.

A consulta do estado na thread de UI caiu de média **37,07 ms** na versão intermediária para **1,73 ms**, com máximo **3,87 ms**, depois de remover a cópia textual profunda de JSON e separar as chamadas USB/Binder. Esses números medem a consulta, não todo o tempo de renderização nem garantem ausência de travamentos em qualquer condição.

Na etapa inicial não havia sinal óptico nem amplificadores conectados; a saída dos testes era zero. A captura recebida pelo Android não foi identificada como fonte óptica. Captura AC-3 íntegra, ordem dos conectores analógicos, latência acústica, estabilidade prolongada e compensação de relógios permanecem pendentes. A leitura de registradores HID funciona no Windows; no A34, a interface HID estava ocupada pelo driver e o diagnóstico não a removeu.

Em 09/10/2026, com HDMI e óptico conectados, os tons chegaram à TV pelo HDMI 2. A captura S/PDIF no Windows permaneceu em silêncio e a entrada USB no A34 não apresentou os tons. Depois, um loop externo da própria placa confirmou placa e cabo recebendo PCM estéreo; mudar apenas a sinalização de copyright do sinal sintético alternou áudio e silêncio na captura. Os indicadores enviados pela TV ainda não foram medidos e o fluxo completo continua sem aprovação. Veja as evidências e os testes em [verificação óptica](docs/TESTE-OPTICO-2026-10-09.md).

## Organização

```text
app/src/main/java/br/com/sistema51/a34/
  dsp/        motor e perfil imutável, independente do Android
  io/         WAV estrito, decoder de arquivos e bancada offline
  usb/        descritores, permissão e extensão de transporte comprimido
  service/    captura/reprodução PCM USB e ciclo de execução
  ui/         Activity e contrato com a aplicação
  ProfileStore.java / AppFacade.java / A34Application.java
app/src/debug/    instrumentação e fixture AC-3 sintética
app/src/test/     testes JVM e exportação de vetores
scripts/         build, instalação/teste e comparação numérica
docs/            DSP/perfis e validação USB
```

Leia [DSP e perfis](docs/DSP-E-PERFIS.md) e [USB e validação](docs/USB-E-VALIDACAO.md). A rota planejada é Fire TV → Sony → óptico → CM6206 ↔ A34 → seis saídas analógicas; ela continuará exigindo testes físicos, independente de os testes de arquivo passarem.

As mudanças de desempenho, interface e bloqueio dos atrasos estão registradas em [otimizações e proteção](docs/OTIMIZACOES-E-PROTECAO-2026-10-09.md).
