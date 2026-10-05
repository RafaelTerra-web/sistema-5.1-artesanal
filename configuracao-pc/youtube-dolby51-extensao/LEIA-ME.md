# Extensão experimental de Dolby Digital 5.1

## O que foi encontrado

O Opera declara suporte a AC-3/E-AC-3 e saída de seis canais. O vídeo `nLT8nu-BY6s` anuncia os formatos 380 (AC-3, seis canais) e 328 (E-AC-3, seis canais). O player público do YouTube descarta a seleção Dolby se seu teste do parâmetro `channels` não distinguir os valores 2 e 99. No Opera instalado, ambos retornam suporte; o navegador ignora esse parâmetro de MIME. O primeiro teste com favorito continuou selecionando estéreo.

## O que esta extensão tenta

Antes de o player iniciar, recusa apenas o teste de MIME `audio/*; channels=99`. Todas as consultas de codecs e números de canais válidos continuam usando a implementação original. Também habilita as flags de AC-3 e áudio 5.1 do player, e solicita a preferência 5.1. A versão 0.1.0 é experimental: ainda precisa ser instalada e validada na reprodução.

A extensão só executa em `https://www.youtube.com/*`, inclusive navegação entre vídeos sem recarregar a página. Não possui permissões extras, processo em segundo plano, acesso a arquivos, histórico, cookies nem serviço externo de coleta. O script atua no contexto da página do YouTube.

## Instalar manualmente no Opera

1. Abra `opera://extensions`.
2. Ative **Modo de desenvolvedor**.
3. Escolha **Carregar sem compactação** / **Carregar extensão descompactada**.
4. Selecione esta pasta `youtube-dolby51-extensao`, que contém `manifest.json`.
5. Atualize o vídeo com F5. O código precisa rodar antes da criação do player; o favorito anterior não substitui essa etapa.

Esse passo deve ser feito pelo usuário: a skill **computer-use** impede alterar configurações de segurança do navegador e exige confirmação para instalar software fora das fontes reconhecidas. Além disso, a captura de interface nesta sessão não identificou a janela correta.

## Verificação

Abra `https://www.youtube.com/watch?v=nLT8nu-BY6s`. Clique com o botão direito no vídeo → **Estatísticas para nerds**. Neste vídeo:

- Áudio `ac-3` / formato `380`: faixa Dolby Digital de seis canais.
- Áudio `ec-3` / formato `328`: faixa Dolby Digital Plus de seis canais.
- Áudio `opus` / formato `251` ou `mp4a` / formato `140`: continua estéreo.

O ícone da extensão ou a preferência 5.1 ativada não comprovam que a faixa está tocando. A presença do botão Surround oficial também não é garantida. O sistema global continua decodificando/recodificando para AC-3 com 76,8 ms em FL/FR, 5,8 ms na central, 71 ms em SL/SR e 0 ms no LFE.

## Desfazer

Desative ou remova a extensão em `opera://extensions` e atualize o YouTube. As alterações de consulta de MIME e flags desaparecem ao recarregar. A preferência 5.1 salva pelo próprio YouTube pode permanecer; ela não altera a saída padrão do Windows nem o relay de áudio.
