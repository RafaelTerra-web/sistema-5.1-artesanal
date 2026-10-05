# Sistema 5.1: áudio do Windows em Dolby Digital

## Controle pelo celular

Abra **Controle remoto do PC** na Área de Trabalho para controlar volume, reprodução e navegação no Jellyfin pelo A34. A página aberta no PC mostra o endereço e o PIN da rede local. O painel também permite escolher o perfil Dolby Digital e o comportamento do upmix. Veja [instruções do controle](controle-remoto/LEIA-ME.md).

Estado em 03/10/2026. Ligação física: PC (HDMI da placa de vídeo) → UD851B → TV Sony Bravia. O usuário confirmou que as seis caixas tocam nessa ligação e que o UD851B aceita Dolby Digital.

## Rota global configurada

1. **CABLE Input** (VB-CABLE) é a saída padrão do Windows, em seis canais, 48 kHz, 24 bits (mixagem interna float32). Os aplicativos devem usá-la em modo compartilhado.
2. O **Equalizer APO**, na etapa de pré-mixagem desse dispositivo, preenche CEN, LFE, SL e SR quando um aplicativo envia estéreo ou mono. Para estéreo, CEN recebe metade de L + metade de R; os surrounds recebem metade do canal correspondente. O LFE recebe um quarto de L + um quarto de R, com filtro passa-baixas de 120 Hz. Um fluxo 5.1 nativo não recebe esse upmix.
3. O relay de baixa latência captura os seis canais por **WASAPI loopback** de CABLE Input e os escreve em outra thread como WAV contínuo 5.1/48 kHz/float32 para o mpv. A captura usa eventos, reutiliza os buffers e mantém uma fila limitada a 80 ms; o demuxer entrega pacotes de 10 ms e o buffer de saída AC-3 do mpv foi confirmado em **32 ms**. O relay também expande fluxos declarados como seis canais que tenham sinal apenas em FL/FR por 1,5 segundo: gera CEN, LFE filtrado a 120 Hz e surrounds, com entrada gradual de 100 ms. Quando há sinal nos outros canais, passa o bloco original. Os dois filtros do LFE do fallback usam Q 0,7071, iguais aos do APO. O mpv compensa a pequena deriva observada entre o cabo virtual e o HDMI, aplica o atraso uma vez, codifica AC-3 a **640 kbit/s** e envia o bitstream em modo exclusivo à saída HDMI Sony `{e89cb1c3-e885-4df2-800f-ac950f115f89}`. O UD851B decodifica o Dolby Digital; o usuário confirmou som normal nas seis caixas com o perfil novo.

| Canais | Atraso adicional |
| --- | ---: |
| FL e FR (índices 1 e 2) | 76,8 ms nominais |
| SL e SR (índices 5 e 6) | 71 ms |
| CEN (índice 3) | 5,8 ms nominais |
| LFE (índice 4) | 0 ms |

O atraso da rota global fica no filtro do mpv, depois da compensação de relógio e antes do codificador. A 48 kHz, FL/FR correspondem a 3.686 amostras (76,792 ms), CEN a 278 (5,792 ms) e SL/SR a 3.408 (71 ms); LFE permanece em zero. O perfil APO do HDMI não acrescenta outro atraso ao bitstream exclusivo. A fila de software estimada pelo IPC, medida antes desses ajustes entre caixas, ficou em **73–77 ms** nas cinco primeiras amostras do perfil novo, contra **107–121 ms** nas cinco amostras anteriores. O atraso total entre imagem e som não foi medido. Veja [melhorias-fidelidade-2026-10-03.md](melhorias-fidelidade-2026-10-03.md) para os testes e os limites da calibração.

## Uso

Abra **Sistema de audio 5.1** na Área de Trabalho, ou **Controle do sistema 5.1.cmd** nesta pasta. O painel tem dois botões:

- **Ligar 5.1:** seleciona CABLE Input como saída padrão do Windows, do Opera e do Edge (incluindo a Netflix instalada), inicia a codificação Dolby Digital e mantém o upmix e os atrasos da tabela acima.
- **Desligar / audio direto:** encerra a rota, libera a saída HDMI Sony e a seleciona como saída padrão em estéreo, sem a correção de atraso da rota 5.1. Opera e Edge voltam a acompanhar a saída padrão.

Fechar o painel deixa o áudio no estado escolhido. A escolha de desligar é mantida após sair da sessão ou reiniciar o PC; use **Ligar 5.1** para reativar. Há um atalho na pasta de Inicialização do Windows que inicia a rota ao entrar na sessão quando ela estiver habilitada.

