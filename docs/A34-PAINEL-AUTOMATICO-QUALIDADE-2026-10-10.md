# A34 0.8.4/0.8.5 — painel, Dolby automático e qualidade

Histórico destas revisões. O resultado posterior, recuperação corrigida, app no A55 e teste final da 0.9.1 estão em [controle no A55 e revisão de áudio/interface](A34-CONTROLE-A55-E-REVISAO-2026-10-10.md).

Solicitação de 10/10/2026: volume mestre 0–100 fora das configurações; seis níveis por canal dentro da engrenagem; início automático com Dolby; EQ geral e revisão de qualidade/latência.

## Comportamento

- Painel LAN: mestre 0–100 com curva cúbica (`gain=(volume/100)^3`); 0 é silêncio, 50 equivale a ganho 0,125 e 100 a ganho 1. A conversão do perfil salvo para o slider não altera o áudio.
- Engrenagem abaixo do painel: volumes FL, FR, central, LFE, SL, SR; EQ geral de dez bandas de 31,5 Hz a 16 kHz; depois controles avançados. Trims mantêm a faixa −60 a +12 dB. EQ conserva −12 a +6 dB e margem automática.
- Sliders enviam ajustes durante o gesto, limitados a uma atualização a cada 100 ms, e confirmam o valor final ao soltar. Ajustes recebidos durante requisição lenta são agrupados e aplicados posteriormente. Atualizações de estado não recriam os controles durante o gesto.
- A34 local também usa mestre 0–100; aba `⚙ Ajustes` reúne os volumes individuais.
- Automático ativo por padrão nesta versão. Com app/controle local em execução e CM6206 autorizada, abre a captura óptica e mantém a saída silenciosa até validar dois quadros AC-3 consecutivos. Perda de sinal silencia; retorno válido retoma. Codec suportado nesta cadeia é AC-3 a 48 kHz em mono, estéreo ou 5.1, convertido pela Bravia na configuração previamente validada; não implica decoder E-AC-3 direto.
- A revisão 0.8.5 pausa o automático após três tentativas sem estabilidade, com espera progressiva entre elas. Dez segundos de sinal contínuo restabelecem a contagem. Nunca muda volume, mute ou atrasos. `Parar áudio` pausa o automático de forma persistente; `Iniciar Fire TV` ou reativar a opção desfaz essa pausa. Desmarcar automático impede novas tentativas, preservando uma sessão já iniciada.
- Reconectar CM6206 pode abrir o app pelo filtro USB do Android, depois da seleção/autorização do usuário. Não há implementação de boot automático ou evasão das restrições de segundo plano do Android.

## Qualidade e continuidade

- Correção de relógio substitui interpolação linear por cúbica de quatro pontos. Lookahead fracionário máximo de duas amostras (0,042 ms), com histórico preservado entre blocos; a razão 1 mantém as amostras exatas. O teste de 12 kHz com correção de 1000 ppm verifica ganho RMS acima de 0,9, além de continuidade entre blocos e estabilidade de fila em ±350 ppm por dez minutos simulados.
- Volume/trim mudam com rampa de 10 ms; mute permanece imediato. EQ muda coeficientes ao longo de 10 ms e preserva o histórico dos atrasos calibrados. Reset explícito continua limpando todos os filtros, incluindo EQ geral.
- Estatística de fila no caminho de áudio usa vetor primitivo reutilizado, evitando criar/serializar JSON a cada quadro AC-3. JSON de diagnóstico continua limitado a 500 ms.
- Cálculo de margem do EQ reutiliza seno/cosseno por frequência. Percentual de processamento passa a abranger decoder, compilação eventual de perfil, DSP e resampling, além da conversão PCM.

## Latência: limites e próximos ensaios

Mantidos os buffers USB que já tiveram reprodução contínua: quadro AC-3 de 1536 amostras/32 ms, priming de 3072 amostras/64 ms e URBs submetidas cobrindo 16 ms. Reduzir esses buffers sem medir poderia agravar a falha de continuidade observada na telemetria antes da atualização.

Diagnóstico expõe `decoderFrameMs`, `softwareQueueMs`, `usbSubmittedMs`, mínimo/máximo dos atrasos calibrados e estimativa composta. Essa estimativa usa um quadro inteiro como orçamento do decoder e não mede latência acústica, Fire TV, HDMI/TV, conversores ou amplificadores. Os atrasos FL/FR de 76,8 ms e surrounds de 71 ms são calibração preservada, não devem ser apagados para anunciar menor latência.

Próximo ensaio físico: registrar fila, tempo de processamento, erros, CRC e underruns em conteúdo real; depois medir sincronismo A/V ou loopback antes de reduzir priming. EQ subjetivo depende das caixas/sala e deve ser calibrado por escuta ou medição; a atualização não impõe reforço de graves.

