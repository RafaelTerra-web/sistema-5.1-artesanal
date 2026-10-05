# Reaproveitar em outro projeto

## Escolha o que precisa levar

| Objetivo | Levar | Dependências e adaptação |
| --- | --- | --- |
| Somente controle remoto Windows | `controle-remoto/web`, `server.mjs`, `worker-bridge.mjs`, `worker.ps1`, `UniversalRemote.cs` | Desacoplar chamadas ao áudio antes de executar sem os gerenciadores. Windows/Node/PowerShell; o servidor usa WinRT e Win32. |
| Somente teclado/mouse | `UniversalRemote.cs` | Pode chamar a classe por `Add-Type`; manter validação do destino e foco. |
| Gerenciamento de processos e arquivos | `Audio gerenciamento comum.ps1` | Alterar nomes de processos, caminhos, mutexes e arquivos de estado para seu produto. |
| Captura PCM contínua | Relay atual, `RelayLoopback.cs` e `StereoUpmix.cs` | WASAPI; formato fixo de seis canais/48 kHz/float32; mudar transporte/encoder somente após medir a fila. |
| EQ, graves e atrasos | `LFE equalizador comum.ps1`, `Ajustar atrasos.ps1`, painéis e exemplos `.conf` | mpv com filtros necessários; preservar ordem dos canais e a estrutura de `af=` esperada pelos parsers. |
| Jellyfin | Adaptadores em `server.mjs` e telas correspondentes | Servidor, usuário e sessão; parametrizar URL `127.0.0.1:8096`. |
| Iniciador Netflix 5.1 | `Abrir Netflix 5.1.ps1`, `Configurar Netflix automatico.ps1` e sua pasta auxiliar | Edge/app instalado; opções do player são experimentais e devem ser revalidadas. |

Copiar apenas `server.mjs` e `web/` não cria um controle autônomo: o snapshot e as rotas de volume também chamam os módulos de áudio. Copie o conjunto completo ou crie adaptadores com a mesma forma de resposta.

## Contratos úteis

### Painel web ↔ servidor

As páginas usam endpoints `/api/*`, com sessão HttpOnly obtida pelo PIN. O status reúne volume, mute, estado de áudio, sessões Windows/Jellyfin, janelas e eventual ponte Netflix.

| Endpoint | Finalidade |
| --- | --- |
| `POST /api/pair` | Obter sessão com PIN local |
| `GET /api/status` | Snapshot para a interface |
| `POST /api/volume` | `{percent, muted}` para as seis caixas |
| `POST /api/input` | `{window, action, mode, text?, dx?, dy?}`; ações permitidas, sem shell arbitrário |
| `POST /api/media` | Comandos GSMTC a uma sessão Windows |
| `POST /api/audio` | Ligar, desligar ou escolher modo de upmix |
| `POST /api/profile` | Perfil de codificação/buffer |
| `POST /api/jellyfin/*` | Login, comandos e streams do Jellyfin |
| `POST /api/netflix/open` | Abrir app ou Edge com ajuste 5.1 |
| `POST /api/netflix/command` | Comando assíncrono para a ponte opcional |
| `POST /bridge` | Heartbeat local da extensão Netflix |

Os comandos de UI devem apontar para uma janela atual de `/api/status`. A chave da ponte e o cookie da sessão têm funções diferentes; não reutilize um segredo para ambos nem grave os valores no repositório.

### Servidor ↔ worker

Uma linha JSON de entrada inclui `type` e um `id` correlacionado pelo bridge. A saída é `{id, ok:true, data}` ou `{id, ok:false, error}`. O worker pode permanecer carregado, reduzindo a criação de processos e a inicialização do WinRT.

No outro projeto, injete adaptadores para `snapshot`, `volume`, `media`, `input` e ciclo de vida. Mantenha comandos de áudio demorados fora da fila de pacotes PCM. Não repita comandos de input automaticamente após uma resposta desconhecida.

