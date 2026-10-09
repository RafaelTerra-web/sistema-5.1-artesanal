# Sistema 5.1 artesanal

Código desenvolvido para transformar o áudio compartilhado do Windows em Dolby Digital 5.1, corrigir o tempo entre as caixas, ajustar o subwoofer e controlar o PC pelo celular. Este repositório reúne a implementação e o conhecimento para aproveitar os módulos em outro projeto.

**Comece por [Reaproveitar em outro projeto](docs/REAPROVEITAMENTO.md).** Para entender as decisões e limitações, leia [Arquitetura](docs/ARQUITETURA.md) e [Estado e validação](docs/ESTADO-E-VALIDACAO.md).

**Continuidade da bancada em 09/10/2026:** leia primeiro o [ponto de retomada do próximo chat](docs/RETOMADA-PROXIMO-CHAT.md). PCM/upmix no PC restaurado e confirmado por escuta; mestre 0,15, sub +3 dB, envio central grave 50%. AC-3/E-AC-3 não recebem upmix, inclusive Dolby estéreo. A óptica contínua e a cadeia independente no A34 permanecem pendentes. As seções antigas abaixo preservam a arquitetura exportada anterior.

## Plano aprovado para o Galaxy A34

A versão atual é **0.6.0**, instalada e testada no A34 em 09/10/2026. Acrescenta crossover frontal/central, filtro subsônico e troca central/LFE na saída USB. Arquivos com seis canais decodificados permanecem nativos automaticamente; o laboratório atual seleciona upmix pelo número de canais. Ainda falta propagar o codec original no Android para também preservar Dolby estéreo, como exige a regra final. A [bancada PCM no PC](docs/PC-CM6206-PCM.md) verifica o grafo sem recodificação Dolby. Reprodução USB e captura óptica completas no A34 ainda dependem de validação física. O parágrafo abaixo registra a etapa 0.4.0.

**Atualização de 09/10/2026:** o [aplicativo A34 0.4.0](android-a34/README.md) incorpora upmix ajustável, modos Preencher caixas/Ambiência e ferramentas de bancada. A rota HDMI 2 → Sony → óptico → CM6206 → USB no Windows recebeu e decodificou AC-3 de seis canais com CRC válido. Use **Sistema de áudio / Auto 1 / DD+ Não** na TV para a condição que passou. As seis caixas foram ouvidas no teste direto USB, após habilitar REG2.DRIVERON; o par surround exato continua aberto. Consulte o [relatório consolidado e próximos passos](docs/PROGRESSO-A34-E-UPMIX-2026-10-09.md) e a [comunicação CM6206](docs/CM6206-COMUNICACAO-2026-10-09.md). Captura comprimida, controle analógico integrado e mapa físico no Android, além da estabilidade completa, continuam pendentes; o plano abaixo preserva o histórico anterior.

A direção planejada mantém o Fire TV como player e usa o A34 como DSP, com uma interface CM6206 cuja compra foi informada pelo usuário em 06/10/2026, junto com um hub PD. A investigação usa a saída óptica da Sony e as saídas analógicas da interface nos amplificadores, deixando o UD851B fora dessa rota. **O APK existe; a cadeia óptica completa no A34 ainda não foi validada.**

- [Arquitetura, diagramas, calibração, latência e sincronismo](docs/A34-DSP.md).
- [Programação Android pelo PC e roteiro de validação](docs/A34-DESENVOLVIMENTO-E-VALIDACAO.md).
- [Orçamento em reais, alternativas e pesquisa de hardware](docs/A34-ORCAMENTO-E-PESQUISA.md).
- [Notas complementares: Sony/DTS, controle de volume, PC-USB do decoder e reaproveitamento de cabos](docs/A34-NOTAS-COMPLEMENTARES.md).
- [Estudo de viabilidade: atualizar e diagnosticar o A34 pelo Wi-Fi sem desmontar a montagem](docs/A34-MANUTENCAO-SEM-FIO.md).

O manual da Sony documenta Dolby Digital/DTS na óptica e conversão DD+ → DD; o percurso real pelos aplicativos ainda precisa de teste. A captura comprimida íntegra/simultânea pela CM6206 e as capacidades PC-USB do UD851B são condições abertas. Os subtotais são referências anteriores, não ofertas ou funcionamento garantidos.

