# Controle remoto 5.1 no celular

## Abrir no celular

1. Abra **Controle remoto do PC** na Área de Trabalho. Se o Windows pedir autorização para liberar a porta 8787 na rede local, aceite.
2. A página de configuração no PC mostra o endereço atual e o PIN. Conecte o celular ao Wi-Fi do mesmo roteador do PC e abra esse endereço no navegador. O PC pode estar conectado por Ethernet; as bandas 2,4 e 5 GHz funcionam quando pertencem à mesma rede local.
3. Entre com o PIN. Para abrir como aplicativo, use **Adicionar à tela inicial** no Chrome ou Samsung Internet.

O PIN e o endereço também ficam em `endereco.txt`. O servidor usa sessões individuais, exige PIN e só aceita a origem local configurada. A regra do firewall limita o acesso à sub-rede local. Não encaminhe a porta 8787 no roteador.

## O que há no painel

- **Controle:** escolha qualquer janela aberta em **Aplicativo do PC**. No modo **Teclado**, as setas enviam teclas e OK envia Enter; **Próximo foco** e **Foco anterior** percorrem os campos e botões. No modo **Mouse**, as setas movem o ponteiro e OK clica; arraste o dedo na área de toque e toque para clicar. Há rolagem, voltar página e envio de texto ao campo selecionado. A sessão Jellyfin continua com navegação própria pelo servidor.
- **Áudio:** ligar/desligar a rota 5.1, escolher Dolby Digital 640 kbps ou perfil estável de 448 kbps, ativar upmix automático ou preservar trechos originalmente 5.1. Trocar o perfil pode interromper o som por alguns segundos.
- **Aplicativos:** entrar no Jellyfin e conferir a extensão opcional da Netflix. A senha do Jellyfin não é salva em arquivo; o token fica apenas na memória da sessão pareada.
- **Volume mestre:** a faixa inferior fica acessível nas três abas. Ajusta as seis caixas na rota processada, sem refazer o grafo a cada toque.

O atraso de correção é **76,8 ms para FL/FR**, **5,8 ms para a central**, **71 ms para SL/SR** e **0 ms para LFE**. O painel do subwoofer controla o equalizador e o corte dos graves das surrounds. Uma cópia dos graves da central abaixo de 120 Hz é somada ao LFE, sem cortar a central. A escolha de perfil e o volume preservam esses ajustes.

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

### Subgrave

A curva atual tem reforços que se somam perto de 26 Hz. Um cálculo do EQ indica aproximadamente **+14 dB antes da margem da soma de graves**, com possibilidade de saturação em sinais LFE fortes. Isto não prova distorção no áudio reproduzido; não alterei a curva escolhida. Confira o resultado em escuta ou medição. Uma proteção moderada de pico após o EQ pode ser incluída caso seja necessário.
