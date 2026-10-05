# A34: orçamento e pesquisa de hardware

Consolidação em **05/10/2026**. [Plano aprovado](A34-DSP.md), [programação/validação](A34-DESENVOLVIMENTO-E-VALIDACAO.md) e [notas complementares](A34-NOTAS-COMPLEMENTARES.md).

Atualização de conhecimento: o manual exato da Sony documenta saída óptica Dolby Digital/DTS e conversão DD+ → DD. A direção mais recente investiga TV → interface → A34 → saídas analógicas, sem UD851B nessa rota. Adaptadores P2 macho → dois RCA fêmeas podem reutilizar cabos existentes compatíveis; ainda não foram cotados. A PC-USB do decoder pode ser testada como alternativa de saída, sem presumir captura ou seis canais por USB.

Os valores abaixo são **referências das consultas anteriores**, em reais. Não constituem nova cotação, confirmação de estoque ou total entregue. Fretes permanecem separados. A CM6206 e o extrator foram cotados com estimativa de impostos; a cobrança efetiva depende da oferta e do checkout. Não foram feitas compras.

## Componentes candidatos

| Item | Valor de referência | Condição |
| --- | ---: | --- |
| CM6206 com óptica IN/OUT e saídas multicanal | R$ 87,13 ou mais | R$ 69,69 + R$ 17,44 de impostos anteriormente estimados; unidade, firmware e captura AC-3 a validar |
| Extrator Navceker ZY-HA201 4K60 | R$ 207,52 ou mais | R$ 165,99 + R$ 41,53 de impostos anteriormente estimados; dispensável se decoder ou TV oferecerem óptica adequada |
| OTG USB-C → USB-A simples | R$ 10,30 | Teste com A34 na bateria; verificar alimentação da interface |
| Hub Ugreen 15596 com USB-A e entrada USB-C PD | R$ 101,64 | Alternativa ao OTG, não compra cumulativa; carregamento e host simultâneos no A34 precisam de teste |
| Segundo cabo óptico Toslink 1 m | R$ 16,35 | Só necessário na montagem com retorno óptico ao decoder |
| Cabos/adaptação das saídas analógicas | A cotar | Três P2 estéreo macho-macho ou três adaptadores P2 macho / 2 RCA fêmeas para cabos existentes compatíveis; conferir conectores e pinagem |
| Carregador PD e cabo compatíveis | Reutilizar ou cotar | Não incluídos nos totais; necessários para tentativa de operação com hub e carga contínua |

O hub não dá entrada HDMI nem saída HDMI nativa ao A34. Sua saída HDMI não é utilizada neste projeto. O A34 não recebe o Fire TV diretamente por um cabo HDMI/USB-C.

### Totais por montagem

| Montagem com o A34 existente | Teste na bateria com OTG | Com hub PD substituindo o OTG |
| --- | ---: | ---: |
| Decoder com óptica OUT → CM6206 → A34 → saídas analógicas → amplificadores | **R$ 97,43** | **R$ 188,77** |
| TV com saída óptica AC-3 → mesma saída analógica | **R$ 97,43** | **R$ 188,77** |
| Extrator externo → CM6206 → A34 → saída analógica | **R$ 304,95** | **R$ 396,29** |
| Extrator externo → CM6206 → A34 → retorno óptico ao UD851B | **R$ 321,30** | **R$ 412,64** |

Acrescentar fretes, eventuais cabos analógicos e carregador/cabo PD. A primeira linha depende de uma porta ainda não confirmada no decoder; a segunda depende do repasse 5.1 da TV; todas dependem da captura da interface. Não interpretar o menor subtotal como sistema garantido.

A opção econômica por saída analógica elimina o extrator de R$ 207,52 e o segundo óptico de R$ 16,35 em relação à última linha: **R$ 223,87 de economia de referência**, antes de adaptar os cabos analógicos. Reutiliza o cabo óptico curto existente.

Se a TV fornecer óptica adequada, também é possível conservar o retorno óptico ao UD851B sem extrator: R$ 113,78 com OTG ou R$ 205,12 com hub, mais os adicionais. Essa variação ainda exige recodificação AC-3 e saída óptica USB bit-perfect, diferente da opção atual com saída analógica.

## A34 versus mini PC J1800

O A34 foi escolhido por já existir e reduzir o custo inicial. O mini PC continua sendo alternativa de equipamento dedicado se a integração Android/USB se mostrar inviável.

