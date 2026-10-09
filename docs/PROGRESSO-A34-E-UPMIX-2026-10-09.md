# Progresso consolidado: CM6206, Sony, upmix e Galaxy A34

## Atualização 0.5.0: fonte nativa, graves e central no R

Esta atualização sucede o registro 0.4.0 abaixo. O usuário voltou a relatar graves distorcidos nas frontais depois da confirmação inicial de “limpo”; aquela escuta não encerrou a calibração. Após fechar o FxSound, a bancada PCM com corte de graves melhorou as frontais. Confirmou surrounds audíveis e sub baixo, com central ausente. Informou que a **central está no R** do módulo CEN/BASS: trocar FC/LFE somente na saída física e acompanhar a troca nos ganhos do driver. O par surround físico continua aberto.

As Estatísticas para nerds mostraram `ec-3 (328)` no Opera para [o vídeo informado](https://youtu.be/nLT8nu-BY6s). [Dolby](https://ott.dolby.com/OnDelKits/DDP/Dolby_Digital_Plus_Online_Delivery_Kit_v1.5/Documentation/Content_Creation/SDM/help_files/topics/ddp_mpeg_dash_c_mpd_adaptation.html) identifica `ec-3` como Dolby Digital Plus/E-AC-3. Não assumir que YouTube/Opera entrega sempre estéreo. O codec recebido não comprova canais PCM negociados ou passthrough HDMI. Esta fonte permanece Native enquanto se verifica a cadeia, sem upmix por silêncio/atividade.

Os oito controles de volume da CM coexistiam com MixFormat WASAPI estéreo. Backups locais foram salvos; formato/configuração foram alterados para oito canais e lidos como float32/48 kHz/máscara 0x63F, padrão PCM16/48 kHz/oito canais. O VB-CABLE da bancada tem seis canais. Atualizar o vídeo para renegociar; sinal apenas em FL/FR não autoriza fabricar canais de uma fonte 5.1.

O [protótipo PCM](PC-CM6206-PCM.md) retira graves das cinco caixas com LR4 a 90 Hz, soma ao LFE com margem 1/6 e subsônico 20 Hz, reduz ganho e permite swap FC/LFE. Testes em arquivos passaram em Native/Stereo e mapas normal/trocado. O vídeo testa baixas frequências, atenuadas pelo crossover nos satélites; não basta para identificar a central. A duplicação de surrounds nos dois pares USB é provisória.

A primeira bancada executou em janelas curtas sem quedas de fila, mas com uma descontinuidade inicial e captura Native só em FL/FR. As tentativas Native posteriores falharam na abertura USB. O gerenciador Dolby antigo também estava rodando; foi parado e agora o script rejeita essa concorrência. O erro persistiu: `AUDCLNT_E_DEVICE_INVALIDATED` e parada por stdin bloqueado. Após reconexão USB, PnP apareceu OK, mas leitura HID falhou com dispositivo não funcionando. **Ainda não houve aprovação da nova reprodução nativa de seis canais.** Essa bancada PC→USB é distinta do teste anterior HDMI→TV→óptica.

### Android 0.5.0 e resultados

- **0.5.0/versionCode 5** instalada por ADB no SM-A346M/Android 14, conservando dados. Novos controles continuam desligados no perfil anterior; disponibilidade no APK não significa aplicação ao hardware.
- LR4 opcional de FL/FR/FC, envio ao LFE antes dos delays e supressão da cópia antiga da central quando o crossover frontal está ativo. Subsônico Butterworth opcional no LFE. Margem automática conta todas as somas; cinco satélites/envios 1 e EQ desligado resultam em 1/6.
- Roteador USB troca FC/LFE depois do DSP/medidores, usando o perfil efetivamente aplicado ao bloco. Exports WAV conservam a ordem lógica. Função pura testada; mapa físico Android ainda não ouvido.
- Laboratório decide pelo PCM decodificado: 1/2 → upmix; 6 → nativo. Instrumentação confirmou `decoded51Protected=true`: fonte AC-3 decodificada em seis canais, mesmo com perfil estéreo/swap, gerou WAV idêntico ao perfil nativo. `fileChannelOrderLogical=true`.
- Compilação, **25 casos JUnit** e lint passaram: zero falhas/erros, 17 avisos. Inclui quatro testes novos de crossover/subsônico e dois de roteamento. Aceitação engloba 11 cenários/1.251.095 verificações. Instrumentação no telefone: código 0, `ok=true`, sem reprodução/captura.
- Nova gestão de graves no A34: **431.616 frames**, zero amostras clipadas, processamento total 349,03 ms, maior bloco 3,626 ms. Comparação independente com mpv do mesmo PCM: comprimento completo, erro máximo **3,73e-9**, menor SNR **146,92 dB**. Equivalência de DSP em arquivo; exclui decoder e USB.
- Perfil anterior também comparado: **435.302 frames** com cauda, erro máximo 3,73e-9, menor SNR 147,05 dB. Decoder Samsung ainda perdeu **1.536 frames** frente ao AC-3 sintético: `fidelityValidated=false`; não aprovar transparência/gapless.

Próxima ordem: recuperar sessão USB; confirmar seis posições PCM nativas e central no R; identificar REAR; calibrar ganhos/atrasos; integrar HID/journal Android; implementar transporte IEC61937/decoder controlado; validar simultaneidade, relógios e sessão longa no A34 com hub PD. O telefone ligado por ADB executou testes de arquivos; a CM permaneceu no PC. A cadeia óptica Android completa não foi validada nesta atualização.

## Registro anterior: 0.4.0

Atualizado em 09/10/2026, após os testes de bancada e a instalação Android **0.4.0**. Este é o resumo atual; relatórios anteriores preservam condições e tentativas de cada etapa. O [registro de comunicação](CM6206-COMUNICACAO-2026-10-09.md) detalha os ensaios Windows. Os [planos originais](A34-DESENVOLVIMENTO-E-VALIDACAO.md) continuam como roteiro, com implementação e pendências atualizadas aqui.

## Evidências e limites

| Etapa | Evidência | Limite |
| --- | --- | --- |
| Sony → óptico → CM6206 → PC | AC-3/IEC61937 recebido, CRC válido e seis canais separados em arquivo | Captura não idêntica ao AC-3 transmitido; estabilidade prolongada não aprovada |
| Configuração da TV | Sistema de áudio / Auto 1 / DD+ Não passou; Alto-falantes da TV ficou sem dados no comparativo | Fonte AC-3; DD+ não foi isolado como causa da correção |
| PCM óptico pela Sony | Amostras zero e CANREC=0 nas condições testadas | CANREC não identifica a causa |
| Copyright no loop sintético | Mudar sinalização da saída da própria placa alternou captura/silêncio | Não mede todos os bits enviados pela Sony nem prova bloqueio geral de Dolby |
| Analógico no Windows | REG2.DRIVERON resolveu silêncio; usuário ouviu frontais por USB e pela rota óptica completa | Inicialização Android não integrada |
| Seis caixas por USB direto | Usuário confirmou “foi tudo”; player abriu PCM16/48 kHz/7.1 com oito slots | Par REAR entre BL/BR e SL/SR ainda ambíguo; não comprova oito saídas físicas |
| Upmix Android 0.4.0 | Testes JVM e autoteste executado no A34 | Processamento offline, sem escuta das seis saídas USB no telefone |
| AC-3 em arquivo no A34 | Codec Samsung produziu seis canais; DSP offline sem clipping | Perda de um quadro/1.536 amostras; fidelidade/gapless não aprovados |
| Serviço PCM no A34, etapa anterior | Entrada/saída USB simultâneas silenciadas: 945.600 frames/~20 s, sem underruns naquele ensaio | Não identificou fonte como óptica nem confirmou som nas caixas |
| AC-3 óptico ao vivo no A34 | Contrato EncodedUsbAudioTransport e bloqueio explícito da rota não implementada | Falta backend que preserve/extrai IEC61937 e decodifique continuamente |

## Montagem e causas de silêncio

O PC envia áudio pela HDMI 2 da Sony KD-55X705E. Óptico: TV OUT → **SPDIF IN** da CM6206 `0d8c:0102`, ligada ao PC por USB. Amplificadores nos conectores FRONT OUT, REAR e CEN/BASS. O A34 foi reconectado ao PC para desenvolvimento; a placa permaneceu no PC durante a validação da 0.4.0.

REG2 estava em `0x6004`, bit 15 DRIVERON desligado, com HEADPON presente nas flags. Habilitar **somente bit 15**, REG2=`0xE004`, tornou as saídas analógicas audíveis. Os scripts registram o estado antes da escrita, conferem leitura e restauram o bit ao terminar, preservando os outros bits. REG0 configura a saída S/PDIF da placa; não é correção documentada dos indicadores recebidos da TV. Não houve escrita em EEPROM.

As primeiras falas com LFE provocaram grave forte e resultado confuso. Ensaios posteriores reduziram deliberadamente a voz central a 20% do pico das outras falas e o tom LFE a um pico muito menor. O relato posterior de central/sub baixos, portanto, **não demonstra defeito ou trim final incorreto**. Não foi aplicado reforço automático para compensar uma fonte de teste desigual. Calibração exige sinais comparáveis, mapa confirmado e medição/escuta dos amplificadores.

Uma repetição óptica apresentou CRC AC-3 inválido e underruns mesmo sem flags de descontinuidade WASAPI. Uma falha de acesso HID desapareceu após reconectar USB. Som curto, código zero ou ausência de descontinuidade de captura não aprovam transporte contínuo. A Sony alterou o fluxo AC-3 em algum ponto: CRC válido não comprova transparência do conteúdo; a correlação PCM agregada anterior (~0,76) não aprovou fidelidade integral.

## Upmix implementado

O mesmo motor Java atende arquivos e serviço PCM Android. Entrada nativa exige seis canais; mono/estéreo usa o modo explícito Upmix. Silêncio momentâneo em central/LFE/traseiras de um filme 5.1 não muda o modo.

Para estéreo, M=(L+R)/2 e d é separação entre 0 e 1:

```text
FL = L
FR = R
FC = ganhoCentral * M
SL = ganhoSurround * (L - d*R) / (1+d)
SR = ganhoSurround * (R - d*L) / (1+d)
LFE = passa-baixas LR4(corte, ganhoGraves * M)
```

| Preset | Central | Surround | Graves | d | Corte |
| --- | ---: | ---: | ---: | ---: | ---: |
| Preencher caixas | 1 | 0,5 | 0,5 | 0 | 120 Hz |
| Ambiência | 0,7071 | 0,5 | 0,25 | 1 | 80 Hz |

Em Ambiência, material igual em L/R cancela nas traseiras; diferenças estéreo podem permanecer. É uma matriz simples, não extração de diálogo nem decodificação Dolby. O divisor limita a soma absoluta dos coeficientes surround ao ganho selecionado. O restante do DSP pode somar graves e atingir o clamp.

Os cinco controles persistem em JSON schema 1. Perfis antigos sem os campos conservam a matriz anterior. Presets alteram só esses parâmetros: modo, master, trims, atrasos e EQ permanecem. Para aplicar, selecionar **Upmix estéreo** na aba Perfil. Graves gerados afeta apenas a soma L/R; envios grave dos crossovers são independentes.

Ganhos/d ficam em 0–1, corte em 40–160 Hz. Ganhos preservam históricos; mudança de corte limpa o grafo na fronteira de bloco. Não foi adicionada alocação por amostra. A margem LFE automática conservadora pode deixar o sub baixo: no perfil inicial, ganho efetivo ~0,014889453. Deve ser calibrada deliberadamente na montagem final. Veja [DSP e perfis](../android-a34/docs/DSP-E-PERFIS.md).

## Testes desta atualização

- assembleDebug, testDebugUnitTest e lintDebug passaram. **18 casos JUnit**, zero falhas. O caso de aceitação engloba 11 cenários/1.251.095 verificações. Lint: **0 erros, 17 avisos**; há avisos de APIs/toolchain, não tratados como erros ocultos.
- Cinco novos testes de sinal: mono fora das surrounds, anti-fase limitada, controles sem alterar 5.1 nativo, resposta LR4/invariância de blocos e rejeição de parâmetros inválidos.
- Cinco testes do núcleo Cm6206AnalogDriver: preservar outros bits, estado já ativo, falha de journal sem escrita, conclusão incerta da escrita e restauração com falha/repetição. **Somente base de controle**: ainda sem adaptador HID Android/journal persistente integrado ao serviço; não modificou a placa no A34.
- ADB instalou **0.4.0, versionCode 4**, no SM-A346M/Android 14. Instrumentação: INSTRUMENTATION_CODE=0, ok=true. Perfil existente preservado e restaurado após teste de persistência.
- No próprio app: delays exatos em seis canais, WAV, round-trip dos cinco controles, fallback legado, cancelamento mono surround e persistência aprovados. Perfil inválido e WAV truncado rejeitados.
- AC-3: 282 quadros/433.152 amostras de entrada; c2.dolby.eac3.decoder produziu 431.616 amostras, diferença -1.536. DSP exportou **435.302 frames**, incluindo cauda de 3.686; zero clipping/entradas não finitas. Processamento offline ~348 ms; **não mede latência ao vivo**.
- Comparação do DSP do APK instalado com mpv, usando o **mesmo PCM decodificado**: 435.302 frames completos; FL/FR/FC idênticos, erro máximo global 3,73×10⁻⁹, SNR surround mínimo 147,05 dB. Teste exclusivamente em arquivo; separa processamento DSP da fidelidade do decoder.
- Ferramentas Windows publicadas: 22 testes do coordenador/guard, seis testes do validador AC-3, nove casos de validação do log relay e teste do timer do painel passaram nesta cópia. Nenhum desses testes offline reproduz áudio.
- Instrumentação não iniciou áudio sem USB. App aberto após os testes; nenhuma reprodução pelas caixas nesta atualização.

APK local: android-a34/artifacts/sistema51-a34-0.4.0-debug.apk, SHA-256 `3fd53064cfea10f113c1599992f4c70684266965decf375be6fed86c25588cdc`. Assinatura debug local, não versão de produção. Binário/capturas/relatórios brutos ficam fora do Git; fontes, scripts e resumos reproduzem a validação.

## Painel Windows

O erro “Não há mais dados disponíveis”, RegistryKey.GetValueNames/Timer.OnTick, veio do painel PowerShell antigo. A descoberta Sony passou a ler valores nomeados por RegistryKey.OpenSubKey, fechar handles e tolerar endpoints removidos. O timer trata falhas transitórias e retoma atualização. Teste isolado de falha/recuperação passou; fechar/reabrir a janela carrega a correção. Nenhuma configuração JIT ou alteração do Registro foi necessária.

## Plano atualizado, em ordem

### Ajuste posterior: música do PC, FxSound e distorção

O usuário relatou distorção nas frontais e possivelmente surrounds durante música/vídeo, fora dos testes falados. A inspeção mostrou **Opera GX → dispositivo virtual FxSound → saída USB analógica CM6206**, sem processo mpv ativo. Não tratar essa escuta como novo teste do percurso HDMI/Sony/óptico nem do app Android.

A placa estava quase a 0 dB em oito controles de canal. Foram reduzidos FL/FR e ambos os pares surround a aproximadamente **−12 dB**, preservando os controles de FC/LFE. Restou distorção. Depois, a entrada virtual FxSound foi reduzida em 6 dB; o programa também sincronizou o master da saída USB. Leitura: FxSound −6,07 dB; USB FL/FR/surround −17,37 dB e FC/LFE −5,44 dB. O usuário disse que ficou limpo, mas em seguida relatou distorção considerável no máximo do Windows/navegador. Isso confirma melhora em uma condição, não aprovação de volume máximo.

Nova redução nos controles de FL/FR e ambos os pares surround: cerca de **18 dB em relação a FC/LFE**. O driver quantizou a solicitação: leitura final −23,00 dB nos seis slots de satélites e −5,44 dB em FC/LFE, diferença 17,56 dB. O usuário repetiu o trecho no máximo e confirmou **“Ficou limpo”**. Essa regulagem foi mantida. Capturas de configurações antes/depois estão privadas em artifacts. É uma confirmação auditiva naquele trecho/volume; leituras do driver não medem pressão sonora, margem elétrica ou ausência de clipping em qualquer fonte. Calibração relativa e teste prolongado continuam pendentes.

O FxSound instalado é **1.1.16.0**. Sua [documentação de suporte](https://forum.fxsound.com/t/troubleshooting/3105) informa PCM de 2–8 canais e ausência de suporte ao transporte não-PCM Dolby Digital/DTS. Portanto, não afirmar que é exclusivamente estéreo; suporte nominal não confirma mapa físico, upmix ou preservação de 5.1 nesta versão/montagem. O [desenvolvedor explica](https://forum.fxsound.com/t/what-does-dynamic-boost-do-exactly/6900) que Dynamic Boost tem limitação de picos digitais, mas não impede sobrecarga de amplificadores/caixas. A causa exata desta distorção continua aberta; comparar o trecho com efeitos desativados e observar níveis digitais antes de atribuir ao hardware.

No A34, FxSound não integra a arquitetura. Trims do próprio DSP fazem a redução antes do clamp. A calibração de margem/trims deve cobrir entrada normalizada no master máximo autorizado, e depois ser confirmada fisicamente; baixar só o volume geral não estabelece essa condição. Conversão inicial: −12 dB equivale a ganho 0,2511886; −18 dB a 0,1258925. Não transplantar cegamente o volume Windows para o Android, cujos transportes ainda precisam de validação.

O CI Android publicado também passou no GitHub após incluir preparação explícita de command-line tools e retirar o pacote SDK obsoleto tools. [Execução validada](https://github.com/RafaelTerra-web/sistema-5.1-artesanal/actions/runs/37969023704). As falhas iniciais de ambiente estão preservadas no histórico; compilação, JUnit e lint concluíram na repetição.

### Próximas etapas

1. **Mapa físico:** distinguir BL/BR versus SL/SR e CEN/BASS. Depois suportar explicitamente seis/oito slots no app; índice Android não identifica automaticamente conector.
2. **Inicialização analógica A34:** adaptador HID do guard, journal persistente antes de DRIVERON, leitura, restauração/recuperação e reconexão. A leitura anterior encontrou HID ocupado com claim sem forçar; resolver mantendo interfaces de áudio disponíveis. Base Java não prova esse acesso.
3. **Captura óptica Android:** identificar seletor/fonte UAC; preservar bytes, sync IEC61937, ordem de palavras, frames/CRC, continuidade e simultaneidade. Converter carrier PCM diretamente para float/DSP não implementa AC-3.
4. **Decoder/transporte:** comparar ganho/duração com referência, resolver perda no codec Samsung; avaliar FFmpeg ARM64/licenças, filas limitadas, métricas e compensação de relógios após decodificação.
5. **Upmix/calibração:** escutar voz/música estéreo após autorização, avaliar presets, equilibrar central/LFE com trims/ganhos físicos e medir atrasos da rota final. Preservar 5.1 nativo.
6. **Uso dedicado:** ensaio prolongado com hub PD, carga, tela apagada, temperatura, reconexão, mudanças de formato e ADB sem fio. Operação independente do PC depende desses critérios.

## Fontes e conhecimento anterior

- [Sony: manual oficial](https://www.sony.com/electronics/support/res/manuals/W000/W0006624M.pdf): opções e formatos ópticos. AC-3 sintético testado; Netflix/Prime protegidos não foram testados.
- [Datasheet C-Media CM6206](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf): registradores; REG0 não informa channel status da entrada.
- [Protocolo HID](https://github.com/vestom/cm6206ctl/blob/master/cm6206ctl.c): referência de comandos; Android não usa o prefixo sintético de report ID do Windows.
- [Android UsbDeviceConnection](https://developer.android.com/reference/android/hardware/usb/UsbDeviceConnection) e [AudioFormat](https://developer.android.com/reference/android/media/AudioFormat): APIs de acesso/máscaras; capacidade da API não comprova rota física.
- [Validação anterior](../android-a34/docs/VALIDACAO-2026-10-08.md), [USB](../android-a34/docs/USB-E-VALIDACAO.md), [otimizações](../android-a34/docs/OTIMIZACOES-E-PROTECAO-2026-10-09.md), [teste óptico inicial](../android-a34/docs/TESTE-OPTICO-2026-10-09.md), [manutenção sem fio](A34-MANUTENCAO-SEM-FIO.md), [publicação](PUBLICACAO.md).

O conhecimento publicado é síntese técnica dos testes e da escuta relatada, distinguindo ambos. Credenciais, serial ADB, pareamentos, dados de conta e gravações de terceiros ficam fora da publicação.

Atualização da recuperação: restaurados formato padrão estéreo e máscaras originais a partir do backup. A tentativa de saída exclusiva de oito canais continuou falhando com `0x80070001`, embora IsFormatSupported aceitasse o formato. A leitura HID permaneceu com erro. Solicitado retirar a óptica temporariamente e usar outra porta USB para isolar a condição física. O formato padrão atual voltou a dois canais; isso não altera o grafo Native de seis canais, mas a nova sessão USB ainda não abriu.

O CI publicado para a 0.5.0 passou: [Aplicativo A34](https://github.com/RafaelTerra-web/sistema-5.1-artesanal/actions/runs/37979134746) e [Testes isolados](https://github.com/RafaelTerra-web/sistema-5.1-artesanal/actions/runs/37979134907). Nenhum APK, captura, serial ou credencial foi incluído no commit.

Nova porta USB, óptica retirada: leitura dos seis registradores voltou a funcionar, REG2=0x6004/DRIVERON desligado. Após o ajuste dos volumes por canal via Windows, leitura HID voltou a falhar; o wrapper parou antes de escrever DRIVERON ou iniciar reprodução. A sequência sugere investigar a interação de controle de volume/driver, mas não estabelece causalidade. Próximo ensaio solicitado: reconectar na mesma porta e usar só ganho baixo no DSP, sem alterar controles de volume da placa.

Recuperação sem ajuste dos volumes do driver: leitura HID dos seis registradores passou; REG2 0x6004 foi journalado e somente DRIVERON foi mantido em 0xE004 pelo wrapper privado, com restauração verificada a 0x6004 na parada. Saída exclusiva abriu como WASAPI 48 kHz/7.1/oito canais. Após atualizar o vídeo, a captura apresentou sinal em FL/FR/FC/LFE/SL/SR, com autoFrames=0 e modo Native. Isso confirma sinal na captura, não o mapa físico nem independência dos canais originais. Óptica permaneceu retirada: teste PC→USB, sem aprovação adicional do passthrough.

Ganho DSP 2% foi baixo; aumentado para 10% por pedido do usuário. Ele relatou som somente nas frontais, sem central, surrounds ou sub. Sessões curtas começaram sem quedas, mas depois registraram 7.200 frames descartados (~150 ms), escrita máxima ~200 ms e uma descontinuidade inicial: estabilidade prolongada não aprovada. Preparado teste falado direto de oito slots, central no R/slot 3, LFE no L/slot 2 e comparação separada BL/BR versus SL/SR. Sem crossover, EQ ou upmix nesse teste. Picos: frontais/traseiras 0,10, central 0,05, tom LFE 60 Hz/0,5 s a 0,003 com rampas. Anúncio do sub nas frontais. Preparação somente, aguardando autorização de reprodução; arquivos/identificadores ficam privados.

Teste falado direto executou em WASAPI 48 kHz/7.1/oito canais, sem crossover/EQ/upmix. O usuário relatou que a fala Central, enviada ao slot USB 3 (índice zero), saiu no subwoofer. Portanto a informação de ligação da central no R não autoriza presumir a inversão FC/LFE: o mapa precisa de confirmação empírica. Não manter a troca apenas por essa descrição. Preparado teste isolado de duas falas nos slots 2 e 3, pico igual 0,05, demais slots zerados e sem tom grave; aguardando reprodução. A saída analógica foi restaurada a REG2 0x6004 com leitura verificada depois do teste. Uma leitura HID falhou na transição, mas a repetição somente de leitura recuperou os seis registradores, sem nova reconexão física; falhas anteriores não estabelecem causalidade exclusiva do volume Windows.

Mapa CEN/BASS confirmado por escuta: teste isolado de duas falas executado até o fim, saída WASAPI 48 kHz/7.1/oito canais, sem filtro/EQ/upmix, demais seis slots zerados. O usuário ouviu a primeira fala na central e a segunda no sub. Primeira = slot USB 2 (terceiro canal), segunda = slot 3 (quarto canal). Nesta rota Windows, preservar a ordem lógica FC/LFE normal, sem SwapCenterLfe; a ligação R no amplificador não muda essa evidência. REG2 restaurado e verificado em 0x6004 ao terminar. Mapas Android ainda exigem escuta própria porque usam seis posições/HAL distinto.

Retirada a inversão do runner local e do grafo PCM ativo em arquivo; não reiniciada música neste momento. Ganho padrão do script de bancada reduzido de 0,5 para 0,1, mantendo trim central −12 dB, margem LFE 1/6 e cortes. Teste de sinais em arquivo com mapa normal/ganho 0,1 passou. Corrigida a descrição da opção no A34 para exigir confirmação física, sem prometer que uma central ligada no R precisa de inversão. Preparado teste de traseiras separado: quatro falas, slots 4/5 e depois 6/7; frontais/central/LFE zerados, pico 0,1, ~25 s; aguardando autorização de reprodução.

## Gerenciador integrado e A34 0.6.0

O painel e o controle remoto passaram a usar um gerenciador único, com estado recente, identidade do processo e avanço real do áudio por IPC. Volume/mudo usam ganho no DSP (conversão cúbica do mpv); não escrevem volumes USB. Rotas PCM e óptica têm exclusão mútua, parada/limpeza e restauração de defaults sob controle da sessão. EQ/perfis HDMI legados não aparecem como aplicados na nova rota. A detecção antiga de estéreo por silêncio foi removida; extensão opcional Web Audio aplica upmix só com 1/2 canais antes do mixer. Instalação autorizada mas automação bloqueada por não confirmar URL do Opera; instalação manual/validação pendentes.

Atrasos solicitados: FL/FR 76,8 ms, CEN/LFE 5,8 ms, SL/SR 71 ms. Vetor48k = 3686,3686,278,278,3408,3408; teste arquivoPC deslocou as posições exatamente, com erro zero. Upmixmanual da música confirmada como estéreo foi ouvido em frontais, central, sub e surrounds a ganho0,24. Ganho salvo aumentado a0,30 pelo pedido do usuário. Sessão PCM curta/longa teve perdas; não aprovada sem falhas.

Optical atual: encoder PCM6→AC3/Sony negociou carrier; receptor recebeu AC3 seis canais e abriuUSB8, mas teve CRCmismatch, underruns e travamentoReset/StartWASAPI/pipe. Decoder mantémcrccheck+explode e descarta quadros ruins, com contagem; tolerar retomada não corrige corrupção. NVIDIA Broadcast ligado ao mic da CM foi encerrado temporariamente. Saída óptica reduzida paraUSB6/5.1(side) como ensaio de duplex; últimoensaionãoabriuáudio porfalhaHID/escritaDRIVERON. Não há aprovação da rota contínua atual. Ver [relatório e plano Fire TV/A34](RELATORIO-ROTA-FIRE-TV-A34-2026-10-09.md).

A34 0.6.0/code6 compilado e instalado:27JUnit,lint0erros/17avisos,instrumentaçãooktrue. Autoteste observou LFE278; operaçãoapply-delays persistiu o vetor no perfil existente combackupdurável eotherSettingsPreserved/profileAppliedAndVerified true. NenhumáudioUSBAndroid nesse ensaio;codecSamsungpermanececom1536framesperdidos.
