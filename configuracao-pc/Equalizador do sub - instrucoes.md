# Equalizador do subwoofer (LFE)

Abra **Equalizador do subwoofer** na area de trabalho ou no menu Iniciar. O painel principal **Sistema de audio 5.1** tambem tem um botao para abrir o equalizador.

## Controles

- **Volume mestre - 6 caixas:** controla FL, FR, central, LFE, SL e SR juntos, de 0 a 100%. **Mudo** silencia as seis caixas e preserva a posicao do volume. Esses dois controles sao aplicados e salvos automaticamente, sem precisar clicar em Aplicar. 100% corresponde ao nivel anterior; 0% e silencio.
- Nove bandas independentes, inicialmente em 20, 25, 30, 40, 50, 60, 80, 100 e 120 Hz.
- **Hz:** escolha a frequencia de cada banda, entre 10 e 200 Hz.
- **Ganho:** controle vertical de -12 a +6 dB, em passos de 0,5 dB.
- **Q:** largura da banda, de 0,3 a 10. Menor Q abrange mais frequencias; maior Q concentra o ajuste.
- **Volume LFE:** atenuacao adicional de 0 a -24 dB somente no subwoofer.
- **Margem automatica:** reduz o nivel de entrada do LFE pela soma dos ganhos positivos, para reduzir o risco de saturacao digital. Os ganhos das bandas sao relativos a esse nivel reduzido; ela tambem pode diminuir o volume percebido do sub. Pode ser desmarcada no painel.
- **Equalizador ligado:** desmarque e clique em Aplicar para retornar ao LFE sem EQ e sem a margem do EQ. O corte das surrounds e a margem da soma tem controles independentes.
- **Aplicar e salvar:** atualiza o sistema ativo e guarda os ajustes para a proxima abertura. Pode ocorrer uma breve interrupcao ao reconstruir os filtros.
- **Zerar bandas:** coloca todos os ganhos em zero; clique em Aplicar para confirmar.
- **30/40 +3 | 60 -2 dB:** restaura frequencias e larguras iniciais, com margem automatica e volume LFE em 0 dB.

O ajuste inicial tem +3 dB em 30 Hz, +3 dB em 40 Hz e -2 dB em 60 Hz. Com margem automatica, o nivel de entrada do LFE e reduzido em 6 dB. Bandas vizinhas se sobrepoem, portanto a resposta combinada nao corresponde exatamente ao ganho isolado de cada controle.

## Processamento e verificacao

Filtros parametricos IIR do FFmpeg, somente no canal LFE, dentro da rota existente e antes do AC-3. `block_size=0`: nenhum buffer de processamento reverso ou lookahead foi adicionado. Equalizacao altera fase e resposta temporal por frequencia; isso nao representa uma nova espera fixa para o fluxo inteiro.

Preservados: compensacao de deriva 48002 -> 48000 Hz, FL/FR 76,8 ms nominais, central 5,8 ms, SL/SR 71 ms, LFE 0 ms, AC-3 640 kbit/s e buffer de 32 ms. A troca entre os perfis de fidelidade e estabilidade preserva os ajustes do painel.

Verificacao offline anterior ao ajuste frontal de 7,7 ms, com entrada PCM float 5.1: os outros cinco canais foram identicos a referencia, o inicio do impulso do LFE ocorreu na mesma amostra e a correcao de 70 ms permaneceu. Resultados em `equalizador-lfe-verificacao.json`. A rota ativa foi confirmada via IPC em `equalizador-lfe-ativo-verificado.json`; a observacao apos aplicar registrou zero quadros descartados e zero preenchimento com silencio no relay.

Estado salvo em `equalizador-lfe.json`. Copia da configuracao anterior em `mpv-sistema-dolby.antes-equalizador.conf`.

O volume mestre atua no PCM depois do EQ, corte e atrasos, antes da codificacao Dolby Digital. Usa um comando de volume em tempo real, sem reconstruir o grafo de filtros nem adicionar buffers. Os perfis de qualidade e as proximas aberturas preservam o volume e Mudo. Verificacao offline: 50% reduziu igualmente a amplitude nos seis canais; Mudo zerou todas as amostras, sem mudar o tamanho do fluxo.

## Graves das surrounds enviados ao sub

O painel tambem oferece **Enviar graves de SL/SR para o sub**, independente da chave do equalizador. Comeca em **80 Hz**, com corte ajustavel entre **40 e 120 Hz** e inclinacao de **24 dB/oitava** (crossover Linkwitz-Riley de quarta ordem).

As surrounds recebem a parte alta; a parte baixa de cada uma e somada ao LFE original. O envio por surround comeca em **-6 dB** e pode ser ajustado entre **-24 e 0 dB**. Quando o EQ estiver ligado, ele afeta tanto o LFE original quanto esses graves redirecionados.

**Margem de volume da soma** reduz o nivel do LFE combinado para acomodar as fontes somadas. Com os envios atuais em 0 dB, LFE, duas surrounds e central podem chegar a quatro fontes; a margem teorica e de aproximadamente **12 dB**. O volume percebido do sub pode diminuir. A margem do EQ e independente; quando ativada, sua atenuacao se soma a esta.

O redirecionamento ocorre antes dos atrasos: o grave enviado segue a correcao do sub, sem carregar os 71 ms das surrounds; a parte alta permanece nos canais SL/SR com 71 ms. O crossover IIR modifica a fase perto do corte, sem adicionar lookahead ou alterar os buffers existentes.

Verificacao offline em `surround-graves-verificacao.json`: 30 Hz caiu aproximadamente 34 dB nas surrounds e chegou ao LFE; 300 Hz permaneceu nas surrounds (variacao inferior a 0,05 dB). SL/SR permaneceram separados na parte alta. FL, FR e central foram identicos a referencia; o LFE original foi preservado com a margem de volume indicada. A cadeia completa gerou Dolby Digital. Referencia do filtro: [FFmpeg acrossover](https://ffmpeg.org/ffmpeg-filters.html#acrossover).

## Grave da central somado ao sub

Uma copia do sinal da central passa por dois filtros passa-baixas causais de dois polos a **120 Hz** e e somada ao LFE. A central original segue com a faixa completa e recebe somente seu atraso configurado de 5,8 ms. A copia grave entra no LFE antes dos atrasos, portanto o canal LFE continua sem atraso fixo. O filtro tem transicao gradual: em 120 Hz a copia esta aproximadamente 6 dB abaixo, e frequencias mais altas continuam sendo atenuadas. O EQ do sub tambem atua nessa copia. O ajuste fica salvo no preset e sobrevive ao painel de EQ, ao volume mestre e a troca de perfil.