## O que foi desenvolvido

| Módulo | Responsabilidade | Código principal |
| --- | --- | --- |
| Captura e transporte | WASAPI loopback, PCM 5.1, fila limitada, pool de buffers, alimentação contínua do mpv | [RelayLoopbackLowLatency.cs](configuracao-pc/RelayLoopbackLowLatency.cs) |
| Upmix | Distribuição de mono/estéreo; preservação de conteúdo multicanal conforme o modo selecionado | [StereoUpmix.cs](configuracao-pc/StereoUpmix.cs), [configuração APO](configuracao-pc/upmix-sistema-5.1.txt) |
| Supervisão | Iniciar, parar, detectar HDMI, recuperar falhas e publicar estado | [Controle do sistema 5.1.ps1](configuracao-pc/Controle%20do%20sistema%205.1.ps1), [rodar-audio-sistema.ps1](configuracao-pc/rodar-audio-sistema.ps1) |
| DSP | Atrasos individuais, EQ paramétrico LFE, crossover das surrounds, soma de graves da central, margem e volume mestre | [LFE equalizador comum.ps1](configuracao-pc/LFE%20equalizador%20comum.ps1), [Ajustar atrasos.ps1](configuracao-pc/Ajustar%20atrasos.ps1) |
| Painéis locais | Interface Windows para gerenciamento e equalizador | [Equalizador do sub.ps1](configuracao-pc/Equalizador%20do%20sub.ps1) |
| Controle remoto | Site para celular, autenticação por PIN, mídia Windows, teclado e mouse, Jellyfin e ponte Netflix | [controle-remoto](configuracao-pc/controle-remoto) |
| Netflix 5.1 | Iniciadores com parâmetros do player; ferramenta anterior de preferência local e reversão | [Abrir Netflix 5.1.ps1](configuracao-pc/Abrir%20Netflix%205.1.ps1), [backend anterior](configuracao-pc/netflix-dolby51-automatico) |
| Pesquisa e diagnóstico | Testes sintéticos, inspeção de codecs, fila/deriva, tentativas YouTube e estudo do UD851B | [Documentação local](configuracao-pc/LEIA-ME.md), [pesquisa](pesquisa/viabilidade-firmware-ud851b.md) |

## Dinâmica do sistema

```mermaid
flowchart LR
  A[Aplicativos Windows] --> B[VB-CABLE e Equalizer APO]
  B --> C[Relay WASAPI PCM 5.1]
  C --> D[mpv: DSP e codificação AC-3]
  D --> E[HDMI: UD851B]
  E --> F[Amplificadores e seis caixas]
  G[Celular: controle web] --> H[Servidor Node e worker PowerShell]
  H --> I[Gerenciamento e IPC do mpv]
  I --> D
  H --> J[Teclado, mouse e mídia do Windows]
```

A faixa estéreo pode ser expandida para as seis caixas. Isso não cria os canais independentes de uma gravação 5.1 original. A saída final é AC-3; uma faixa E-AC-3 recebida pela Netflix é decodificada e recodificada após o DSP, não enviada diretamente ao UD851B.

## Calibração exportada

| Canal | Valor solicitado | Valor em 48 kHz |
| --- | ---: | ---: |
| FL / FR | 76,8 ms | 3.686 amostras = 76,792 ms |
| Central | 5,8 ms | 278 amostras = 5,792 ms |
| LFE | **5,8 ms** | **278 amostras = 5,792 ms** |
| SL / SR | 71 ms | 3.408 amostras |

Perfil Fidelidade: **AC-3 640 kbit/s e buffer de saída de 32 ms**. Perfil Estável: **448 kbit/s e 64 ms**. O buffer de saída é somente uma parcela da latência total. O código contém uma compensação de relógio `asetrate=48002` específica da medição deste PC; ela precisa ser recalibrada em outro sistema.

O crossover das surrounds está configurado em 90 Hz. A cópia dos graves da central abaixo de 120 Hz mantém a central com sua faixa completa. O grafo salvo contém essa cópia, mas o preset local exportado tinha `CenterBassEnabled=false`; essa divergência está registrada em [Estado e validação](docs/ESTADO-E-VALIDACAO.md) e deve ser resolvida no projeto de destino.

## Pré-requisitos e instalação

