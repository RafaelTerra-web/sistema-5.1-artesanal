# Viabilidade inicial: delay no firmware do UD851B

Pesquisa realizada em 02/10/2026. Objetivo: acrescentar aproximadamente 70 ms a FL, FR, SL e SR, preservando o caminho atual de CEN/SW e o amplificador YS-S350H. Orçamento de hardware: até R$ 320.

## Conclusão

A alteração é tecnicamente plausível e há um pacote original disponível para análise. Ainda não foi demonstrado que este firmware permita delay independente nos quatro canais. O pacote não contém código-fonte nem um projeto pronto para recompilar.

A análise inicial não identifica uma configuração pronta que resolva o problema. A presença de funções e mensagens com a palavra `delay` não comprova um atraso puro por canal, sua faixa de ajuste ou sua aplicação ao áudio recebido por HDMI.

## Pacote obtido

- Página da marca: https://atnedcvh-online.com/FAQ_Download/Download/
- Entrada: UD851B, firmware 2022.4.8, v1.0; data de publicação indicada: 01/04/2023.
- URL direta: https://atnedcvh-online.com/uploads/soft/230401/2-230401111923.zip
- Cópia local: `ud851b-original-2022-04-08.zip`.
- Tamanho do ZIP: 5.100.842 bytes.
- SHA-256 da cópia recebida: `2a5e11cd7c94344b570321cefa93637b2805c0da5848572fed7da874486ac91f`.

Conteúdo:

| Arquivo | Tamanho | Observação |
| --- | ---: | --- |
| `uImage` | 2.272.232 bytes | Imagem de kernel; cabeçalho identifica Linux 3.0.8. |
| `rootfs.sfs` | 2.834.432 bytes | Sistema de arquivos SquashFS 4, comprimido com XZ. |
| `UD851B How to Upgrade.txt` | 359 bytes | Instruções de instalação por pendrive. |

O texto de atualização especifica pendrive FAT32 e os dois arquivos `uImage` e `rootfs.sfs`. Isso documenta o mecanismo previsto para a atualização original; não valida uma imagem modificada, a revisão da unidade do usuário ou um método de recuperação.

## Achados na análise estática

- O kernel descomprimido identifica `CSKY TOOLCHAINS V2.5.01`, ABI 1, e a placa `C-SKY SILAN_DLNA`. O código de arquitetura 19 do cabeçalho não deve ser interpretado isoladamente: a numeração depende desta implementação do fabricante.
- Há um driver denominado `silan-dsp` e um firmware separado de DSP, `/lib/firmware/dsp_firmware.bin`, com 908.744 bytes.
- A biblioteca `/usr/lib/libsladsp_new.so` contém a função `swa_audio_delay_start` e interfaces de buffers de áudio. Nome e existência não estabelecem que ela implemente delay contínuo por canal.
- O firmware do DSP contém mensagens referentes a `dsp_dma_play(delay: %d ms)`, `eho_set_delay()` e `mix_set_rdelay()`. Há também referências a echo, reverb e surround. Esses efeitos não foram confirmados como um caminho de atraso puro aplicável aos quatro canais desejados.
- A configuração `/etc/db/Slmp.xml` inclui seis canais, 16 bits e 44.100 Hz. Não foi encontrado um campo explícito de delay por canal nos arquivos de configuração examinados. Esses valores de configuração não provam o formato usado em todos os modos de entrada.
- O kernel contém uma linha de comando padrão com `mem=28M`. Isso não determina a RAM física total, a RAM livre em execução ou a memória acessível ao DSP.
- Um repositório chamado `zoubochang/silan-sdk` foi encontrado, mas estava vazio. Não constitui um SDK disponível: https://github.com/zoubochang/silan-sdk

Arquivos de apoio locais: `ud851b-firmware-original/`, `ud851b-rootfs/`, `ud851b-rootfs-list.json`, `ud851b-strings/`, `ud851b-kernel-decompressed.bin` e `ud851b-kernel.config`.

## O que a implementação precisaria fazer

Aplicar o atraso depois da decodificação e antes da saída analógica, de forma seletiva:

| Canal | Atraso adicional pretendido |
| --- | ---: |
| FL / FR / SL / SR | Aproximadamente 70 ms |
| CEN / SW | 0 ms |

Um atraso global mantém a diferença relativa entre os canais. A duração correta deve ser calibrada para o sistema real; os 70 ms continuam sendo a estimativa informada pelo usuário.

A 48.000 Hz, 70 ms equivalem a 3.360 amostras por canal. Para quatro canais, o armazenamento seria 26.880 bytes com 16 bits, 40.320 bytes com 24 bits compactados ou 53.760 bytes com palavras de 32 bits. Isso não inclui a memória já usada pela decodificação, pelos buffers e pelos demais efeitos.

## Dados ainda necessários

1. Fotos legíveis da unidade real, mostrando processador, DSP, memórias e revisão da placa, para verificar correspondência com o pacote.
2. Documentação ou análise das interfaces do DSP para determinar se algum recurso existente oferece os quatro delays necessários.
3. Se não houver recurso existente, acesso ao PCM e ferramentas compatíveis para alterar o processamento, sem introduzir mistura de canais ou interrupções.
4. Identificação do formato de atualização, eventuais verificações e um método de recuperação antes de instalar firmware experimental.

## Custos e limite prático

A pesquisa e a análise local não exigiram compra de componentes. Se o recurso puder ser ativado em software, o custo adicional de peças pode ser R$ 0, supondo pendrive disponível. Ainda não há orçamento fechado de implementação: instrumentos de programação ou recuperação, componentes e mão de obra podem ser necessários e não foram cotados.

Nenhum firmware foi instalado no aparelho. Não foi produzido um firmware modificado para uso.