**Iniciar audio 5.1 do sistema.cmd** e **Parar audio 5.1 do sistema.cmd** executam essas mesmas ações diretamente. O iniciador volta a procurar a saída Sony e reinicia o relay se ele sair após uma mudança no HDMI; o áudio pode ser interrompido enquanto a saída estiver indisponível. Depois de trocar a rota, atualize o vídeo com F5 ou reabra os aplicativos que continuarem usando outra saída. O FxSound foi fechado por conflito com a rota HDMI exclusiva.

**Audio - maior fidelidade.cmd** seleciona AC-3 640 kbit/s e buffer de 32 ms. **Audio - maior estabilidade.cmd** seleciona 448 kbit/s e buffer de 64 ms, para recuperar margem se surgirem falhas sob carga ou incompatibilidade do decoder. Ambos mantêm upmix, compensação de relógio e os atrasos da tabela acima. A troca reinicia a rota se ela estiver ligada; se estiver desligada, prepara o perfil para a próxima ativação. O painel principal continua sendo usado para ligar/desligar todo o sistema.

Enquanto o sistema estiver ligado, mantenha **CABLE Input** como saída padrão do Windows. A saída **SONY** é ocupada pelo codificador e pode causar erro de renderização se o navegador tentar usá-la diretamente. O controle direciona o Opera ao CABLE Input ao ligar e desfaz esse direcionamento ao desligar. Após um reinício do serviço de áudio, atualize o vídeo; se necessário, reabra o navegador.

**Upmix automatico.cmd** habilita a detecção descrita acima. **Preservar 5.1 nativo.cmd** desliga essa detecção para preservar trechos de 5.1 que tenham apenas os frontais ativos. Essa seleção permanece até trocá-la. O upmix de fontes declaradas como estéreo/mono no Equalizer APO continua ativo em ambos os modos.

**Testar estereo no sistema.cmd** envia um sinal estéreo ao cabo virtual para verificar o upmix. **Testar caixas 5.1.cmd** envia sinais nos seis canais pela rota global. Use volume baixo nos testes e confira FL → FR → CEN → LFE → SL → SR. **Abrir player 5.1.cmd** também envia seu áudio ao cabo virtual; o processo global faz a codificação e o atraso.

A rota não captura aplicativos que escolhem outra saída, WASAPI exclusivo, passthrough ou saída RAW. A detecção de FL/FR é uma heurística: um trecho 5.1 com silêncio nos outros quatro canais por mais de 1,5 segundo pode ser expandido. Use **Preservar 5.1 nativo.cmd** quando precisar preservar esse caso. Os testes de caixas desativam temporariamente essa detecção enquanto sua janela estiver aberta (até cinco minutos), para conferir a separação dos canais.

O rastreamento `apo-upmix-trace.log` confirmou entrada estéreo de dois canais, saída de seis e carregamento do filtro `Copy` na pré-mixagem. Os testes e perfis antigos de 90 ms são históricos ou diagnósticos; não medem os atrasos atuais da tabela acima. Os iniciadores principais de teste usam agora a rota global. A separação física das caixas e o alinhamento acústico final dependem de escuta ou medição no ambiente.

No teste inicial de cerca de cinco minutos com o relay novo, os logs não registraram underruns do mpv, frames descartados nem silêncio inserido; o usuário relatou que as falhas audíveis pararam. A revisão de fidelidade foi observada por **13 minutos**, cobrindo o intervalo dos descartes recorrentes encontrados no log mais longo: zero descartes, zero silêncio inserido, zero underruns e fila sem crescimento contínuo. Veja [diagnostico-latencia.md](diagnostico-latencia.md) e [melhorias-fidelidade-2026-10-03.md](melhorias-fidelidade-2026-10-03.md) para os números e os limites das medições.

O controle foi testado em 03/10/2026: desligar encerrou o relay e selecionou Sony em dois canais; ligar restaurou CABLE Input como saída padrão das três funções do Windows em seis canais e iniciou novamente a saída AC-3. As exportações `controle-verificacao-desligado.json` e `controle-verificacao-ligado.json` registram as saídas. O sistema foi deixado ligado.

## Netflix instalada e Edge

**Netflix - app com Dolby 5.1.cmd** e o atalho **Netflix - Dolby 5.1** na Área de Trabalho ligam a rota, selecionam AC-3 a 640 kbit/s e abrem a Netflix instalada. **Netflix - Edge com Dolby 5.1.cmd** abre o site no Edge. A Netflix da Store instalada neste PC, versão 7.0.8.0, é uma aplicação hospedada pelo Edge. Ambos usam CABLE Input e mantêm os mesmos atrasos e upmix da rota global.

### Abertura automática corrigida em 03/10/2026

