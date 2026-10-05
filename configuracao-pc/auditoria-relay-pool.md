# Auditoria de buffers e medição do relay

Data: 03/10/2026. Alteração preparada sem reiniciar a rota ativa nem reproduzir áudio.

## Alteração

- `RelayLoopbackLowLatency.cs` reutiliza 16 pacotes com buffers de 11.520 bytes, em vez de criar um array para cada bloco capturado. O cache de arrays ocupa 184.320 bytes (180 KiB), além dos objetos e da fila.
- Cada pacote tem `Data` e `Count`. Captura, upmix, medição e escrita usam somente `Count`, inclusive nos blocos de menos de 480 frames. O silêncio limpa somente o trecho válido.
- A captura entrega a propriedade do pacote à fila; o escritor devolve em `finally`. Trim da fila, cancelamento e falhas devolvem os pacotes pendentes. Pacotes já em processamento voltam pelo escritor.
- O cache tem capacidade fixa de 16. Se houver mais de 16 pacotes simultaneamente, a captura usa uma alocação emergencial para continuar sem esperar pelo pipe; `poolAllocations` torna esse caso visível. Ao devolver, o cache continua limitado a 16.
- Formato de seis canais/48 kHz/float32, blocos de até 10 ms, fila máxima de 80 ms, trim para 60 ms, modo de upmix e correção entre caixas permanecem iguais.

Backup: `RelayLoopbackLowLatency.cs.pool-backup-20261003-023148`.

## Novas métricas

- `poolAllocations`: quantidade de arrays de pacote criados; começa em 16. Crescimento indica uso do fallback. Não representa todas as alocações do processo.
- `inFlightFrames`: frames do pacote em processamento/escrita, incluindo silêncio de manutenção. Volta a zero em caso de sucesso ou exceção. Complementa `queueMs`, que só conta a fila.
- `peakAbs`: pico absoluto por canal desde o início da execução, após o upmix e antes do pipe para o codificador.
- `overOneSamples`: quantidade acumulada de amostras com magnitude maior que 1 por canal. Detecta falta de margem no PCM sem aplicar ganho, limiter ou EQ. O valor não prova, sozinho, que houve distorção audível.
- `meterMsPer10ms`: custo médio da observação normalizado para blocos de 480 frames. `maxMeterMs` guarda o maior custo de um bloco observado.

As métricas de pico e custo usam arrays reutilizados e contadores atômicos; a medição só lê o sinal.

## Verificação offline

Comando executado no Windows PowerShell 5.1/.NET Framework:

```powershell
powershell.exe -NoProfile -NonInteractive -File .\configuracao-pc\testar-relay-pool.ps1
```

Os três arquivos C# do relay e o teste compilaram. Passaram:

1. Esvaziamento do pool com 17 rentals e retorno para capacidade 16; contador de alocações igual a 17.
2. 100.000 ciclos de aluguel/devolução sem criar arrays de pacote adicionais.
3. Nove blocos completos causam o mesmo trim de 90 para 60 ms; três blocos são contados como descartados e devolvidos.
4. Cancelamento esvazia a fila e devolve também um pacote entregue pela captura após o cancelamento.
5. Bloco parcial de três frames escreve e mede somente 72 bytes; a cauda com dados antigos não é enviada.
6. Cancelamento durante escrita bloqueada deixa apenas o pacote do escritor fora do pool; depois da liberação, todos os 16 retornam e `inFlightFrames` volta a zero.
7. Exceção sintética de escrita devolve pacote corrente e fila, limpa as métricas de escrita e não conta frames como enviados.
8. Picos e contagens acima de 1 corretos para os seis canais; bytes permanecem idênticos após a medição.

Benchmark local de 20.000 blocos, após aquecimento: medição média **0,004936 ms por bloco de 10 ms**, maior observação **0,053100 ms**. Esses valores medem cópia/leitura dos picos e contadores; não incluem latência física nem garantem desempenho sob toda carga do PC.

O teste não abriu endpoint de áudio ou processo de reprodução. Estabilidade com a rota real e eventuais ganhos na latência precisam ser verificados pelo agente responsável após reiniciar o relay com o código novo.
