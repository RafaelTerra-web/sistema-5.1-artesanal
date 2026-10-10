# Fire TV real e controle local no A34 — 09/10/2026

O usuário conectou o Fire TV à HDMI 2 da Bravia KD-55X705E e iniciou um título identificado como 5.1. A CM6206 começou no PC e foi transferida ao A34 por **OTG simples**, com permissão USB concedida e amplificadores desligados. O hub PD ainda não está em uso. ADB por Wi-Fi foi conectado a partir da sessão USB já autorizada, conforme o [procedimento Android](https://developer.android.com/tools/adb#connect-wirelessly-with-a-device-after-an-initial-usb-connection-only-option-available-on-android-10-and-lower). A rede transporta manutenção/comandos; não transporta áudio do Fire TV.

## Controle local

APK **0.7.0/versionCode 7**, mesma assinatura/pacote, instalação inicial com preferências integralmente preservadas. Painel hospedado no telefone, porta **8051**, endereços/chave em **Diagnóstico**. Serviço foreground opt-in, independente do DSP; desligar o listener não para o áudio. Não há acesso pela internet, encaminhamento de portas ou início após boot.

Controles: master, mute, bypass, seis trims em dB, cortes/envios de graves, upmix, EQ LFE de nove bandas, margem, mapa FC/LFE e atrasos com desbloqueio deliberado. Consulta estado/medidores, para áudio, solicita início PCM USB e exporta perfil. O início depende de placa/permissões e das restrições de captura em segundo plano do Android. **O botão PCM não implementa recepção Dolby óptica.**

Chave aleatória de 192 bits em header Bearer, sem URL/token no armazenamento do navegador. HTTP local, sem TLS. Listener limitado a pares privados/loopback, Host igual ao IP local usado, Origin externo recusado, sem CORS. Limites de tamanho/prazo/concorrência. Não encaminhar essa porta à internet. Sessão do servidor e revisão do perfil impedem reaplicar comandos antigos/duplicados e sobrescrever ajustes concorrentes. Edições locais/remotas usam o mesmo controlador; rotação da tela não restaura um perfil antigo.

Teste LAN real confirmou autenticação, rejeição de origem externa, ganho inválido, booleano incorreto, bloqueio dos atrasos, aplicação master/mute, preservação dos demais parâmetros, rejeição de revisão antiga e restauração do perfil. Não iniciou streams USB. A versão final também exige sessão atual do servidor.

## Fire TV → Bravia → CM6206 → PC

Capturas de entrada, PCM16 estéreo como **carrier**, sem reproduzir os bytes ou alterar volume TV/Windows. Bravia orientada em Sistema de áudio / Auto 1 / DD+ desativado; Fire TV em Dolby Digital quando disponível. App/título/configuração efetivamente mostrada não foram informados: não atribuir mudanças de formato a um player específico.

| Captura | Resultado |
|---|---|
| Inicial, 5 s | Nenhum pacote |
| Título tocando, 8 s, saída PC ativa | 375.360 frames carrier; 243 quadros AC-3 completos; 176 CRC válidos (83 estéreo, 93 seis canais), 67 CRC inválidos |
| Isolada, 8 s, saída PC parada | 374.400 frames carrier; 241 quadros completos; 181 CRC válidos, todos seis canais; 60 CRC inválidos |

Há espaçamentos diferentes dos 6.144 bytes esperados entre rajadas. **Nenhum segmento capturado está íntegro e contínuo.** WASAPI não sinalizou descontinuidades apesar da estrutura/CRC. Parar a saída USB não eliminou as falhas; não identifica cabo/TV/chip/relógio/driver como causa única.

Uma tentativa intermediária falhou em Initialize WASAPI (0x800703E3). Corrigida incompatibilidade do controlador PowerShell: JSON ISO pode virar DateTime em PS7.5, invalidando comparação textual da propriedade do processo. Agora compara instantes, tolerância inferior a 1 ms para a precisão do PS5.1. Testes de dono/heartbeat/PID reciclado passam em ambos. A captura isolada ocorreu após essa correção; PCM foi restaurado com ganho 0,15 antes da transferência física.

Ao retirar a placa do PC, o worker PCM encerrou com erro USB; o guard não confirmou restauração de REG2 (HidD_FlushQueue failed). O controlador foi solicitado a parar. **Não declarar CleanupComplete do PC nessa desconexão**; preservar o journal local e verificar recuperação quando a placa voltar ao PC. Não executar outro gerenciador simultâneo.

## CM6206 no A34

- Android 14, única entrada física USB corretamente roteada.
- AudioRecord UNPROCESSED, 3 s: 144.000 frames / 576.000 bytes; DC/ruído baixo, nenhum preâmbulo IEC61937. **Não confirmou receber a óptica.**
- Seletor UAC1 unidade 7: microfone, linha, S/PDIF pin 3, mistura. GET_CUR retornou −1; fonte ativa não identificada. Leitura experimental com recipient device também retornou −1, sem escritas.
- HID: seis registradores lidos com acesso exclusivo à interface **3**, force=true, sem reivindicar áudio nem escrever registradores. Interface liberada. REG0 0x2004, REG1 0x3000, REG2 0xF800, REG3 0x157F, REG4 0x0000, REG5 0x3000.
- [Datasheet C-Media, seção 6.1](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf): REG3 sugere S/PDIF a 48 kHz e CANREC ativo nesse instante. Não comprova transporte íntegro ou decodificação.
- OTG simples: Android informa sem alimentação AC/USB, descarregando; bateria 100%, aproximadamente 30 °C. **Nenhum teste de PD** nesta montagem.
- Descritores reais: captura interface 2/alternate 1, endpoint 0x85, máximo 200 bytes, intervalo 1; HID interface 3, interrupt IN 0x81/3 bytes. Saída multicanal USB ainda precisa de mapa acústico.

## Software e pendências

**Política experimental desta revisão, depois substituída:** expandir todo mono/estéreo decodificado, inclusive Dolby, preservando 5.1 nativo. A decisão posterior do usuário prevalece: somente mono/estéreo PCM ou não Dolby conhecido recebe upmix; AC-3/E-AC-3 fica sem upmix. A versão atual está descrita em [retomada do sistema](RETOMADA-PROXIMO-CHAT.md) e [pipeline contínuo](FIRE-TV-A34-PIPELINE-CONTINUO-2026-10-09.md). Carrier IEC61937 precisa ser reconhecido e decodificado antes; duas posições do carrier não autorizam upmix.

Probe USB isócrono direto JNI/NDK implementado para diagnóstico debug, limitado em tempo/tamanho, com captura e saída opcional de silêncio. Não fornece áudio arbitrário à saída. Seleção S/PDIF exige descritores exatos, leitura original, journal, readback e restauração; controle de áudio Android temporariamente separado durante o teste. Usa o FD autorizado pelo UsbManager e a [API usbfs/URBs do Linux](https://github.com/torvalds/linux/blob/master/include/uapi/linux/usbdevice_fs.h), sem root, reset ou EEPROM.

### Resultado do transporte direto

Com controle UAC exclusivo, GET_CUR respondeu: **pin 1 (microfone)** era a fonte anterior. Selecionar **pin 3 (S/PDIF)** e confirmar por readback trouxe o sinal óptico. O teste restaura pin 1 ao terminar; esse comportamento ainda não é uma integração contínua do serviço.

| Ensaio A34 | Integridade |
|---|---|
| AC-3, captura direta 3 s | 93 quadros completos, seis canais/48 kHz, 93 CRC válidos, zero inválidos; todos os intervalos de 6.144 bytes |
| AC-3, captura direta 8 s | 249 quadros completos, seis canais/48 kHz, 249 CRC válidos, zero inválidos; todos os intervalos de 6.144 bytes |
| AC-3, captura 8 s + USB6 silencioso | Mesmo resultado: 249 completos/CRC válidos; zero erros USB IN/OUT; 7.984 pacotes de saída silenciosa, 4.598.784 bytes |
| Fire TV configurado PCM pelo usuário, 5 s + USB6 silencioso | 958.108 bytes / 239.527 frames de entrada, zero erros USB IN/OUT, mas **todos os samples zerados**; não aprova recepção estéreo |

Após selecionar PCM, REG3 mudou de **0x157F para 0x157E**: taxa ainda indicada como 48 kHz, **CANREC=0** (captura S/PDIF não permitida nesse instante). Isso é um indicador concreto do chip, mas a causa exata, inclusive eventual SCMS/channel status, não foi medida. Não alterar EEPROM, reset ou bits de copyright para tentar contornar. **Auto 2 também produziu só zeros** (571.036 bytes, 3 s, CANREC=0). Retorno a Auto 1 / Fire TV Dolby Digital orientado ao usuário; usar essa rota nos próximos ensaios, sem dizer que a falha PCM foi corrigida.

Com Auto 1 + Dolby Digital selecionados e música/vídeo estéreo informado pelo usuário, outra captura de 3 s + saída silenciosa trouxe **571.032 bytes, todos zerados**, sem preâmbulos e CANREC=0. A escolha do menu Fire TV não comprova um carrier Dolby nessa fonte. Experimento seguinte solicitado: Dolby Digital Plus no Fire TV, Bravia Auto 1 / DD+ Out desativado, mantendo conteúdo estéreo. Não chamar silêncio de entrada PCM validada.

**Resultado DD+:** usuário confirmou Fire TV em **Dolby Digital Plus**, música/vídeo estéreo tocando, Bravia Auto 1 / DD+ Out desativado. Captura de 3 s + USB6 silencioso trouxe 574.108 bytes, **93 quadros AC-3 de dois canais/48 kHz, 93 CRC válidos, zero inválidos**, todos os intervalos de 6.144 bytes. Portanto, a óptica recebeu **AC-3 estéreo**, apesar de DD+ selecionado no player. Esse é o caminho confirmado para levar o estéreo ao upmix; o conteúdo original/player/transcoder exato continua sem telemetria. Não concluir que qualquer app/arquivo obedecerá ao menu sem medição.

**Confirmação final 5.1 na mesma configuração DD+:** usuário voltou ao título 5.1 sem mudar os ajustes. Captura de 3 s + USB6 silencioso trouxe 574.108 bytes, **93 quadros AC-3 de seis canais/48 kHz, todos CRC válidos, intervalos de 6.144 bytes**. Assim, tanto estéreo quanto 5.1 chegaram íntegros ao A34 nessa configuração, nas janelas testadas.

Configuração a conservar: **Fire TV DD+ → Bravia Auto 1, DD+ Out desativado → óptica → CM6206 no A34 por OTG**. Transições repetidas, pausas/seek, outros players, tela apagada e sessão longa ainda precisam de validação com o transporte/DSP contínuo.

Em todos, seleção original restaurada e interfaces liberadas. Reconectar explicitamente o driver retornou **−16/EBUSY**; a consulta AudioPolicy ainda anunciou entrada/saída USB e testes seguintes conseguiram reivindicar novamente as interfaces. Isso não confirma todas as condições de retorno HAL após falhas/desconexão; validar lifecycle/reconexão antes de uso contínuo. Os muitos `shortPackets` são pacotes menores que o máximo 200 bytes, comum em transferência isócrona de taxa nominal 192 bytes/ms; esse contador não equivale a CRC inválido ou erro USB.

As evidências aprovam **esses trechos de captura/duplex silencioso**, sem aprovação acústica, comparação do payload original Fire TV, duração prolongada ou decoder ao vivo. Prefixo/sufixo incompletos da janela não entram na contagem de quadros completos.

### Decoder

O fixture próprio tem 282 quadros / 433.152 amostras. Samsung devolveu 431.616 (diferença −1.536), tanto com EOS vazio separado quanto com EOS no último quadro. Primeiro timestamp de saída 0; último 8.919.980 µs. Não corrigir esse resultado fabricando amostras ou declarando fidelidade. Esses modos de drain permanecem diagnósticos; caminho padrão do decoder preservado.

Ainda faltam estabilidade prolongada da captura direta, recepção de estéreo não silencioso, decoder contínuo confiável, trocas PCM/Dolby, duração/ganho/mapeamento, captura/saída simultâneas com DSP real, sincronismo e carga PD. O decoder Samsung de arquivos continua experimental. Controle físico Fire TV exige caminho IR/CEC separado. Testes acústicos só após autorização para tocar, baixo ganho e identificação por canal.

## Verificação do código e estado final

Compilação ARM64 JNI/CMake, 41 testes Java e lint sem erros (18 avisos); quatro testes do inspetor CRC; controlador de estado em PowerShell 5.1 e 7; instrumentação geral do app (persistência/rejeição de perfil, WAV, proteção 5.1 e ordem lógica) passou no aparelho. Perfil comparado novamente ao backup inicial, valores preservados. A regra final de Dolby mono/estéreo foi confirmada depois da instrumentação geral e tem casos próprios na suíte Java; APK atualizado instalado por Wi-Fi.

O controle LAN foi reativado pelo botão do app pelo usuário após a manutenção. A tentativa de adicionar um atalho de instrumentação para reativação foi rejeitada pela revisão automática com a mensagem genérica “bloqueado por política”; o atalho foi removido. A opção persistida pelo botão passa a ser retomada ao abrir o app, sem iniciar o áudio. **Treze verificações LAN finais passaram**, incluindo sessão antiga, revisão duplicada, valores/tipos inválidos, preservação e restauração do perfil; nenhum stream USB foi iniciado pelo teste de rede. Após a última captura, o app foi reaberto e retomou o listener autorizado.

Capturas, perfis de backup, chave, endereços e relatórios brutos ficam em `android-a34/artifacts`, ignorados pelo Git. Não publicar áudio dos programas.