O ambiente de origem usou Windows, Windows PowerShell 5.1/.NET Framework, Node.js **24.14.0**, Python **3.14.3**, mpv, SoundVolumeView, VB-CABLE e Equalizer APO. O servidor Node não exige pacotes npm externos. Consulte [Instalação e dependências](docs/INSTALACAO.md) antes de iniciar os scripts em outro PC.

Os fontes preservam algumas referências ao hardware de origem: GUID do VB-CABLE, busca por `SONY`/`NVIDIA`, caminhos de executáveis e calibração de relógio. **Este é um projeto documentado para migração; não é um instalador universal.** Os binários e drivers são obtidos separadamente.

Os arquivos `.conf` e o exemplo de preset preservam o estado exportado. A configuração ativa e o preset de volume do PC de origem são dados locais ignorados pelo Git. Para inicializar uma cópia isolada dos exemplos:

```powershell
# Execute dentro de uma cópia nova do repositório, antes de rodar o áudio.
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Preparar exemplos locais.ps1
```

## Testes

Testes isolados de painel/servidor e tentativa YouTube:

```powershell
npm test
```

Backend histórico de configuração Netflix, usando bancos temporários:

```powershell
npm run test:netflix
```

Pool/filas do relay, sem abrir dispositivos de áudio:

```powershell
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\configuracao-pc\testar-relay-pool.ps1
```

Os testes de DSP com mpv, testes nativos com janelas e verificações que alteram a rota ficam separados. [Estado e validação](docs/ESTADO-E-VALIDACAO.md) explica quais são apropriados para cada ambiente.

## Organização para reaproveitamento

```text
configuracao-pc/              implementação, painéis e scripts de diagnóstico
  controle-remoto/            servidor, worker, controle universal e site
  netflix-dolby51-automatico/ backend anterior da preferência local
  youtube-dolby51-extensao/   tentativa experimental, não comprovada em uso real
docs/                        arquitetura, migração, instalação e validação
examples/                    preset e configurações sem credenciais
scripts/                     preparação dos exemplos e verificação do pacote
pesquisa/                    relatório de viabilidade do firmware
```

O diretório mantém os nomes dos arquivos originais para preservar as referências entre scripts. Variantes antigas com nomes `antes-*`, `aplicar-atrasos-*` e `mpv-teste-*` são registros de desenvolvimento: o ponto de entrada atual é `Controle do sistema 5.1.ps1`, que usa `rodar-audio-sistema.ps1` e `RelayLoopbackLowLatency.cs`.

## Arquivos locais e terceiros

A lista de permissões do `.gitignore` publica código, documentos e exemplos. Ela deixa fora PIN, chave da ponte Netflix, cookies, perfis do navegador, logs, PIDs, backups locais, áudio de teste gerado, instaladores, firmware extraído e bibliotecas baixadas. O servidor gera suas próprias credenciais ao ser iniciado em uma cópia nova. [Manifesto de publicação](docs/PUBLICACAO.md) descreve a seleção.

As dependências de terceiros mantêm seus próprios termos de distribuição. Este repositório não redistribui seus executáveis nem o player público completo da Netflix.

## Montagem Fire TV e A34

O [plano de controle de volume pelo Fire TV](docs/FIRE-TV-CONTROLE-DE-VOLUME-A34.md) separa volume digital, comandos IR/CEC e master do A34. O equilíbrio manual usa seis trims independentes; os botões do Fire TV deverão modificar apenas master/mute, preservando esse equilíbrio. Integração Fire TV ainda planejada, sem teste físico.

O [relatório da rota Fire TV → HDMI 2/Bravia → óptica/CM6206 → A34](docs/RELATORIO-ROTA-FIRE-TV-A34-2026-10-09.md) registra o mapa central/sub confirmado, os atrasos atualizados (incluindo LFE 5,8 ms), limites e plano de integração. A bancada óptica contínua ainda apresenta falhas de controle/driver e CRC; o A34 ainda não substitui o PC nessa cadeia. O [gerenciador PC](scripts/pc-cm6206-system.ps1) controla PCM/óptica e ganho no DSP, enquanto a [extensão experimental](configuracao-pc/browser-upmix-extensao/LEIA-ME.md) decide por canais decodificados antes do mixer; validação no Opera permanece pendente.