| Aspecto | Galaxy A34 | Celeron J1800 / Unisys U7500 |
| --- | --- | --- |
| Custo do processador dedicado | Já disponível | R$ 378,56 (4 GB / SSD 80 GB) ou R$ 398,51 (SSD 120 GB / Wi-Fi), referências anteriores |
| Implementação | APK Android e possível motor USB próprio | Linux leve e captura USB/ALSA com FFmpeg/mpv adaptados |
| Integração | Maior trabalho de USB/Android e carga contínua | Menor trabalho de integração, ainda exigindo comprovar entrada e saída |
| Margem de CPU | Provavelmente maior no A34, inferência sem benchmark do DSP | J1800 tem dois núcleos e duas threads; dimensionar pelo teste real |
| Vídeo do Fire TV | Direto à TV pelo caminho escolhido | Direto à TV; não contar com J1800 para capturar/reproduzir 4K60 |
| Latência | Não medida; depende de buffers, codec e transporte | Também não medida; não deduzir apenas da CPU |

Na montagem anteriormente cotada com **extrator + CM6206 + mini PC**, usando HDMI de retorno ao UD851B, os subtotais eram R$ 673,21 ou R$ 693,16, mais fretes. Não há OTG nem segundo cabo óptico nesse retorno HDMI; precisa de saída HDMI ativa, EDID e passthrough AC-3 funcionando. Usar os cabos HDMI existentes. Reutilizar a óptica de TV/decoder e escolher saída analógica muda esse orçamento e exige teste; não somar peças dispensadas.

PC existente para desenvolvimento: Windows 11, Xeon E5-2680 v4, RTX 5060 Ti 16 GB, 32 GB DDR4 a 2400 MHz. O usuário relatou boa sincronia no programa anterior com mídias do PC, YouTube e Netflix. Isso confirma a experiência dessa rota, não é medição nem garantia do processamento de uma fonte externa no Android.

## HDMI, splitters, captura e proteção

### Splitter comum não substitui extração/captura

Splitter HDMI apenas duplica o sinal HDMI. A CM6206 precisa de uma entrada óptica na proposta; o telefone não tem HDMI IN. Splitter com saída TOSLINK também exerce a função de extrator, e deve ser avaliado por essas especificações.

Manter vídeo 4K60 para a Sony exige caminho HDMI compatível, negociação adequada e conteúdo/fonte disponíveis nesse formato. A TV e o Fire TV devem informar o modo real. Um filme em 4K pode ter 24 fps mesmo quando a saída HDMI está configurada em 60 Hz; não confundir resolução/frequência da conexão com taxa de quadros do conteúdo.

Não assumir que splitters baratos retiram HDCP, nem que separar o áudio elimina as condições de proteção do enlace HDMI. O plano óptico não requer capturar/reencodar o vídeo no A34; mantém o caminho HDMI regular para a TV e exige extração de áudio compatível. A indicação 4K60 em um anúncio não prova compatibilidade com toda a cadeia de streaming.

Dolby Digital Plus/E-AC-3 não deve ser confundido com Dolby Digital/AC-3. Um extrator que anuncia suporte a áudio não necessariamente transcodifica DD+ em DD. Selecionar formato compatível no Fire TV e verificar o sinal realmente recebido.

### Capturadora HDMI → USB barata

A Hagibis UHC07 pesquisada tinha referência de **R$ 173,35**, mas não foi encontrada especificação comprovando captura AC-3/PCM 5.1 pelo USB. O anúncio também divergia entre 1080p30 e 60 fps. Repasse HDMI 5.1 não comprova captura USB 5.1.

O datasheet original MacroSilicon MS2130 descreve configuração padrão com áudio USB estéreo. Essa referência explica a limitação frequente de capturadoras baratas, mas não prova qual chip/revisão está dentro da Hagibis anunciada. USB 3.0, título 4K e atualização de driver não recuperam seis canais que foram reduzidos a dois antes de chegar ao computador.

Hauppauge HD PVR 2 (versões adequadas) e AVerMedia GC553Pro foram pesquisadas como exemplos de captura com suporte multicanal documentado, mas não se tornaram a solução barata escolhida. Captura de HDMI protegido, requisitos, formato USB e latência continuam condições separadas. A pesquisa anterior não encontrou oferta barata comprovada que resolvesse todos esses requisitos.

Hifime UR23 foi uma alternativa documentada para entrada óptica AC-3/DTS intacta no PC. A estimativa anterior de importação era aproximadamente **R$ 802**, tornando-a incompatível com a prioridade de menor custo. A demonstração dessa interface não valida a CM6206.

### PCIe, USB e entrada HDMI em PC

Uma ponte USB comum não oferece um slot PCIe genérico funcional para qualquer placa de captura. Soluções específicas ou Thunderbolt exigem interfaces/controladores compatíveis e não equivalem a um adaptador USB barato. Porta HDMI de placa de vídeo/mini PC normalmente é saída, não entrada. Um computador com HDMI IN precisa documentar captura utilizável por software; apenas aceitar imagem em uma tela integrada não garante esse acesso.

## Qualidade e cabos

