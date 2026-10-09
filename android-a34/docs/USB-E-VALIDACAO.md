# USB, execução PCM e validação da interface

Esta base usa o mesmo motor DSP para arquivos e para o serviço PCM USB. Os testes do motor não dependem da CM6206. Captura e reprodução pela interface física, ordem dos conectores, latência e estabilidade contínua precisam ser medidos com a CM6206 e o hub alimentado conectados ao A34.

Atualização de 08–09/10/2026: a unidade real `0d8c:0102` foi conectada ao A34 por adaptador OTG simples, na bateria. O sistema expôs captura de dois canais e saída até oito; o serviço testou dois canais de entrada e seis slots de saída simultâneos por 20 segundos, sem underruns, usando saída zero. Essa execução não identifica a origem óptica da captura nem comprova os conectores. O hub com carregamento simultâneo não foi testado.

## Conexão física

Para o áudio, conecte a CM6206 a uma porta de dados do hub USB-C e ligue o hub ao A34. A alimentação externa do hub precisa ser compatível com a operação do telefone como host USB. O Android em modo host enumera os dispositivos conectados; o diagnóstico do aplicativo usa essa enumeração. [Visão oficial do modo USB host](https://developer.android.com/develop/connectivity/usb/host).

O cabo A34 → computador usado para ADB não substitui essa conexão. Para manter a manutenção pelo computador enquanto a porta USB-C atende ao hub de áudio, prepare a depuração por Wi-Fi antes de trocar os cabos, conforme a seção final.

## Condições que liberam o serviço PCM

O aplicativo exige todas estas condições antes de liberar os dados PCM processados na saída:

| Condição | Verificação atual |
| --- | --- |
| Interface física de áudio USB | Dispositivo presente no `UsbManager`, com classe de áudio no dispositivo ou em uma interface. |
| Permissão USB | Concedida pelo usuário no botão de autorização e confirmada pelo `UsbManager`. |
| Permissão de captura Android | `RECORD_AUDIO` concedida. A captura USB também utiliza a infraestrutura de gravação do Android. |
| Entrada e saída na mesma interface | Dispositivos `TYPE_USB_DEVICE` ou `TYPE_USB_HEADSET`, identificados por endereço/nome e agrupamento da placa de áudio. |
| Correspondência sem ambiguidade | Nome/endereço compatível; a alternativa inicial aceita apenas uma interface física de áudio USB e uma única placa USB exposta pelo Android. |
| Saída de seis canais | Seis canais anunciados em contagens ou máscaras de canais. Apenas estéreo anunciado não libera a saída 5.1. |
| Entrada compatível com o modo do perfil | Seis canais anunciados para `NATIVE_5_1`; dois canais para `STEREO_UPMIX`. O serviço não muda o modo automaticamente. |
| Formato inicializado | `AudioRecord` e `AudioTrack` aceitam os formatos e conservam a quantidade solicitada de canais. |
| Rota real confirmada | `getRoutedDevice()` confirma os dispositivos USB escolhidos para entrada e saída. |

Se uma condição falhar, o aplicativo mostra o motivo e encerra a solicitação. O serviço não escolhe alto-falante, Bluetooth ou outro dispositivo para continuar a sessão.

Quando existem várias interfaces USB de áudio, a primeira validação deve ser feita com apenas a CM6206 conectada. A base ainda não oferece um seletor completo de múltiplas placas e endereços.

Antes da confirmação das rotas, a reprodução é preparada somente com um bloco de zeros. Os dados processados são enviados após confirmar ambas as rotas. Durante a execução, a rota é conferida a cada bloco e imediatamente antes de escrever na saída.

## O que o diagnóstico mostra

`UsbAudioController` registra identificadores de produto/fabricante, interfaces, configurações alternativas e endpoints: direção, tipo, tamanho máximo de pacote e intervalo. Endpoints isócronos de entrada e saída tornam a interface uma **candidata** a operação simultânea; isso não comprova a operação.

Após a autorização USB, o controlador lê os descritores sem reivindicar os endpoints. O parser atual detalha formatos UAC1 Type I, quantidade de canais, resolução e taxas anunciadas. Não há parser completo de todos os formatos UAC2.

`androidUsbAudioDevices` complementa os descritores com os dispositivos que o Android realmente expõe: endereço, entrada/saída, taxas, codificações, contagens e máscaras de canais. Um endereço Android pode identificar uma placa/subdispositivo de áudio e diferir do caminho `/dev/bus/usb/...` do `UsbDevice`. A correspondência utilizada fica no campo `routeMatch`.

Capacidade anunciada, formato inicializado e rota confirmada são etapas diferentes. Mesmo passando por todas elas, ainda é necessário conferir os seis conectores com sinais identificados e medir a captura/reprodução simultânea na interface real.

## Formato, blocos e buffers atuais

O caminho implementado é:

```text
Entrada USB PCM16 a 48 kHz
  → conversão para float
  → DspEngine
  → AudioTrack PCM_FLOAT 5.1 a 48 kHz
  → saída USB
```

O motor sempre produz seis canais na ordem interna `FL, FR, FC, LFE, SL, SR`. A entrada nativa mantém os seis canais. O modo Upmix utiliza a matriz estéreo explicitamente escolhida no perfil. Para a CM6206 real no A34, a saída usa a máscara de índices de seis slots `0x3f` anunciada pelo sistema; o formato posicional 5.1 permanece alternativa para dispositivos que não anunciam esses índices. A identificação física dos conectores surround continua parte do teste de canais.

- Cada bloco tem **480 quadros**, equivalentes a **10 ms** a 48 kHz.
- Os arrays de captura, entrada, saída e medição são reutilizados pela thread de áudio.
- A leitura e a escrita usam operações não bloqueantes com espera curta e prazo de 1,5 segundo; perda de progresso interrompe a sessão. O fluxo do aplicativo não cria uma fila ilimitada de blocos.
- Os buffers solicitados de captura e reprodução têm capacidade mínima nominal de **quatro blocos/40 ms cada**, podendo aumentar conforme o mínimo exigido pelo Android.
- Essas capacidades não são uma medição da latência de ponta a ponta. Não se deve somá-las e apresentar o resultado como latência real.
- O DSP usa prioridade de áudio; RMS, picos, quadros capturados/escritos, clipping e underruns são publicados aproximadamente a cada **500 ms**.

O contador `reproducedFrames` indica quadros entregues ao `AudioTrack`; ele não é uma medição da saída analógica. `clippingSamples` conta amostras limitadas pelo motor por exceder ±1; não mede saturação anterior à captura. `saturatedSamples` registra amostras próximas do limite de saída.

**Ainda não estão implementados:** calibração entre relógios de captura e reprodução, resampling adaptativo, correção de deriva, controle de ocupação de uma fila intermediária ou recuperação automática após erro. A execução contínua precisa medir perda de quadros, crescimento de underruns, estabilidade dos buffers e comportamento térmico. O estado atual informa `latencyMeasured=false` e a calibração de relógios pendente.

O caminho PCM do Android pode incluir conversão e processamento. A solicitação de fonte `UNPROCESSED` e a preferência por USB não constituem comprovação de transmissão bit perfect.

## Captura óptica AC-3/IEC61937

O aplicativo **não implementa captura óptica AC-3 direta** nesta versão. O estado apresentado é:

> Captura óptica AC-3 direta pendente validação da interface; PCM USB disponível.

A leitura PCM de dois canais por `AudioRecord` não demonstra que a CM6206 esteja entregando os bytes originais de um fluxo óptico codificado. Essa comprovação exige observar e validar o transporte real, inclusive preservação dos bytes, ordenação dos pacotes e continuidade das rajadas IEC61937.

`EncodedUsbAudioTransport` é apenas o ponto de extensão para um backend futuro. Ele declara estados `NOT_VALIDATED`, `VALIDATED_PCM_ONLY` e `VALIDATED_IEC61937_AC3`, evidência de validação, abertura, leitura em buffer reutilizável com timeout e encerramento capaz de desbloquear a leitura. **Não existe implementação desse backend** na base atual.

Somente depois da validação física o fluxo codificado deve ser conectado à extração dos quadros AC-3 e à decodificação. O decodificador Samsung existente trabalha com **arquivos AC-3 elementares**, em caráter experimental, e reporta diferenças de contagem de amostras. Ele não comprova captura óptica, transparência de ganho ou reprodução contínua sem lacunas.

## Permissões, foreground e encerramento

A base tem mínimo Android API 28 e alvo API 36. O manifesto declara `RECORD_AUDIO`, `MODIFY_AUDIO_SETTINGS`, `FOREGROUND_SERVICE`, tipos de foreground `MEDIA_PLAYBACK` e `MICROPHONE`, `POST_NOTIFICATIONS` e `WAKE_LOCK`. A autorização USB é específica do dispositivo e separada dessas permissões.

O início parte de uma ação do usuário na interface. O serviço entra em foreground após as verificações de permissão, hardware e formatos; a notificação oferece **Parar DSP**. Durante o processamento, um wake lock parcial renovado mantém a CPU disponível. O retorno `START_NOT_STICKY` evita reativação autônoma da captura; não existe início após boot.

**Parar**, desconexão da interface, perda de uma rota ou falha de áudio interrompem a sessão. O encerramento desbloqueia a captura, interrompe a reprodução e libera os recursos de áudio, a notificação e o wake lock. A interface mostra o estado de erro ou de parada.

Criação e encerramento nativos são executados fora da thread principal. A solicitação de parada fecha imediatamente o envio de dados e agenda a liberação dos recursos; estados de início/parada impedem pedidos concorrentes de ressuscitar uma sessão já cancelada. A tela consulta estado em cache; topologia USB e descritores são atualizados em segundo plano.

Após reconectar a CM6206, abra o diagnóstico, autorize o dispositivo novamente se o Android solicitar e pressione **Iniciar**. A reconexão não retoma áudio sozinha. Trocar entre entrada nativa 5.1 e Upmix também exige parar e iniciar novamente.

## Manutenção no Windows com a porta USB-C ocupada

O Android 11 ou superior permite ADB sem fio com pareamento. PC e A34 devem estar na mesma rede Wi-Fi. No telefone, abra **Opções do desenvolvedor → Depuração sem fio → Parear com código** e use o endereço/porta exibidos. [Procedimento oficial de ADB sem fio](https://developer.android.com/tools/adb#connect-to-a-device-over-wi-fi).

No PowerShell, com o SDK instalado no caminho padrão:

```powershell
$adbExe = Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
$a34PairEndpoint = Read-Host 'IP:porta exibidos na tela de pareamento do A34'
& $adbExe pair $a34PairEndpoint
& $adbExe devices -l
```

Informe o código quando o ADB solicitar. Se o dispositivo não se conectar automaticamente, utilize o endereço/porta da tela principal de depuração sem fio:

```powershell
$a34ConnectEndpoint = Read-Host 'IP:porta de conexão da Depuração sem fio'
& $adbExe connect $a34ConnectEndpoint
& $adbExe devices -l
```

`pair` e `connect` são operações distintas; use a porta indicada para cada operação. Os comandos estão documentados no [manual oficial do ADB no AOSP](https://android.googlesource.com/platform/packages/modules/adb/+/refs/heads/main/docs/user/adb.1.md).

Confirme a conexão Wi-Fi com estado `device` antes de retirar o cabo do PC e ligar o hub ao A34. Para atualizar o APK, a partir da pasta `android-a34`, selecione o identificador mostrado em `devices -l`:

```powershell
$a34Serial = Read-Host 'Identificador Wi-Fi mostrado por adb devices -l'
& $adbExe -s $a34Serial install -r '.\app\build\outputs\apk\debug\app-debug.apk'
```

O ADB não desbloqueia a tela nem aceita os diálogos pelo usuário. Abra o aplicativo no A34 para iniciar a captura. Se a conexão sem fio cair, confira a rede e o endereço atual no telefone e reconecte; a [documentação oficial inclui diagnóstico de conexão](https://developer.android.com/tools/adb#resolve-wireless-connection-issues). A manutenção por Wi-Fi não transporta o áudio do DSP.
