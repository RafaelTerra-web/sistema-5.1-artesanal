# Controle remoto 5.1 no celular

## Abrir no celular

1. Abra **Controle remoto do PC** na Área de Trabalho. Se o Windows pedir autorização para liberar a porta 8787 na rede local, aceite.
2. A página de configuração no PC mostra o endereço atual e o PIN. Conecte o celular ao Wi-Fi do mesmo roteador do PC e abra esse endereço no navegador. O PC pode estar conectado por Ethernet; as bandas 2,4 e 5 GHz funcionam quando pertencem à mesma rede local.
3. Entre com o PIN. Para abrir como aplicativo, use **Adicionar à tela inicial** no Chrome ou Samsung Internet.

O PIN e o endereço também ficam em `endereco.txt`. O servidor usa sessões individuais, exige PIN e só aceita a origem local configurada. A regra do firewall limita o acesso à sub-rede local. Não encaminhe a porta 8787 no roteador.

## O que há no painel

- **Controle:** escolha qualquer janela aberta em **Aplicativo do PC**. No modo **Teclado**, as setas enviam teclas e OK envia Enter; **Próximo foco** e **Foco anterior** percorrem os campos e botões. No modo **Mouse**, as setas movem o ponteiro e OK clica; arraste o dedo na área de toque e toque para clicar. Há rolagem, voltar página e envio de texto ao campo selecionado. A sessão Jellyfin continua com navegação própria pelo servidor.
- **Áudio:** ligar/desligar a rota CM6206, selecionar **PC → USB** ou **TV → óptica AC-3**, e escolher **Auto por formato**, **Dolby / 5.1 preservado** ou **Estéreo sem Dolby confirmado → upmix**. A rota óptica precisa do cabo da TV conectado em SPDIF IN. Auto não identifica estéreo pelo silêncio nas outras caixas. AC-3 e E-AC-3, inclusive estéreo Dolby, devem permanecer preservados.
- **Aplicativos:** entrar no Jellyfin e conferir a extensão opcional da Netflix. A senha do Jellyfin não é salva em arquivo; o token fica apenas na memória da sessão pareada.
- **Volume mestre:** a faixa inferior fica acessível nas três abas. Salva o ganho linear e o mudo no gerenciador CM6206, que aplica o ajuste pelo IPC do player. Uma confirmação de aplicação ao vivo exige o reconhecimento do gerenciador; um valor salvo com a rota desligada vale para a próxima ativação.

Desde 09/10/2026, o painel delega início, parada e restauração da rota ao gerenciador CM6206. O estado ligado exige saída WASAPI e avanço da reprodução, além de processo e confirmação recentes. Isso confirma a cadeia de software, e não a audibilidade ou a posição física de cada caixa. O equalizador e os perfis 448/640 kbps do codificador HDMI antigo ficam bloqueados nesta cadeia para evitar ajustes sem efeito na rota atual. Os atrasos aparecem a partir dos metadados da sessão: **FL/FR 76,8 ms, central 5,8 ms, LFE 5,8 ms e surrounds 71 ms** são os valores solicitados para esta etapa. A interface aguarda os metadados em vez de mostrar esses valores como aplicados em uma sessão desconhecida.

### Roteamento dos aplicativos

