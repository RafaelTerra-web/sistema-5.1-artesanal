# Validação do APK 0.1.0 no A34 — 08/10/2026

APK final: `artifacts/sistema51-a34-0.1.0-debug.apk`, SHA256 `DF57494A459A657065634F71A695E0764E7338FB34FB03B638DB49D36DCFAE73`.

O pacote `br.com.sistema51.a34` foi instalado e atualizado por ADB no SM-A346M, Android 14 / API 34, One UI 6.1. O APK usa minSdk 28, targetSdk 36 e assinatura debug local. Os testes instrumentados executaram com UID próprio `10355`, usando as mesmas classes de DSP/IO da aplicação. Resultados brutos permanecem em `artifacts/device/` e não entram no Git.

| Verificação | Evidência |
| --- | --- |
| Gradle debug + JUnit + lint | Passaram; lint com 0 erros, 16 avisos. |
| Núcleo JVM | 11 cenários independentes, todos passaram. |
| Aplicativo: seis delays, silêncio, WAV, perfil | Autoteste exato passou; persistência restaurada após o teste. |
| Validação de entrada | Perfil inválido e WAV truncado foram recusados. Máscaras e layouts suportados têm validação explícita no IO. |
| AC-3 pelo codec Samsung | Saída 6 canais / 48 kHz, 431.616 frames para 282 quadros; diferença de −1.536 frames registrada. Fidelidade continua experimental. |
| DSP completo sobre o PCM do codec | 435.302 frames incluindo cauda de 3.686; valores finitos e nenhum clipping no perfil padrão. |
| Mesmo PCM → mpv | Todos os 435.302 frames comparados. FL/FR/central iguais; LFE erro pico `1,46e-11`; SL `1,86e-9`; SR `3,73e-9`. SNR SL/SR >147 dB. |
| Iniciar sem interface | Recusado, estado aguardando USB; não iniciou captura/reprodução. |
| Assinatura | Verificação apksigner passou, assinatura v2 válida. |

A comparação numérica acima isola o DSP da diferença de ganho do codec Samsung, usando exatamente o PCM decodificado pelo aplicativo como entrada da referência. Ela comprova processamento de arquivo no UID do aplicativo, não transporte óptico, saída física, estabilidade contínua ou latência ao vivo.

A abertura da Activity foi confirmada e o processo ficou ativo sem crash observado. A conferência visual por tela/toque ficou pendente porque o telefone permaneceu bloqueado durante essa etapa. Não há captura de tela da aplicação nesta validação.

Os critérios físicos restantes estão em [USB e validação](USB-E-VALIDACAO.md). A CM6206 e o hub ainda não estavam disponíveis. O workflow Android foi adicionado ao projeto, mas não foi executado no GitHub nesta sessão.
