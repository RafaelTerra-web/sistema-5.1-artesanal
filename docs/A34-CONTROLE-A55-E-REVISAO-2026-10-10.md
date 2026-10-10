# A34 0.9.1 e Controle 5.1 no A55

Continuação de 10/10/2026: melhorar áudio, corrigir falhas e interface, e instalar por ADB um controle no Galaxy A55.

## Entrega

- DSP A34 0.9.1, versionCode 16; companion `Controle 5.1` 0.1.0, pacote `br.com.sistema51.controller`, instalado no A55 real.
- Painel responsivo com mestre 0–100 destacado, Silenciar/Retomar som, estado de execução e medidores. Engrenagem reúne seis volumes por caixa e EQ geral de dez bandas; opções avançadas ficam recolhidas. Volume/trim mantêm curva e unidades anteriores, sem migrar ganhos.
- App busca o serviço `A34 DSP` via DNS-SD/mDNS e oferece conexão por IP quando a rede não entrega a descoberta. Confere a identidade do serviço antes de abrir o painel; lembra o último endereço e exige seleção novamente se outro DSP ocupar esse IP. A autenticação continua com o PIN de quatro dígitos do A34.
- Sessão do painel lembrada por até 12 h, sujeita à validade no A34. Reiniciar o serviço invalida os tokens temporários e pode pedir o mesmo PIN novamente. Desconectar apaga a sessão salva. PIN não é guardado pelo companion.
- O companion controla o servidor existente, sem capturar, decodificar ou processar áudio no A55. O WebView limita navegação/subrecursos à origem local selecionada, sem acesso a arquivos ou ponte JavaScript nativa.
- Não foi configurado domínio público ou DNS do roteador. O nome fácil implementado é a descoberta de serviço no app; não afirmar que `a34-dsp.local` funciona como endereço em navegador. A URL IP exibida no A34 continua sendo alternativa.

## Correções e áudio

- Corrigido bloqueio permanente após `Teste óptico anterior precisa de recuperação`: o transporte passa a recuperar também o journal do diagnóstico, conferir a entrada real e persistir a restauração antes de abrir outra sessão. Só aceita estado igual ao original registrado ou ao seletor S/PDIF temporário; outra entrada ou journal inválido interrompe sem sobrescrever o hardware. O usuário confirmou que a mensagem deixou de ocorrer.
- Decoder AC-3 usa buffer PCM reutilizado no caminho contínuo, evitando criar vetor de amostras, metadados e objeto de resultado a cada quadro. Checagem CRC, layout, taxa e 1.536 amostras continuam obrigatórias; DRC/dialnorm permanecem conforme configuração anterior.
- EQ desligado evita percorrer os filtros a cada amostra; filtros em transição conservam a cauda. Compensação de ganho do EQ geral passa a acompanhar a rampa de 10 ms dos coeficientes, sem salto imediato no mestre e sem limpar atrasos.
- Alterações de mestre/trims reutilizam os coeficientes e a margem de EQ quando as frequências/ganhos dos filtros não mudaram. Evita recompilar a resposta de 1.025 pontos durante cada gesto de volume. Nenhum filtro ou ganho salvo é modificado por essa otimização.
- Telemetria conta também clipping causado por overshoot da interpolação na conversão PCM16 e mede o pior bloco de processamento, além da média. Não adiciona compressor, loudness automático ou ganho extra no sub.
- Sliders preservam a posição ainda não confirmada durante escrita em curso. Conflito de revisão entre dois controles permite uma única recomposição do gesto sobre o perfil mais recente, preservando canais/bandas do outro controle. Falha de rede retém o último gesto e para as tentativas; novo gesto ou Atualizar permite tentar novamente.
- Requisições do painel têm limite de 5 s. Resposta tardia de login/comando não reabre o painel após Desconectar. Automático captura o valor escolhido antes de aguardar a rede. Medidores são reutilizados em vez de recriados a cada consulta.

## Testes

Na versão 0.9.1, compilação e lint dos dois APKs passaram. 64 testes JVM do A34 e três do companion passaram; teste Node do painel inclui conflito entre controles, falha de rede, gesto preservado, login cancelado e ordenação de respostas.