Com o mesmo fluxo digital íntegro, óptico e HDMI não diferem automaticamente em qualidade de áudio. A fibra oferece isolamento elétrico e não capta interferência eletromagnética; HDMI pode funcionar perfeitamente junto a outros cabos, mas não fornece esse isolamento. Problemas graves de enlace digital tendem a provocar cortes/perda de sinal, não o mesmo tipo de chiado de um AUX analógico.

O óptico não elimina o trecho analógico depois da CM6206. A qualidade do DAC, módulos amplificadores, fontes e cabos P2/RCA continua relevante. Usar cabos analógicos curtos e afastados das fontes/cabos de alimentação; se precisarem cruzar, preferir cruzamento a 90 graus. Não apertar ou dobrar excessivamente a fibra. Na opção econômica, a CM6206 substitui a conversão analógica do UD851B; não foi feita comparação sonora entre os dois DACs.

## Devolução de importados

Não foi confirmado o selo de devolução gratuita da variante exata da CM6206 ou do ZY-HA201. A política AliExpress depende da oferta participante, pedido, prazo e elegibilidade do frete reverso. Termos consultados para ofertas participantes mencionam até 90 dias contados do pagamento do pedido; conferir o prazo efetivamente exibido no pedido, sem tratá-lo como regra universal ou 90 dias após a entrega. Há condições para gratuidade e quantidade de usos do frete reverso.

Preservar embalagem e acessórios durante inspeção/teste e evitar modificações físicas se houver intenção de devolução. A orientação geral da Senacon é direito de arrependimento nas compras a distância em sete dias após o recebimento, com restituição de valores e fretes; isso não comprova que o fluxo automático de devolução da oferta importada esteja habilitado. Não comprar contando com devolução gratuita sem verificar o pedido.

## Links de referência

Preços podem mudar; as páginas não são garantia de oferta ativa.

- [OTG USB-C — KaBuM](https://www.kabum.com.br/produto/257542/adaptador-otg-tipo-c-3-1)
- [Hub Ugreen 15596 — KaBuM](https://www.kabum.com.br/produto/514482/adaptador-hub-ugreen-5in1-p-usb-c-hdmi-100w-power-delivery?seller_offer_id=2951209)
- [Segundo cabo óptico — Realtek Brasil](https://realtek.com.br/produto/cabo-de-audio-optico-digital-toslink-1-metro-pix-018-9001/)
- [Mini PC Unisys U7500 — referência 4 GB / SSD 80 GB](https://www.mercadolivre.com.br/mini-computador-postech-unisys-u7500-4-gb-ssd/up/MLBU3893677861?wid=MLB6612316402)
- [Mini PC — referência SSD 120 GB / Wi-Fi](https://www.mercadolivre.com.br/mini-pc-desktop-postech-unisys-u7500-4-gb-ssd-120-gb--wifi/up/MLBU3893866799?wid=MLB6612579150)
- [Intel J1800: especificações](https://www.intel.com/content/www/us/en/products/sku/78866/intel-celeron-processor-j1800-1m-cache-up-to-2-58-ghz/specifications.html)
- [Samsung A34: especificações](https://www.samsung.com/uk/business/smartphones/galaxy-a/galaxy-a34-5g-lime-128gb-sm-a346blgaeub/)
- [CM6206: datasheet original C-Media em espelho](https://tehnoblog.org/downloads/cmedia/C-Media_CM-6206.pdf)
- [Hagibis UHC07: fabricante](https://cn.hagibis.com.cn/products/video-capture-card-222)
- [Hagibis: anúncio anteriormente consultado](https://www.mercadolivre.com.br/placa-de-captura-externa-hdmi-usb-30-1080p-60fps-hagibis-cinza/p/MLB51890190)
- [MS2130: datasheet original](https://atta.szlcsc.com/upload/public/pdf/source/20241106/4958344C7DE6B7E146D765C26384299A.pdf)
- [Hauppauge: HD PVR 2 e limites de captura](https://www.hauppauge.com/pages/products/data_hdpvr2.html)
- [AVerMedia: requisitos e formatos de captura multicanal](https://www.avermedia.com/support/faq/can-gc553g2-support-5-1-surround-sound)
- [Hifime: demonstração de captura AC-3/DTS](https://hifimediy.com/2022/12/send-and-receive-5-1-dts-ac3-signal-via-optical-spdif/)
- [AliExpress: termos oficiais consultados para devolução](https://cdn.contract.alibaba.com/terms/c_end_product_protocol/20240529164446731/20240529164446731.html)
- [Senacon: orientação sobre compras a distância e arrependimento](https://www.gov.br/mj/pt-br/assuntos/arquivos-imprensa/senacon/cartilha-black-friday-senacon-2025)

Os anúncios importados específicos de CM6206 e ZY-HA201 não foram reconfirmados nesta publicação. Não vincular uma oferta genérica à promessa de compatibilidade ou a um selo de devolução não verificado.
