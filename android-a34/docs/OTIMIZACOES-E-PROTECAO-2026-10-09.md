# Versão 0.3.0 — desempenho, interface e proteção dos atrasos

Atualização realizada na sessão de 08–09/10/2026. APK instalado no A34, com a CM6206 ligada por OTG e manutenção por ADB Wi-Fi. SHA256 do APK final: `08BE9E3041EE6DA1EE46BE83B9F1D17EB0980DEFA3EECB24E412FD28DB24A91D`.

## Mudanças de execução

- A tela lê um snapshot em memória. Enumeração USB, capacidades e chamadas ao serviço ficam no monitor em segundo plano, com topologia atualizada por evento ou a cada três segundos.
- O snapshot evita serializar e analisar novamente toda a árvore JSON. A telemetria aninhada é publicada como leitura; o perfil editável continua sendo copiado e validado separadamente.
- Ajustes de perfil usam persistência assíncrona; não há `commit()` em cada gesto do slider na thread principal. Perfis idênticos não são recompilados nem regravados.
- Criação e encerramento de AudioRecord/AudioTrack ficam em uma fila de controle. A parada bloqueia imediatamente novos dados e libera recursos fora da tela.
- Leitura/escrita não bloqueantes têm prazo de 1,5 segundo e espera curta, com buffers reutilizados. Mudança/perda de rota USB interrompe a sessão.
- Início, execução e parada são estados distintos. Cancelamento invalida pedidos antigos para evitar que uma sessão volte a iniciar depois de parar.
- Bancada de arquivos não compete com áudio ativo; callbacks não alteram uma Activity já encerrada. Mudanças de perfil ao vivo são aplicadas fora da thread da tela.

## Mudanças da interface

As três abas mantêm seus campos e posição de rolagem. Volume/mute/bypass permanecem sincronizados entre painel e perfil. O painel apresenta conexão USB e capacidades de entrada/saída; JSON e relatórios técnicos ficam atrás de expansão. Consulta de codecs e documentos ocorre em worker. Os rótulos de desempenho indicam custo do DSP em relação ao bloco, sem chamá-lo de uso total de CPU.

## Proteção dos atrasos

Os seis sliders e seus valores numéricos começam desativados. **Editar atrasos** abre uma confirmação; somente **Desbloquear edição** libera os controles. **Bloquear atrasos**, sair da aba Perfil, deixar o app em segundo plano ou recriar a Activity restaura o bloqueio. O estado desbloqueado não é salvo entre sessões.

A proteção foi exercitada na tela real do A34: gesto horizontal no slider desativado e toque no valor não alteraram a calibração. Confirmação liberou a edição; troca de aba e ida ao launcher bloquearam novamente. Os valores finais continuaram `3686 / 3686 / 278 / 0 / 3408 / 3408` amostras. Trims e outros controles continuam editáveis. Importar/restaurar perfil são operações deliberadas separadas do gesto acidental no slider.

## Evidências

| Teste | Resultado |
| --- | --- |
| Build debug, JUnit e lint | Passaram; lint sem erros, 17 avisos. |
| Serviço real USB, perfil temporariamente silenciado | 20.055 ms; 945.600 frames capturados e escritos; zero underruns; perfil anterior restaurado. |
| Consulta de estado na thread principal | Média 1,73 ms, máximo 3,87 ms; na versão intermediária com cópia profunda de JSON, média 37,07 ms. |
| Chamada de parada | Retorno ao chamador em 0,74 ms; encerramento concluído em 72,14 ms nesta execução. |
| Interface real | Painel e diálogo de proteção inspecionados; bloqueio, confirmação e relock exercitados por toque. |
| Parser experimental de transporte | Seis casos sintéticos passaram; não houve captura óptica nesta sessão. |

A melhoria de tempo acima corresponde à consulta de estado, não a todo o desenho da tela. O teste de 20 segundos é uma verificação curta, sem fonte óptica ou amplificadores, com saída zero; não garante ausência de falhas sob qualquer carga nem estabilidade prolongada. A captura recebida não foi identificada como óptica. AC-3 íntegro, mapa dos conectores, latência acústica e controle de deriva permanecem pendentes.

Logs e capturas locais estão em `artifacts/hardware-2026-10-08/` e `artifacts/qa/`, ignorados pelo Git. O pacote oficial de driver Windows foi baixado e inspecionado, mas não instalado: o driver Microsoft e o suporte USB do Android já permitiram os testes PCM.