No A34, teste offline de decoder: 282 quadros 5.1, 433.152 amostras por canal, saída PCM reutilizada exatamente igual à saída anterior em cada amostra; CRC inválido e quadro truncado rejeitados; reset determinístico. Esse teste não toca USB nem caixas.

Instalação verificada por ADB nos aparelhos corretos. O usuário confirmou painel conectado e Fire TV executando pelo app do A55. Interface conferida no navegador e em captura de tela do A55; o perfil encontrado antes da atualização foi preservado. Alterações posteriores de volume/caixas/EQ foram feitas pelo usuário durante a validação.

## Evidência física e limites

O hub voltou a falhar no duplex silencioso mesmo com PD na tomada e A34 carregando: errno 108, aproximadamente 1,1 s de dados, sem restauração confirmada. Por isso não foi confirmada ausência de energia como causa única. OTG direto passou 10 s de duplex silencioso, com 311/311 quadros AC-3 íntegros, e o usuário confirmou que voltou a funcionar ao usar OTG. A investigação do hub/cabos/negociação permanece pendente; usar o OTG para a validação atual.

Antes da atualização, sessão completa 0.8.6 pelo OTG terminou com 22.708.224 frames capturados, cerca de 473 s, sem CRC inválido, erro USB, underrun ou clipping. A 0.9.0 registrou reposições de fila durante manipulação dos controles. A 0.9.1 acrescentou o cache de filtros; a observação final será registrada abaixo, sem atribuir causalidade definitiva a uma única otimização.

**Resultado final observado com a 0.9.1:** observação somente de leitura de 90,29 s, 147 consultas, fonte AC-3 estéreo com upmix (comportamento experimental anterior à política atual). Nenhum errno, erro de pacote/sequência, novo underrun ou clipping; os contadores de underrun e clipping permaneceram zero. Fila observada entre 3.075 e 4.608 frames, diminuindo ao longo da correção de relógio. Snapshot posterior: 6.950.400 frames capturados (144,8 s de áudio), CRC/discontinuidade zero, nenhum refill. O usuário fez alterações remotas durante a sessão; a leitura não mudou volume/mute nem iniciou áudio. Esses resultados aprovam essa janela no OTG, sem garantia universal de estabilidade, aprovação acústica ou teste prolongado desta versão em 5.1.

Depois, a entrada mudou para AC-3 de seis canais e o DSP desativou o upmix. Uma tentativa de observação contínua foi interrompida por `waiting_signal`, sem erro USB; a saída foi silenciada e retomou quando o Dolby voltou. A sessão acumulou quatro eventos de reposição de fila, sem comprovação de que todos foram causados pelas trocas/pausas. No novo trecho 5.1 contínuo: **30,81 s / 38 consultas**, seis canais nativos com amostras presentes também no LFE, nenhum novo underrun (contador estável em 4), clipping zero e nenhum erro USB. Fila entre 2.321 e 2.879 frames. Não anunciar zero underruns na sessão inteira, nem que transições e sessões longas estejam completamente aprovadas.

Snapshot de encerramento da observação, mantendo a reprodução do usuário: AC-3 6 canais, upmix desligado, automático ativo, runtimeError vazio, clipping zero; totais acumulados de cinco reposições de fila, dois CRCs inválidos descartados e nove descontinuidades, com 25.820.160 frames decodificados. A sessão permaneceu executando. Não foi isolada a causa de cada evento. A aprovação das duas janelas contínuas não elimina esses eventos nem autoriza dizer que toda a sessão ficou sem falhas.

Latência continua estimada por quadro AC-3, fila, USB e atrasos salvos; não houve medição acústica ou A/V. Não reduzir buffers nem apagar calibração para anunciar redução de latência. Os trechos contínuos estéreo e 5.1 desta revisão foram exercitados nas janelas acima; ensaio prolongado 5.1, transições e investigação do hub permanecem pendentes. Não declarar todos os bugs possíveis eliminados.