### Painéis ↔ mpv

`Invoke-LfeMpv` conecta na named pipe `SistemaArtesanalAudio51` e troca JSON do IPC mpv. Os filtros são identificados por nomes como `surBass`, `cenBass`, `lfeheadroom`, `lfe20` e `master51`. Se renomear esses filtros, ajuste também os parsers e testes; o gerenciador depende dessa identidade para retirar só suas próprias etapas.

## Roteiro de migração

1. Clone o repositório em uma pasta do novo projeto. Rode primeiro os testes sem áudio.
2. Faça uma lista dos módulos que serão incorporados. Evite executar scripts de instalação antigos como se fossem o instalador atual.
3. Leve exemplos para os arquivos de estado locais com `Preparar exemplos locais.ps1`. Eles não iniciam serviços nem mudam endpoints.
4. Parametrize os dispositivos: GUID do VB-CABLE, saída HDMI, busca `SONY/NVIDIA`, caminhos de executáveis e porta/URL do Jellyfin.
5. Defina nomes próprios para mutexes, named pipes, flags e arquivos PID. Dois produtos que usam os mesmos nomes podem disputar a rota.
6. Comece a compensação de relógio em 48.000 Hz e meça a deriva real. O valor 48.002 do sistema de origem não é calibração universal.
7. Confronte o preset e o grafo. Resolva `CenterBassEnabled` conforme sua intenção antes de o painel regenerar `af=`.
8. Confira canais independentes com sinais sintéticos. Revalide atraso relativo, crossover e margem quando mudar sample rate, layout ou EQ.
9. Conecte o controle web ao backend do novo produto e teste respostas tardias, perda de conexão e troca de aplicativo.
10. Só então valide Netflix/Jellyfin em uso real. Uma lista de idiomas 5.1 e um teste de código isolado não medem codec recebido nem bitrate do serviço.

## Referências específicas que precisam mudar

| Referência | Onde aparece |
| --- | --- |
| `SONY` / `NVIDIA` | `Controle do sistema 5.1.ps1`, `rodar-audio-sistema.ps1` e scripts antigos de instalação |
| GUIDs do cabo virtual | Gerenciador principal, APO e scripts históricos |
| `C:\Python314\python.exe` | `Configurar Netflix automatico.ps1` e documentação histórica |
| `C:\Program Files\nodejs\node.exe` | Iniciador do controle e regra do firewall |
| `mpv-portatil\mpv.exe` | Runner e identificação do processo filho |
| `ferramentas\soundvolumeview\SoundVolumeView.exe` | Helpers de gerenciamento |
| `SistemaArtesanalAudio51` | Named pipe, mutexes e consumidores de IPC |
| `http://127.0.0.1:8096` | Adaptador Jellyfin do servidor Node |
| `.conf`, `af=`, taxa 48 kHz | C#, grafo e scripts de atraso/DSP |

O próximo passo natural é extrair uma configuração explícita para hardware/caminhos e interfaces para áudio/mídia. Este snapshot ainda usa scripts acoplados; a documentação identifica esse acoplamento para que ele não seja perdido na migração.

## Texto para entregar a outro projeto ou agente

> Use este repositório como fonte da implementação Windows 5.1 e do controle remoto local. Leia README.md, docs/ARQUITETURA.md e docs/ESTADO-E-VALIDACAO.md antes de modificar. Preserve ordem de canais, atrasos relativos, DSP antes do encoder, validação de janela/foco e locks de configuração. Não copie credenciais nem perfis do navegador. Não aplique a correção 48002 Hz sem medir a deriva do novo hardware. Separe os adaptadores de áudio, GSMTC, input, Jellyfin e Netflix. As últimas correções do vínculo automático com Netflix passaram nos testes do painel, mas ainda precisam de confirmação dentro do app. Registre quais integrações foram efetivamente verificadas no novo ambiente.