Referências consultadas: [Android: áudio de baixa latência e trabalho no callback](https://developer.android.com/games/sdk/oboe/low-latency-audio); [Julius O. Smith: resposta da interpolação linear](https://www.dsprelated.com/freebooks/pasp/Linear_Interpolation_Frequency_Response.html); [Android: restrições de início de serviços](https://developer.android.com/develop/background-work/services/fgs/restrictions-bg-start). A rota atual usa USBFS direto; a documentação Oboe informa os princípios de alocações/buffers, sem afirmar que o app utiliza Oboe.

## Validação

Testes JVM do DSP, rampa de ganho, preservação dos atrasos durante EQ, interpolação cúbica, relógios e política de início automático; teste Node do painel (escala do mestre, seis canais, escrita concorrente, PIN, mute); compilação e lint Android. A validação física da 0.8.4 será registrada depois da instalação; não confundir testes simulados com melhora acústica medida.

### Falha física encontrada durante a validação

0.8.4 iniciou por conta própria e recebeu AC-3 estéreo, mas o transporte interrompeu após aproximadamente um segundo. O usuário relatou volume oscilando e tons anormais. Mute e automático desligados pelo controle; mestre foi posteriormente reduzido pelo próprio usuário a 15 e depois 5 na escala nova, sem restaurar o ganho anterior.

O diagnóstico final da 0.8.5 confirma falhas de transporte com errno 110 (timeout) e 16 (ocupado), sem erros de CRC/packets, clipping ou correção de relógio ativa. A restauração do seletor também falhou. A placa foi desconectada/reconectada e autorizada pelo usuário. Ainda houve falha em teste mantido no mute. Não afirmar áudio normal nem automático fisicamente aprovado.

Corrigida contenção: a cópia JNI dos buffers deixa de segurar o mutex usado pelo pump USB. Estatísticas finais passaram a ser publicadas depois da interrupção, incluindo errno e restauração; a tela anterior mostrava apenas o snapshot precedente. A revisão de transporte em andamento também duplica o descritor autorizado para possuir sua duração e identifica a fase do erro. Esses ajustes precisam de nova sessão de captura silenciosa para comprovar o resultado.

### Última revisão testada

Capture IN: quatro URBs de 16 pacotes/16 ms (64 ms submetidos); OUT permanece quatro URBs de quatro pacotes/4 ms (16 ms submetidos). A mudança reduz reenvios IN e foi verificada no selftest nativo de alocação/FIFO, máscara 31, executado no A34 sem USB. A captura em lotes de 16 ms pode acrescentar espera de agregação e não é anunciada como redução da latência física.

O monitor da fachada deixa de abrir conexões de descritor/consultar capacidades AudioManager enquanto a sessão exclusiva está ocupada. A reserva usa `USBDEVFS_DISCONNECT_CLAIM` atômico e limitado ao driver `snd-usb-audio`, sem forçar a retirada de outro cliente USBFS. O instante do erro passa a registrar o driver proprietário da entrada. A sessão ainda falhou: `submit input`, errno 16, e `input watchdog`, errno 110; driver `snd-usb-audio` já presente no momento da falha. Nenhuma correção de relógio havia entrado em ação.

Roteamento automático USB do Android foi testado desativado, reconectando a placa, sem melhora; valor original 0 restaurado. Reinicializar o A34 também não resolveu os ensaios pelo hub. Não afirmar causa definitiva entre ROM, driver, host USB e hardware somente por errno; a retomada do driver do kernel é um fato observado, não identificação do agente que provocou a perda da reserva.

### Isolamento do hub e da alimentação

O usuário confirmou uso de hub PD durante as falhas. Testes nativos de captura mais saída de zeros reproduziram a interrupção sem decoder, DSP ou áudio audível. O alternate de oito canais, confirmado pelos descritores da própria unidade, também falhou. Esperar 1,5 s depois da reserva do controle não resolveu.

Captura Android isolada trouxe 92 quadros AC-3 completos, todos com CRC válido. Captura Android com saída AudioTrack silenciosa simultânea produziu zero CRCs válidos entre 238 candidatos; captura nativa com AudioTrack também apresentou perdas. Essas alternativas não foram promovidas à produção.

Ao trocar apenas a conexão para o OTG direto, mantendo os amplificadores desligados, o duplex nativo silencioso passou por 3 s e depois 10 s. No ensaio de 10 s: 9.984 pacotes IN, 9.984 pacotes OUT, nenhum erro de pacote, errno 0, interfaces liberadas; 311/311 quadros AC-3 completos com CRC válido, espaçamento de 6.144 bytes, estéreo a 48 kHz. Isso isola uma diferença entre o caminho do hub e o OTG direto; não comprova ainda se a causa é ausência de alimentação PD, o hub, seus cabos ou a negociação USB.

O usuário levantou a possibilidade de o PD ter ficado sem energia, pois antes funcionava alimentado. Próximo ensaio: repetir o mesmo duplex silencioso pelo hub com carregador na tomada e na entrada PD, verificando carregamento no A34. Depois validar a cadeia contínua decoder/DSP/USB no mute. O resultado do OTG é integridade de captura e transporte silencioso, sem aprovação acústica ou de latência física. Mute ativo, mestre 5/100 e automático desligado permanecem preservados.