Referências de implementação: [descoberta de serviços Android](https://developer.android.com/develop/connectivity/wifi/use-nsd), [validação de URI no WebView](https://developer.android.com/privacy-and-security/risks/unsafe-uri-loading). A descoberta do app segue a API NSD do Android; seu anúncio não cria um hostname personalizado.

## Atualização posterior: equalizador e política AC-3

O usuário relatou que aumentar uma banda no EQ geral do A34 parecia não alterar o som. A causa era a compensação de headroom: o maior reforço era compensado integralmente no sinal todo, inclusive com mestre baixo. O DSP agora estima a resposta combinada das bandas e considera mestre e trims; mantém o reforço quando há margem digital e reduz o nível apenas quando o pico previsto pode saturar. O ganho de compensação acompanha a rampa dos filtros. O cache reutiliza coeficientes quando só mestre ou trims mudam.

O APK debug com a correção do EQ foi recompilado com JDK 21 e reinstalado por ADB via depuração sem fio no A34 (`SM-A346M`, pacote `br.com.sistema51.a34`, versão 0.9.1 / `versionCode 16`). A instalação de atualização foi confirmada pelo Package Manager e preservou o perfil. O usuário ainda não confirmou auditivamente o efeito desta correção; validar com música que tenha energia na frequência alterada, sem elevar o volume mestre.

Na revisão de compatibilidade, a política foi alinhada ao pedido explícito: upmix apenas de mono/estéreo PCM ou não Dolby conhecido; AC-3/E-AC-3 nunca recebe upmix e 5.1 nativo fica intacto. Na entrada óptica AC-3 estéreo, o código expande o buffer para seis canais com L/R nas frontais e os demais canais zerados antes do DSP, para que o modo nativo não crie central/surround/LFE. O teste do painel também confirma que AC-3 de dois canais aparece como canais nativos. A revisão forma a versão 0.9.2 (`versionCode 17`); compilação e lint dos dois APKs, 65 testes JVM do A34, três do companion e teste Node do painel passaram. Instalação no A34 concluída e verificada por ADB: pacote `br.com.sistema51.a34`, Galaxy A34 (`SM-A346M`), versão 0.9.2 / `versionCode 17`; dados do app preservados.

## Atualização: cópia ajustável da central para as frontais

Foi adicionado ao DSP do A34 um controle `Roteamento da central` (0–100%, padrão 35%) para enviar uma cópia ajustável do canal central às frontais esquerda e direita. A central original continua intacta e mantém seu próprio trim/volume. O ganho da cópia é aplicado antes dos atrasos e trims individuais e faz rampa de 10 ms.

A cópia só é aplicada a entrada PCM discreta de seis canais. O upmix de estéreo não passa por essa cópia, evitando duplicar o sinal central já criado pelo upmixer e elevar indevidamente o nível das frontais. A opção é persistida no perfil local e sincronizada pelo painel remoto servido pelo A34, portanto o controle aparece também no companion do A55 sem mudança no APK do companion.

Verificação de software: teste Node do painel remoto passou; `:app:testDebugUnitTest`, `:controller:testDebugUnitTest`, `:app:assembleDebug` e `:controller:assembleDebug` passaram. Foram aprovados 65 testes JVM do A34 e os testes do companion. A compilação gerou o APK debug do A34 versão 0.9.2 (`versionCode 17`).

Implantação: APK 0.9.2 instalado no Galaxy A34 (`SM-A346M`) por ADB sem fio; Package Manager confirmou versão 0.9.2 / código 17. A atividade principal foi reaberta e o servidor local voltou a servir os recursos atualizados. O companion 0.1.0 no Galaxy A55 foi reaberto para recarregar o painel remoto; o APK do A55 não foi reinstalado, pois a interface vem do A34 e o código local do companion não mudou. Como o serviço remoto do A34 foi reiniciado, a sessão temporária anterior pode ter expirado e pode ser necessário informar novamente o PIN local.

Limite da validação: confirmação de instalação e presença do controle no recurso servido não comprovam ainda o comportamento acústico da nova cópia em 5.1. A validação auditiva no A55 e a conferência individual de nível/posição continuam pendentes. Nenhum áudio foi iniciado durante a implantação.
