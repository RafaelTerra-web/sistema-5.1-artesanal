# Estado, testes e pendências

Snapshot de publicação: **05/10/2026**, com histórico de desenvolvimento de 02 a 05/10. Esta página distingue código disponível, testes isolados e confirmação de uso real.

## O que foi confirmado em uso

- O usuário confirmou as seis caixas tocando na ligação PC → HDMI IN UD851B → TV.
- O usuário confirmou que os picotes simultâneos cessaram após ajustes de prioridade/buffer do caminho de áudio.
- O usuário confirmou Netflix oferecendo e selecionando Português (5.1) depois do ajuste de parâmetros, inclusive após abertura nova pela página inicial.
- O sistema foi configurado para AC-3 a 640 kbit/s na saída final. Isso não comprova bitrate idêntico na faixa recebida do serviço.
- A correção relativa salva nos perfis é FL/FR 3686 amostras, central 278, LFE zero e surrounds 3408 a 48 kHz.

## Testes isolados disponíveis

| Teste | O que verifica | O que não verifica |
| --- | --- | --- |
| `controle-remoto/test-web-ui.mjs` | Volume concorrente, repetições limitadas, respostas tardias, faixas, vínculo Netflix e troca de janela | Reprodução real de um filme na Netflix |
| `controle-remoto/backend.test.mjs` | Recuperação/fila do worker; no Windows, HTTP autenticado e snapshot em servidor isolado | Todos os atalhos em cada app; áudio no decoder |
| `testar-relay-pool.ps1` | Pool/fila/contador e rotinas C# sem abrir endpoints | Latência acústica do sistema |
| `netflix-dolby51-automatico/test_cadmiumconfig.py` | Banco temporário, escopo, mesclagem, rollback e rejeição de formatos incompatíveis | Oferta de faixa 5.1 pela Netflix |
| `Verificar gerenciamento do audio.ps1` | Arquivos isolados, rollback, consistência de perfil/delay e configurações | Operação em todo hardware possível |
| `Verificar DSP central.ps1` | Síntese e saída PCM de mpv em arquivo para medir graves e delay | Resultado acústico na sala |
| `controle-remoto/test-universal-input.ps1` | HTTP/worker/Win32 em duas janelas criadas para o teste: Unicode, Tab/Enter, mouse, foco e janela encerrada | Navegação real do catálogo Netflix |

O último teste abre janelas temporárias e requer servidor do controle ativo. Ele não deve rodar em CI sem desktop ou ser confundido com teste da Netflix. A verificação DSP exige mpv e arquivos de configuração preparados; ela escreve PCM em arquivo, sem HDMI.

`npm test` executa os testes Node definidos no pacote. No Linux os testes Windows são explicitamente pulados; os mocks e a UI ainda rodam. Não usar uma CI verde como prova de WASAPI, driver, DRM ou HDMI funcionando.

### Verificação para publicação em 05/10/2026

- `npm test`: 15 testes do controle (11 UI + 4 backend) e 1 teste executável da tentativa YouTube; todos passaram no Windows de origem.
- `npm run test:netflix`: 20 testes do backend anterior; todos passaram em bancos temporários.
- `testar-relay-pool.ps1`: cenários de reutilização, fallback, trim/cancel, silêncio parcial, cancelamento durante escrita, erro de escrita e medidor; todos passaram sem abrir endpoint ou player.
- Preparação de exemplos em pasta temporária: três arquivos copiados, GUID marcador presente e segunda execução recusada sem sobrescrever.
- Verificação do índice Git: sem categorias locais/privadas proibidas, sem chave atual da ponte e sem os formatos de token pesquisados.

O workflow do GitHub repete os testes portáveis no Linux e os testes de pool em Windows. O histórico da execução fica na aba Actions. Essas verificações não iniciam a rota de áudio do usuário.

## Controle Netflix: pendência atual

O controle universal foi validado em janelas próprias. O usuário depois informou que não alcançava a Netflix. A correção posterior introduziu destino lógico `app:netflix`, reconexão ao reabrir, limpeza da mídia antiga e botão **Controlar Netflix**, com Mouse como padrão do catálogo.

