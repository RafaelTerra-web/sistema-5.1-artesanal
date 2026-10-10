# Retomada do sistema 5.1 — 10/10/2026

Este é o estado consolidado para continuar o projeto. Consulte também o [relatório 0.9.1 e controle no A55](A34-CONTROLE-A55-E-REVISAO-2026-10-10.md), o [pipeline óptico Fire TV → A34](FIRE-TV-A34-PIPELINE-CONTINUO-2026-10-09.md) e o [plano de controle de volume Fire TV](FIRE-TV-CONTROLE-DE-VOLUME-A34.md).

**Aplicativos e aparelhos:** DSP 0.9.1 (`versionCode 16`) instalado no Galaxy A34 por depuração sem fio; o app de controle 0.1.0 está no A55. A reinstalação preservou os dados do perfil. A correção do equalizador geral já está no A34, mas ainda precisa ser ouvida com uma fonte que tenha energia na banda ajustada. O novo código preserva AC-3/E-AC-3 estéreo; está sendo preparado como DSP 0.9.2 (`versionCode 17`) e ainda precisa ser instalado no A34.

**Áudio e transporte:** a arquitetura óptica contínua SPDIF IN → USB duplex da CM6206 → parser IEC61937/AC-3 → decoder FFmpeg → DSP → PCM 5.1 está integrada. OTG direto é o caminho físico que funcionou; o hub PD continua instável mesmo com carregamento confirmado. Há janelas de execução contínua estéreo e 5.1 documentadas, mas a observação também registrou reposições de fila, quadros inválidos e descontinuidades em algumas transições. A rota Fire TV → HDMI 2 da Bravia → óptica → CM6206 → A34, em sessões longas e com transições, ainda não está aprovada como confiável.

**Regra atual de upmix:** apenas PCM ou codec não Dolby conhecido, com um ou dois canais. AC-3/E-AC-3 nunca recebe upmix, mesmo quando decodificado como mono/estéreo; conteúdo 5.1 mantém seus canais. AC-3 estéreo na entrada óptica deve sair só em FL/FR. Portadora IEC61937 jamais passa pelo DSP como PCM; formatos desconhecidos não autorizam upmix.

**Ajustes e escuta confirmados anteriormente:** na rota do PC, mestre 0,15, sub +3 dB, metade dos graves da central enviados ao sub e atrasos FL/FR 76,8 ms, FC 5,8 ms, SL/SR 71 ms e LFE 5,8 ms. O usuário confirmou áudio limpo e caixas ativas nessa rota. Não copiar automaticamente esse equilíbrio físico para o perfil do A34.

**Próxima validação:** atualizar o APK após este commit e conferir que a mudança de EQ é audível sem clipping. Depois, com amplificadores em volume seguro e antes de qualquer reprodução, fazer teste controlado dos canais/mapeamento AC-3 e observar a rota contínua; investigar eventos de fila e o hub. Fire TV/Bravia e a sincronização do master do A34 com o volume da Fire TV seguem no plano vinculado. Capturas, journals, endereços locais e APKs de trabalho permanecem fora do Git.

## Resultado auditivo e parâmetros PC

A rota **Opera → extensão de upmix → VB-CABLE → DSP no PC → USB CM6206 → amplificadores** foi ouvida em todas as categorias de caixas. Após reduzir os ganhos das frontais/surrounds e corrigir o headroom, o usuário confirmou som limpo; com +3 dB no sub e metade dos graves da central enviados ao sub, confirmou presença adequada. Essa escuta não é calibração acústica nem valida a rota A34.

| Parâmetro da bancada PC | Valor confirmado |
|---|---|
| Mestre DSP | 0,15 linear |
| Trim central / sub | 0 dB / +3 dB |
| Envio dos graves da central ao sub | 50%, abaixo de 90 Hz |
| Crossover satélites / subsônico LFE | LR4 90 Hz / 20 Hz |
| Atrasos FL/FR | 76,8 ms |
| Atrasos FC e LFE | 5,8 ms |
| Atrasos SL/SR | 71 ms |
| Modo do relay / swap FC-LFE | PCM Native / desligado |
| Volumes por canal no último perfil Windows | FL/FR e surrounds −23 dB; FC −14 dB; LFE −12 dB |