Use **Netflix - Dolby 5.1** na Área de Trabalho ou no Menu Iniciar. O atalho abre o app Netflix instalado, no perfil **Default**, passando automaticamente as seis opções de 5.1 na URL inicial. O mesmo ajuste também foi integrado aos comandos de abertura do app e do site no Edge. **Não é necessário executar um favorito por filme ou episódio.** O usuário confirmou “Português (5.1)” tanto na abertura direta do episódio 81301714 quanto após uma abertura nova pela página inicial e seleção do episódio.

O ajuste habilita DD+ 5.1 e seu perfil HQ, usa o detector comum de codecs e mantém Atmos desabilitado. Títulos/idiomas que tenham apenas estéreo continuam usando o upmix da rota global. O bitrate recebido da Netflix depende do serviço; 640 kbit/s é o bitrate da saída final ao decoder.

**Netflix - reinstalar 5.1 automatico.cmd** habilita a abertura por URL e recria os dois atalhos. **Netflix - desfazer 5.1 automatico.cmd** desabilita os parâmetros automáticos e restaura a preferência local anterior; a reversão reinicia Edge/Netflix. O estado fica na pasta `netflix-dolby51-automatico`. A entrada original da Microsoft Store não foi modificada; use o atalho **Netflix - Dolby 5.1** para garantir que os parâmetros de abertura sejam enviados.

O método anterior gravou uma preferência `cadmiumconfig`; os testes do armazenamento passaram, mas o usuário informou que o menu continuava sem 5.1. A leitura posterior confirmou que a preferência ainda estava gravada corretamente; ela não foi suficiente na sessão real. A correção utiliza os parâmetros de URL já validados. O player mantém esses overrides na VideoSession durante a navegação entre títulos. Os dois testes de uso real da nova abertura foram confirmados pelo usuário, e a rota continua em AC-3 640 kbit/s, com os atrasos da tabela acima.

Esses iniciadores preparam a saída do PC. Em 03/10/2026, após aplicar o ajuste de URL descrito abaixo, o usuário confirmou que ele funcionou perfeitamente e enviou uma imagem com **Português (5.1)** oferecido e selecionado. Antes do ajuste, o menu apresentava somente faixas sem “(5.1)”. A imagem confirma a oferta e a seleção da faixa 5.1; o codec e o bitrate recebidos da Netflix ainda não foram medidos pelo diagnóstico.

O diagnóstico local no Edge 154 confirmou saída declarada de seis canais e suporte a E-AC-3, incluindo consultas de PlayReady e Widevine. A API antiga `MSMediaKeys.isTypeSupportedWithFeatures` está ausente. O player público da Netflix 6.0063.110.911 usa essa API no detector Microsoft para verificar DD+ com o recurso `audio-endpoint-codec=DD+JOC`, inclusive para DD+ 5.1 comum. Isso é uma causa provável do bloqueio; o caminho escolhido na sessão autenticada ainda precisa de confirmação.

**Netflix - testar faixa 5.1.cmd** abre [Netflix 5.1 - teste.html](Netflix%205.1%20-%20teste.html) no Edge. Essa página contém o teste manual anterior e o favorito de diagnóstico. Os parâmetros de URL têm prioridade sobre a preferência local. O usuário confirmou o funcionamento desse ajuste em 03/10/2026; os mesmos seis valores agora são enviados automaticamente pelos atalhos de abertura.

O favorito **Conferir Netflix 5.1** mostra somente os parâmetros relevantes e, quando disponíveis, canais, codec e bitrate do áudio apresentado pelo player. **6 canais e `ec-3`** confirmam uma faixa Dolby Digital Plus 5.1; opções habilitadas sem esses dados não são prova. **Desfazer teste Netflix** remove apenas os seis parâmetros da URL; para reverter também a preferência persistente, use o comando **Netflix - desfazer 5.1 automatico.cmd**. O teste não altera a conta.

A Netflix decide a qualidade da faixa recebida. O perfil HQ pode ser solicitado, mas seu bitrate não pode ser garantido. A saída final ao UD851B permanece AC-3 640 kbit/s, após decodificação, processamento e recodificação, mantendo os atrasos da tabela acima. O decoder não recebe E-AC-3 diretamente por essa rota.