Essas correções passaram em **11 testes da UI** em 05/10/2026. A validação dentro do app real ficou pendente: o Computer Use interrompeu a captura porque não identificou com confiança a URL da janela Edge/Netflix. A inspeção anterior também mostrou um diálogo de escolha de diretório de extensão aberto; não foi confirmado que ele fosse a causa do problema.

A extensão do controle Netflix e a troca de idioma/legenda no app instalado ainda não têm confirmação de funcionamento. Navegação por teclado/mouse e a ponte do player são caminhos diferentes.

## Divergência preset × grafo

Ao exportar, `equalizador-lfe.json` tinha `CenterBassEnabled=false`, mas o filtro salvo em `mpv-sistema-dolby.conf` continha `asplit@cenBass` e a soma da cópia grave da central. Isso significa que o grafo e o estado do painel não descreviam a mesma intenção.

O exemplo do preset preserva esse valor local; os exemplos `.conf` preservam o grafo. O script de preparação somente copia os exemplos e não tenta resolver a divergência. Na migração, escolha explicitamente se quer a assistência abaixo de 120 Hz, atualize o preset e regenere o grafo com `Get-LfeAf`. Um próximo Aplicar ou troca de perfil pode reconstruir filtros a partir do preset.

Também há EQ com várias bandas positivas e AutoHeadroom do EQ desativado no preset de origem. Os reforços se sobrepõem; antes de usar a curva em outro subwoofer, confira margem, resposta e nível. O volume mestre de 4% do exemplo é o valor local exportado, não uma calibração de volume universal.

## Limites das pesquisas anteriores

- YouTube no Opera: a tentativa manual continuou AAC/Opus estéreo. A extensão experimental tem testes, mas entrega 5.1 real não está comprovada.
- Jellyfin: existem adaptadores, navegação e seleção de streams. 2160p, canais de um título e codec servido devem ser aferidos na sessão real do destino, não deduzidos do título/idioma.
- Firmware UD851B: há relatório de análise estática. Não houve firmware modificado validado nem atualização no aparelho.
- Latência ponta a ponta: o projeto contém diagnósticos de fila e impulsos, mas os números de buffer/delay não medem sozinhos PC → decoder → amplificador → caixa.


## Plano Android / A34 aprovado em 05/10/2026

O usuário escolheu manter o Fire TV e desenvolver um DSP no Galaxy A34 existente. Foram publicados [arquitetura e diagramas](A34-DSP.md), [roteiro de programação/validação](A34-DESENVOLVIMENTO-E-VALIDACAO.md) e [pesquisa/orçamento](A34-ORCAMENTO-E-PESQUISA.md). A publicação contém documentação, sem código Android implementado.

A direção mais recente usa a óptica Sony e as saídas analógicas da CM6206, deixando o UD851B fora da rota processada. O manual exato da Sony documenta Dolby Digital/DTS e conversão DD+ → DD, mas o percurso real pelos aplicativos não foi testado; a CM6206 precisa comprovar captura íntegra e reprodução multicanal simultânea no A34. Os valores de latência são estimativas de planejamento, não resultados de testes. O ajuste AV do Fire TV é candidato à compensação de sincronismo, com alcance e efeito no caminho Dolby Digital ainda a validar.

Na publicação inicial de 05/10/2026 não haviam sido informadas compras, instalação no telefone, root, troca de OS, alteração de firmware ou teste físico da cadeia. Os resultados Windows acima continuam específicos da implementação existente.

As [notas complementares de conhecimento](A34-NOTAS-COMPLEMENTARES.md) registram o Fire TV 4K de 2ª geração informado pelo usuário, limites de bitrate documentados, controle de volume proposto por IR/Wi-Fi, retorno óptico ou PC-USB ao UD851B e adaptação dos cabos existentes. Seis canais HDMI foram informados; seis canais/captura pela PC-USB continuam desconhecidos. Não há firmware ESP32, controle remoto Android ou teste físico acrescentado por essa atualização.

## Atualização de conhecimento — 06/10/2026

O usuário informou compra da CM6206 e de um hub PD. A chegada da placa e o funcionamento da combinação ainda estão pendentes. Foi registrado o [estudo de viabilidade de manutenção sem fio](A34-MANUTENCAO-SEM-FIO.md), com ADB pela rede, preservação de perfis, interrupção de áudio durante atualização e painel web como possibilidade. Não foram realizados pareamento, instalação de APK, teste no A34 nem implementação do painel por essa publicação.
