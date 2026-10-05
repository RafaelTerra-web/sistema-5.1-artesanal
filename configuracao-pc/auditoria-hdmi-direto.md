# Auditoria da ligação HDMI direta ao UD851B

Data local: 02/10/2026. Consulta somente leitura ao Windows após o PC ser ligado à entrada HDMI direta do UD851B.

## Identificação

- Saída ativa: `SONY TV  *00 (NVIDIA High Definition Audio)`.
- Endpoint Core Audio: `{0.0.0.00000000}.{0AA75AE7-E336-4CD9-8281-33745F0863E0}`; estado `1` (ativo).
- Monitor/EDID: `DISPLAY\SNY4904\5&10A018EC&0&UID12544`; EDID de 256 bytes, uma extensão CTA.
- A conexão anterior apresentava `DISPLAY\SNY4B04` e outro endpoint: trata-se de uma identificação HDMI diferente.

## Formatos anunciados e primeira consulta

O bloco de áudio do EDID contém os descritores `0D-7F-07` (**LPCM 6 canais**), `15-7F-C0` (AC-3 6 canais) e `3D-7F-C0` (DTS 6 canais). A ligação anterior anunciava LPCM de apenas 2 canais.

Consulta a `IAudioClient::IsFormatSupported` no endpoint atual, a 48 kHz, PCM `WAVEFORMATEXTENSIBLE`:

| Canais e máscara | Profundidade | Compartilhado | Exclusivo |
| --- | ---: | --- | --- |
| 2, `0x3` | 16 bits | `S_OK` | `S_OK` |
| 6, `0x3F` (5.1 traseiro) | 16 bits | `S_FALSE`, aproximação 2 canais | **`S_OK`** |
| 6, `0x60F` (5.1 lateral) | 16 bits | `S_FALSE`, aproximação 2 canais | **`S_OK`** |
| 6, `0x3F` ou `0x60F` | 24/32 bits | `S_FALSE`, aproximação 2 canais | `AUDCLNT_E_UNSUPPORTED_FORMAT` (`0x88890008`) |

Na primeira consulta, antes da configuração 5.1, o formato de mistura do Windows era **2 canais, 48 kHz, 32 bits float**; o formato padrão gravado para o dispositivo era **2 canais, 48 kHz, 16 bits PCM**. Isso explica o retorno `S_FALSE` em modo compartilhado. A interpretação dos retornos é a da [documentação Microsoft de `IsFormatSupported`](https://learn.microsoft.com/en-us/windows/win32/api/audioclient/nf-audioclient-iaudioclient-isformatsupported).

## Após configurar o Windows para 5.1

Uma segunda consulta somente leitura confirmou a mudança:

- Formato de mistura do Windows: **6 canais, 48 kHz, 32 bits float, máscara `0x3F`**.
- Formato padrão do endpoint: **6 canais, 48 kHz, 16 bits PCM, máscara `0x3F`**.
- `IsFormatSupported` para PCM de 6 canais a 48 kHz retornou **`S_OK` em modo compartilhado e exclusivo, 16 bits**, com máscaras `0x3F` e `0x60F`.
- Em modo compartilhado, 24/32 bits também retornaram `S_OK` devido à conversão do mixer; em modo exclusivo, 24/32 bits seguiram rejeitados (`0x88890008`).
- A consulta não altera a configuração. A configuração 5.1 foi feita antes desta segunda auditoria.

**Conclusão:** a saída HDMI direta está pronta, do ponto de vista do Windows, para enviar seis canais PCM separados a 48 kHz/16 bits e aplicar filtros em modo compartilhado. Falta reproduzir um teste de seis canais e confirmar que cada RCA do UD851B entrega o canal correspondente, sem downmix ou troca de posições.
