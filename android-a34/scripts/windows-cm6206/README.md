# CM6206: descoberta de formatos no Windows

Execute `probe-formats.ps1` no PowerShell do Windows. Ele usa CoreAudio/WASAPI e o driver já instalado para listar os endpoints ativos e consultar os formatos da placa `VID_0D8C&PID_0102`.

O probe consulta `GetMixFormat`, `GetDevicePeriod` e `IsFormatSupported` para PCM de 16 bits a 48 kHz, com 2, 6 e 8 canais, em modo compartilhado e exclusivo. Para 5.1, consulta as máscaras `0x3F` e `0x60F`; para oito slots, `0x63F`. Também consulta WAVEFORMATEX sem máscara. Os resultados incluem HRESULT, formato mais próximo e IDs exatos dos endpoints.

Não inicializa streams, não toca nem grava áudio e não muda o dispositivo padrão. Um resultado `supported_exactly` comprova somente a aceitação do formato pela API e pelo driver. A quantidade de saídas físicas, sua ordem, o conteúdo da captura óptica e a latência precisam de testes separados.

O JSON padrão é salvo em `android-a34/artifacts/hardware-2026-10-08/windows-audio/coreaudio-formats.json`. É possível escolher outra pasta com `-OutputDirectory`.

## Teste silencioso de duplex

Após a descoberta, execute `test-silent-duplex.ps1` em um novo processo PowerShell STA. Ele seleciona somente os endpoints já identificados da CM6206: entrada SPDIF estéreo e saída de seis canais com máscara `0x60F`, ambas PCM16 a 48 kHz em modo exclusivo. Inicializa e mantém os dois streams por três segundos; a saída recebe somente amostras iguais a zero e os dados de captura são descartados. Não altera volumes, mix ou dispositivo padrão e não realiza operações HID.

O JSON `silent-duplex.json` registra HRESULTs de Initialize/Start/Stop, frames e pacotes da API e medidas de tempo do software. Esses dados não comprovam conteúdo óptico, saídas físicas, ordem dos canais ou latência de ponta a ponta. A falha de modo exclusivo é registrada sem recorrer a outro dispositivo ou ao modo compartilhado.