Na rota PCM, console e multimídia usam VB-CABLE. Uma preferência individual de saída pode continuar direcionando o aplicativo a outra placa: por isso a configuração local aceita `Applications`, uma lista de nomes `.exe`, compatível com o campo anterior `Application`. Com `DiscoverActiveApplications` habilitado, também considera sessões ativas de Opera, Edge, Chrome, Spotify e VLC. O player de saída, FxSound e os processos do DSP são excluídos para evitar realimentação. Aplicativos que escolhem diretamente outro dispositivo ou usam sessão exclusiva precisam de configuração própria. Depois de trocar a rota, F5 ou reabrir o fluxo pode ser necessário para o aplicativo seguir o novo dispositivo. A implementação dessa troca depende da [API usada pelo aplicativo](https://learn.microsoft.com/en-us/windows/win32/coreaudio/stream-routing).

O gerenciador journaliza as sessões observadas antes da troca e informa os aplicativos direcionados em **Estado do sistema**. Isso não recupera a preferência persistente anterior do Windows: [SoundVolumeView](https://www.nirsoft.net/utils/sound_volume_view.html) fornece seleção por aplicativo e sessão, mas o snapshot usado aqui não contém um backup completo dessa política. Na parada, só restaura uma preferência se a sessão observada ainda estiver no VB-CABLE. Usa a saída anterior observada ou o padrão do sistema quando essa saída era desconhecida; sessões ausentes, ambíguas ou já alteradas permanecem com aviso para conferência.

O upmix manual é aplicado depois que o Windows misturou os aplicativos. Assim, não permite preservar simultaneamente um programa Dolby/5.1 de outro aplicativo. Use Auto/Native para proteger fontes desconhecidas, AC-3 e E-AC-3. O upmix por fonte antes desse mixer, com metadados de codec e canais, é a opção apropriada para conteúdo simultâneo de formatos diferentes.

### Jellyfin

Entre no Jellyfin pela aba **Aplicativos** deste controle e mantenha o Jellyfin aberto no navegador do PC. Escolha a sessão em **Controle → Aplicativo do PC**. É possível navegar mesmo sem vídeo reproduzindo. Se os títulos não receberem foco, use o modo TV em **perfil → Exibição** do Jellyfin no PC ou selecione a janela do navegador e use o modo Mouse.

**Buscar** abre a pesquisa. Digite no controle e toque em **Enviar** quando o campo de texto estiver selecionado no Jellyfin. O suporte de áudio e legendas depende das faixas disponíveis no título.

### Netflix

Na aba **Aplicativos**, use **Abrir Netflix 5.1 no PC** para abrir o aplicativo instalado com o ajuste surround automático e ativar a saída AC-3 de 640 kbit/s. **Abrir no Edge** usa o mesmo ajuste em uma aba. Em **Aplicativo do PC**, escolha a janela Netflix. No catálogo, use **Próximo foco + OK**, ou o **modo Mouse** para apontar e clicar nos títulos e botões. Durante o filme, há reprodução/pausa, avanço/retorno e **Pular intro Netflix**. O volume mestre controla a rota 5.1.

O controle por teclado e mouse funciona sem extensão. Os atalhos variam conforme o aplicativo: Voltar envia Esc, Página anterior envia Alt+Esquerda e Início envia Ctrl+Home. Para selecionar legendas, áudio, pular recapitulações ou trocar episódio, use o modo Mouse sobre os botões do próprio player. O PC deve estar desbloqueado; uma janela executada como administrador pode recusar comandos do controle.

Use **Controlar Netflix** na aba Controle para escolher o app e ativar o modo Mouse em um toque. A opção **Netflix · conectar automaticamente** acompanha a janela quando o app é fechado e reaberto. Ela também remove uma seleção de reprodução antiga de outro aplicativo. O app instalado é preferido quando existem uma janela Netflix e uma aba Netflix no navegador. O modo Teclado continua disponível e sua escolha para Netflix é lembrada separadamente.

A abertura do app pela API autenticada foi testada em 04/10/2026, com a rota Dolby ligada e saída configurada em 640 kbit/s. Isso valida o iniciador, não a disponibilidade de uma faixa 5.1 em todos os títulos. A troca de idiomas e legendas requer a extensão opcional para Edge na pasta `extension`; com ela conectada, escolha **Netflix · extensão**. A API interna de faixas da Netflix pode mudar; o painel informa quando não estiver disponível. A extensão e a troca de faixas ainda não foram testadas no aplicativo instalado.

## Verificação e diagnóstico

O painel, a autenticação e o estado foram verificados no PC, inclusive no tamanho de tela do A34. Dez testes automatizados passaram: recuperação do processo auxiliar, limite da fila, segurança HTTP, volume concorrente, repetição das setas e respostas atrasadas. O reinício controlado da rota, antes do ajuste frontal de 7,7 ms, confirmou **Dolby Digital 5.1 ligado, perfil Fidelidade e 70 ms de correção**. A navegação real no Jellyfin aguarda uma sessão aberta e conectada pelo usuário.

Em 04/10/2026, a versão com controle universal passou em **13 testes automatizados**, incluindo navegação sem sessão de mídia, modo mouse, envio de texto e troca de janela durante comandos pendentes. O teste `powershell.exe -NoProfile -ExecutionPolicy Bypass -File configuracao-pc/controle-remoto/test-universal-input.ps1` exercitou a rota HTTP real em duas janelas criadas para o teste: ativação da janela escolhida, Unicode, Tab/Enter, clique do mouse e rejeição de uma janela encerrada. O estado final confirmou rota 5.1 ativa no perfil Fidelidade. Isso valida o controle nativo; a reação de cada atalho continua dependendo do aplicativo.

O iniciador seleciona uma interface conectada com IPv4 válido e ignora endereços antigos de interfaces desconectadas. O endereço do celular acompanha a interface selecionada. Abra o atalho da Área de Trabalho para iniciar o controle na rede local e atender ao pedido de autorização do Windows, quando necessário. Para encerrar use `configuracao-pc/Parar controle remoto.cmd`.

### Subgrave da cadeia antiga

A curva registrada na cadeia antiga tinha reforços que se somavam perto de 26 Hz, calculados em aproximadamente **+14 dB antes da margem da soma de graves**. Este registro é histórico; não descreve o DSP da nova rota CM6206. Os testes de ganho, crossover e subgrave atuais estão em [PC-CM6206-PCM.md](../../docs/PC-CM6206-PCM.md).

Em 09/10/2026, passaram **20 testes Node** de interface/backend/YouTube e verificações PowerShell isoladas de timer, estado recente, identidade de processo, ganho/mudo e exibição dos atrasos da sessão, incluindo LFE 5,8 ms. Esses testes não reproduzem áudio nem validam o hardware.
