# Retomada do sistema 5.1 — 10/10/2026

Este é o estado consolidado para continuar o projeto. A atualização atual é descrita no [relatório da versão 0.10.0 / controle 0.2.0](ATUALIZACAO-AC3-ESTEREO-CORRECAO-DE-SALA-2026-10-10.md). Consulte também o [relatório 0.9.1 e controle no A55](A34-CONTROLE-A55-E-REVISAO-2026-10-10.md), o [pipeline óptico Fire TV → A34](FIRE-TV-A34-PIPELINE-CONTINUO-2026-10-09.md) e o [plano de controle de volume Fire TV](FIRE-TV-CONTROLE-DE-VOLUME-A34.md).

**Aplicativos:** DSP A34 0.10.0 (`versionCode 18`) e controle A55 0.2.0 (`versionCode 2`) foram compilados com a correção de sala e os testes aprovados. A versão inclui upmix AC-3 mono/estéreo e preservação AC-3 5.1 discreta. A instalação física deve ser registrada após confirmação por ADB; não inferir instalação só porque os APKs foram preparados.

**Áudio e transporte:** a arquitetura óptica contínua SPDIF IN → USB duplex da CM6206 → parser IEC61937/AC-3 → decoder FFmpeg → DSP → PCM 5.1 está integrada. OTG direto é o caminho físico que funcionou; o hub PD continua instável mesmo com carregamento confirmado. Há janelas de execução contínua estéreo e 5.1 documentadas, mas a observação também registrou reposições de fila, quadros inválidos e descontinuidades em algumas transições. A rota Fire TV → HDMI 2 da Bravia → óptica → CM6206 → A34, em sessões longas e com transições, ainda não está aprovada como confiável.

**Regra atual de upmix:** PCM, codec não Dolby conhecido e AC-3 mono/estéreo recebem upmix; AC-3 5.1 permanece nos seis canais originais, sem duplicação para as frontais. E-AC-3 direto e codecs/layouts desconhecidos não recebem upmix. A Bravia deve permanecer em Auto 1 enquanto a captura PCM óptica da CM6206 não for corrigida: Auto 2 apresentou captura zerada e `CANREC=0` nesta montagem.

**Ajustes e escuta confirmados anteriormente:** na rota do PC, mestre 0,15, sub +3 dB, metade dos graves da central enviados ao sub e atrasos FL/FR 76,8 ms, FC 5,8 ms, SL/SR 71 ms e LFE 5,8 ms. O usuário confirmou áudio limpo e caixas ativas nessa rota. Não copiar automaticamente esse equilíbrio físico para o perfil do A34.

**Próxima validação física:** testar AC-3 estéreo com upmix e AC-3 5.1 discreto no A34, com amplificadores em nível seguro, verificar que nenhuma fala/canal sai trocado ou clipa e exercitar a correção de sala no A55 sem aplicar sugestões frágeis. A compatibilidade do hub PD e a rota PCM óptica seguem pendentes. Fire TV/Bravia e sincronização do volume do A34 com o volume da Fire TV seguem no plano vinculado. Capturas, journals, endereços locais e credenciais permanecem fora do Git.

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

- A34 (`SM-A346M`): no momento da atualização, a versão instalada observada por ADB ainda é DSP 0.9.2 (`versionCode 17`); processo ativo. O APK 0.10.0 foi compilado e publicado, sem interromper essa sessão.
- A55 (`SM-A556E`): controle 0.1.0 instalado e processo ativo. O APK 0.2.0 com o assistente de correção de sala foi compilado e publicado; não foi instalado para preservar a sessão atual de controle.
- A rota óptica completa está implementada. Em OTG direto houve janelas contínuas de AC-3 estéreo e 5.1. A observação também registrou reposições de fila, dois CRC inválidos descartados e descontinuidades; sessões longas e mudanças de formato ainda precisam ser aprovadas.
- OTG direto é o caminho comprovado. O hub PD falhou no duplex mesmo com o A34 carregando; causa entre hub, cabo e negociação ainda não isolada. O usuário não tem outro cabo USB.
- A Bravia foi usada em Sistema de áudio / Auto 1 / Dolby Digital Plus Out Não; a TV entregou AC-3 48 kHz. E-AC-3 direto e PCM óptico não são entradas aprovadas pelo parser atual.

O DSP 0.10.0 agora faz upmix de AC-3 mono/estéreo e mantém AC-3 seis canais em modo nativo. No caminho 5.1 discreto, a matriz de upmix não roda e a cópia CEN → FL/FR não se aplica ao canal central sintético do estéreo; isso evita duplicar o conteúdo das frontais. A alteração ainda precisa ser ouvida após instalar o APK. A revisão de software não substitui teste acústico da ordem física das caixas.

## Próximas etapas

1. Instalar 0.10.0 no A34 e 0.2.0 no A55 em janela autorizada; conferir perfil, atrasos, mute e sessão antes de reproduzir.
2. Com amplificadores em nível seguro, validar AC-3 estéreo do Spotify em todas as caixas e confirmar que os níveis permanecem limpos; em seguida, testar um trecho AC-3 5.1 conhecido e confirmar separação nativa sem upmix.
3. No A55, testar permissão, gravação UNPROCESSED, isolamento de uma saída por vez, interrupções e uma medição curta antes de coletar três posições e considerar cortes. Microfone do telefone não calibrado: não assumir nível acústico absoluto.
4. Medir a continuidade do A34 em AC-3 estéreo/5.1, trocas de formato, pausa/seek e retomada; investigar fila, CRC/descontinuidades e mute/parada. Não declarar estabilidade prolongada com base em janela curta.
5. Retomar a comparação do hub/PD quando houver conexão física confiável. Manter OTG direto enquanto isso.
6. Manter a Bravia em Auto 1 / DD+ Out Não por enquanto; Auto 2 produziu captura zerada e `CANREC=0` na CM6206 nesta bancada. A TV não leva volume pela óptica; seguir o [plano de controle de volume da Fire TV pelo A34](FIRE-TV-CONTROLE-DE-VOLUME-A34.md) para escolher e testar uma ponte IR/CEC viável.

O [relatório 0.9.1 e controle A55](A34-CONTROLE-A55-E-REVISAO-2026-10-10.md) contém as medições detalhadas; o [pipeline óptico](FIRE-TV-A34-PIPELINE-CONTINUO-2026-10-09.md) descreve a implementação e os limites do decoder. Capturas, journals, IPs, tokens e APKs ficam locais/ignorados. Não iniciar dois gerenciadores para a mesma CM6206.
