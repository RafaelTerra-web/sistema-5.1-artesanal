# Tentativa de YouTube Dolby Digital 5.1 no Opera

## Resultado disponível

- Opera 136: declaração de seis canais, AC-3 e E-AC-3 disponíveis. Registro: `opera-codecs-resultado.json`.
- Vídeo `nLT8nu-BY6s`: a resposta pública web anuncia AC-3/380 e E-AC-3/328 de seis canais. Registro: `youtube-metadados-web.json`.
- Primeiro experimento com favorito: o usuário informou Opus/251 ou AAC/140; continuou estéreo.
- Sistema global: deixado ligado; FL/FR/SL/SR continuam em 70 ms e CEN/LFE em zero.

## Experimento preparado

Abra `YouTube Dolby 5.1 - teste.html` no Opera, arraste o botão azul para a barra de favoritos e clique nesse favorito com o vídeo aberto. O código está em `youtube-dolby51-bookmarklet.js`.

O favorito verifica a existência dos métodos antes de agir, habilita as flags de áudio AC-3, tenta recarregar o player no ponto atual e solicita a preferência 5.1. Não simula um dispositivo de TV. O botão **Desfazer este teste** restaura as configurações anteriores enquanto o mesmo vídeo estiver aberto.

Confira **Estatísticas para nerds → Codecs**. Neste vídeo, áudio AC-3/380 ou E-AC-3/328 é multicanal; Opus/251 ou AAC/140 é estéreo. A separação física das caixas ainda precisa ser confirmada por escuta ou medição.

O teste depende de APIs internas e não documentadas do YouTube. A validação de sintaxe passou; não foi possível executar e verificar o favorito na sessão do usuário porque a captura de interface retornou outra janela. A revisão automática bloqueou a tentativa anterior de abrir um perfil com identificação de TV; não informou uma razão específica.

## Segunda tentativa: extensão antes da inicialização

Foi identificado um bloqueio adicional no código do player: `pt(u.A,Cd.CHANNELS)` só considera o parâmetro de canais utilizável quando o teste válido de dois canais passa e o inválido de 99 falha. No Opera, ambos passam; o parâmetro é ignorado. A consulta local registrou esse comportamento para AAC, AC-3 e E-AC-3 em `opera-codecs-resultado.json`.

A pasta `youtube-dolby51-extensao` contém uma extensão Manifest V3 que corrige apenas a consulta inválida de 99 canais, habilita as flags de áudio antes da inicialização e solicita a preferência 5.1. Os testes de inicialização, preservação das demais flags e manutenção dos codecs sem suporte passaram. **A reprodução com a extensão ainda depende de instalação e teste pelo usuário.** Veja o LEIA-ME dentro da pasta para instalar e desfazer.

## Referências

- [YouTube — compatibilidade oficial do botão 5.1](https://support.google.com/youtube/answer/11904456?hl=pt-BR).
- [Vídeo consultado](https://www.youtube.com/watch?v=nLT8nu-BY6s).
- Os nomes `html5_enable_ac3`, `html5_enable_eac3`, `getUserAudio51Preference`, `setUserAudio51Preference` e `hasSupportedAudio51Tracks` foram localizados no JavaScript público carregado pelo vídeo. São detalhes internos, sem garantia de estabilidade.