Fontes: [suporte oficial Netflix para 5.1 no Windows](https://help.netflix.com/pt/node/14163), [qualidade de áudio](https://help.netflix.com/pt/node/109477) e [player público auditado](https://assets.nflxext.com/player/html/ffe/cadmium-playercore-6.0063.110.911.js). Os resultados locais ficam em `netflix-edge-codecs-resultado.json` e a análise em `auditoria-netflix-publica-2026-10-03.json`.

## YouTube no Opera

A [documentação oficial do YouTube](https://support.google.com/youtube/answer/11904456?hl=pt-BR) informa que o botão **Som surround 5.1** aparece apenas em vídeos e dispositivos compatíveis. A lista documentada inclui TVs, aparelhos de streaming e consoles; não inclui Opera para Windows. Não foi encontrada uma configuração confiável que faça o YouTube oferecer essa faixa e esse botão no Opera.

O áudio estéreo do YouTube continua passando pelo upmix para as seis caixas. Isso distribui o estéreo entre os canais e não recupera os seis canais independentes de uma faixa 5.1 original. Conteúdo 5.1 fornecido por um aplicativo compatível pode usar a mesma rota global.

**Diagnostico de audio do Opera.html** verifica localmente a capacidade de saída do Web Audio e o suporte declarado a alguns codecs. Esses resultados não comprovam que o YouTube entregará áudio 5.1. A página foi preparada, mas a verificação visual no Opera não pôde ser concluída porque a ferramenta de controle não conseguiu identificar a URL com segurança.

### Tentativa experimental no Opera — 03/10/2026

A consulta local executada no Opera 136 declarou saída de seis canais a 48 kHz e suporte a AC-3/E-AC-3 pelo Media Source e Media Capabilities. O arquivo `opera-codecs-resultado.json` registra o resultado. Isso confirma a capacidade declarada, sem medir a decodificação ou a faixa efetivamente reproduzida.

Os metadados públicos do vídeo `nLT8nu-BY6s` incluem AC-3 de seis canais (formato 380) e E-AC-3 de seis canais (formato 328), além de AAC/Opus estéreo. Veja `youtube-metadados-web.json`. As consultas do extrator por cliente TV falharam com “The page needs to be reloaded”; a consulta web do extrator não disponibilizou URLs de mídia. A leitura direta dos metadados da página conseguiu identificar as faixas.

**YouTube Dolby 5.1 - teste.html** contém um favorito executável para tentar habilitar AC-3 e a preferência 5.1 no player atual. As flags e os nomes de métodos foram encontrados no código público do player. Arraste o botão para os favoritos e execute-o em um vídeo do YouTube. O painel tem **Desfazer este teste**. O ajuste usa controles internos, pode deixar de funcionar e **a reprodução ainda não foi validada**. Nenhuma alteração foi instalada automaticamente no perfil do Opera.

Confira o codec nas **Estatísticas para nerds**. Para o vídeo consultado, AC-3/380 ou E-AC-3/328 indicam a faixa multicanal; AAC/140 ou Opus/251 indicam estéreo. Um painel dizendo que a preferência está habilitada não é prova de que essa faixa esteja tocando. A configuração global mantém seus atrasos e recodifica o áudio para AC-3; não é passthrough direto do navegador.

A revisão automática de aprovação bloqueou a abertura de um perfil de teste com identificação de TV, sem apresentar uma justificativa específica. A captura de interface também não mostrou a janela correta do Opera. Por isso, a aplicação do favorito e a leitura do codec no player precisam de uma ação do usuário.

O usuário testou o favorito e informou Opus/251 ou AAC/140: a reprodução continuou estéreo. Uma consulta adicional confirmou que o Opera aceita a consulta de MIME com `channels=99`, que o YouTube utiliza como teste inválido para detectar a capacidade de selecionar áudio multicanal. A pasta **youtube-dolby51-extensao** contém uma segunda tentativa que atua antes de o player iniciar. Seus testes de código passaram, mas **a instalação e a reprodução ainda precisam de validação pelo usuário**. As instruções estão no LEIA-ME da extensão.

Custo adicional: **R$ 0**; foram usados PC, decoder, cabos, amplificadores e software já disponíveis.

Referências: [Equalizer APO — configuração](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/) · [manual do mpv](https://mpv.io/manual/stable/).


## Equalizador do subwoofer

Abra **Equalizador do subwoofer** na area de trabalho, no menu Iniciar ou no botao do painel do sistema. Nove bandas com frequencia, ganho e Q ajustaveis, volume LFE e margem automatica. Clique em **Aplicar e salvar**. O EQ atua somente no LFE e preserva buffers e atrasos da tabela acima. Consulte **Equalizador do sub - instrucoes.md** para os controles e a margem de volume.


### Corte dos graves das surrounds

No painel do sub, **Enviar graves de SL/SR para o sub** corta as surrounds em 90 Hz no ajuste atual e redireciona a parte baixa para o LFE. Corte ajustavel de 40 a 120 Hz, envio por surround e margem de volume da soma. Atua antes dos atrasos, preservando os valores da tabela acima; os ajustes sao salvos e preservados nos perfis de qualidade.

### Reforço do grave da central

Uma cópia da central passa por um filtro passa-baixas de 120 Hz e é somada ao LFE antes dos atrasos. A central original continua com toda a faixa de frequências; somente a cópia grave vai para o subwoofer. A margem de volume da soma considera essa fonte adicional quando está ativa.

