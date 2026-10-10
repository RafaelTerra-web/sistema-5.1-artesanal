# A34 0.10.0 / controle A55 0.2.0 — upmix e correção de sala

## O que muda

- O DSP faz upmix de fontes AC-3 mono ou estéreo, como a música estéreo da Fire TV quando chega à Bravia como AC-3 de dois canais. O processo preserva o áudio FL/FR e gera central, surrounds e LFE segundo os ganhos do perfil.
- AC-3 5.1 continua discreto: os seis canais decodificados seguem diretamente para o DSP sem upmix. A cópia ajustável CEN → FL/FR aplica-se apenas à entrada 5.1 com central real, não ao centro sintético criado no upmix estéreo; isso evita duplicação e aumento indevido das frontais.
- O controle do A55 inclui a medição assistida de sala. A interface pede gravação pelo microfone interno, envia a captura à sessão local do A34 e apresenta cortes paramétricos conservadores para revisão antes de aplicar.
- Os sliders de ganho em dB no A34 e no painel remoto avançam em passos de **0,25 dB** e exibem duas casas decimais; valores digitados na tela do A34 são ajustados ao mesmo passo.

## Rota da Fire TV

Para a rota atual, manter a Bravia em **Sistema de áudio / Auto 1 / Dolby Digital Plus Out Não**, HDMI 2 e TV óptica OUT → CM6206 SPDIF IN. A captura AC-3 já decodificada pelo A34 recebe o upmix quando tem um ou dois canais e permanece nativa quando tem seis.

Auto 2 não é uma alternativa funcional nesta CM6206 ainda: na medição anterior desta bancada, a captura óptica recebeu zeros e `CANREC=0`. Isso não determina a causa de `CANREC=0` nem prova bloqueio por copyright. Até validar uma captura PCM real, não selecionar Auto 2 para uso diário.

## Correção de sala

O A55 grava mono/48 kHz por até 20 segundos. O sinal gerado pelo A34 tem pico digital de aproximadamente −30 dBFS, dura até 13,35 segundos por saída satélite e fica em 8,22 segundos para LFE. O nível acústico final depende dos amplificadores e das caixas; o valor digital não é uma garantia de SPL seguro.

A sugestão requer ao menos três posições distintas da mesma caixa, captura bruta válida e picos de banda estreita repetíveis. Só propõe filtros negativos, até −6 dB e Q 3, em bandas graves com evidência consistente. Não tenta preencher cancelamentos, aumentar bandas, corrigir fase, descobrir distâncias, ajustar atrasos ou calibrar o microfone do A55. Os filtros podem ser ligados/desligados para comparação e a captura temporária é descartada após análise.

## Validação feita

- Build dos APKs A34 e A55 e lint dos dois módulos: concluídos sem erros.
- Testes JVM: 88 no A34 e 3 no controle A55, sem falhas.
- Testes Node do repositório: 73 passaram; painel remoto do A34 também passou no teste específico.
- A lógica de seleção foi testada: AC-3 estéreo ativa o upmix; AC-3 5.1 fica em `NATIVE_5_1`; E-AC-3 direto e layout desconhecido não recebem upmix.
- A medição de sala ainda precisa de validação acústica no A55 e de escuta nas caixas. Os APKs não foram instalados nos telefones nesta publicação porque os dois apps foram encontrados em execução e a instalação os interromperia.

## APKs

- [DSP A34 0.10.0 (APK debug)](https://github.com/RafaelTerra-web/sistema-5.1-artesanal/releases/download/v0.10.0/sistema51-a34-0.10.0-debug.apk) — SHA-256 `58AC0A7E9E0F974125310A0364F42376BDE8AF46468CF605908494DA62D45492`
- [Controle A55 0.2.0 (APK debug)](https://github.com/RafaelTerra-web/sistema-5.1-artesanal/releases/download/v0.10.0/sistema51-controle-0.2.0-debug.apk) — SHA-256 `B21773037D01D4C757F97757BD19293F3C6A1CA85855D800A90FEB19328EC0B7`

O código-fonte e o relatório também estão no repositório. Antes de usar o assistente, instale ambos os APKs; a API de calibração do A55 precisa do DSP A34 0.10.0. Preserve os ganhos físicos atuais ao medir e compare os cortes no mesmo volume.