O mapa Windows confirmado é slot USB 2 → central e slot USB 3 → LFE/sub, índices a partir de zero; não inverter sem novo teste. No PC, o par surround físico ainda precisa de identificação final. Não copiar trims do Windows para o A34.

## Instalação e validação A34

- A34 (`SM-A346M`): DSP 0.9.1, versionCode 16 instalado por depuração Wi-Fi; perfil preservado. A mudança do EQ geral está neste binário, mas falta confirmar auditivamente que uma banda aumenta de forma perceptível sem clipping.
- Revisão atual do código: DSP 0.9.2, versionCode 17, compilado com JDK 21; instalação ainda pendente. Esta versão aplica a política AC-3 corrigida descrita acima.
- A55: app companion 0.1.0 instalado; descoberta/autenticação e painel remoto confirmados pelo usuário. O app apenas controla o A34, sem processar o áudio.
- A rota óptica completa está implementada. Em OTG direto houve janelas contínuas de AC-3 estéreo e 5.1. A observação também registrou reposições de fila, dois CRC inválidos descartados e descontinuidades; sessões longas e mudanças de formato ainda precisam ser aprovadas.
- OTG direto é o caminho comprovado. O hub PD falhou no duplex mesmo com o A34 carregando; causa entre hub, cabo e negociação ainda não isolada. O usuário não tem outro cabo USB.
- A Bravia foi usada em Sistema de áudio / Auto 1 / Dolby Digital Plus Out Não; a TV entregou AC-3 48 kHz. E-AC-3 direto e PCM óptico não são entradas aprovadas pelo parser atual.

O resultado AC-3 estéreo com upmix anotado no relatório da 0.9.1 pertence à política experimental anterior. A versão 0.9.2 deve mostrar upmix desligado para AC-3 estéreo e manter só FL/FR; para 5.1 AC-3, preservar os seis canais. A revisão de software não substitui teste acústico da ordem de caixas no A34.

## Próximas etapas

1. Instalar 0.9.2 no A34 via depuração Wi-Fi e conferir que o perfil e os atrasos permanecem. Testar o EQ com uma fonte estável que contenha energia na banda escolhida, em nível seguro; observar clipping e fazer uma alteração por vez.
2. Preparar fala/tons separados para FL, FR, FC, LFE, SL e SR em nível baixo. Esperar o comando “toca” antes de reproduzir. Confirmar o mapa físico da saída A34, especialmente FC/LFE e o par surround.
3. Medir a continuidade do A34 em AC-3 estéreo e 5.1, trocas de formato, pausa/seek e retomada; investigar a fila, CRC/descontinuidades e o comportamento de mute/parada. Não declarar estabilidade prolongada com base em uma janela curta.
4. Retomar a comparação do hub/PD quando houver uma conexão física confiável. Manter OTG direto enquanto isso.
5. Com Fire TV em uso, validar a montagem HDMI 2 → Bravia → óptica → CM6206 → A34 e confirmar o codec realmente recebido em cada caso. A TV não leva volume pela óptica; seguir o [plano de controle de volume da Fire TV pelo A34](FIRE-TV-CONTROLE-DE-VOLUME-A34.md) para escolher e testar uma ponte IR/CEC viável.

O [relatório 0.9.1 e controle A55](A34-CONTROLE-A55-E-REVISAO-2026-10-10.md) contém as medições detalhadas; o [pipeline óptico](FIRE-TV-A34-PIPELINE-CONTINUO-2026-10-09.md) descreve a implementação e os limites do decoder. Capturas, journals, IPs, tokens e APKs ficam locais/ignorados. Não iniciar dois gerenciadores para a mesma CM6206.
