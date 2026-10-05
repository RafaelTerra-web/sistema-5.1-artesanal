# Instalação e dependências

## O pacote publicado

O Git contém os fontes. Drivers, executáveis e credenciais não estão nele. O servidor do controle usa módulos nativos Node, então não é preciso instalar um `node_modules` para executá-lo ou testar a UI.

| Dependência | Uso | Origem e local esperado |
| --- | --- | --- |
| Windows PowerShell 5.1 e .NET Framework | C# via Add-Type, WASAPI/WinRT/Win32 e painéis | `powershell.exe`, já usado pelos iniciadores |
| Node.js | Servidor do controle e testes JS | Ambiente validado: 24.14.0; [distribuição oficial](https://nodejs.org/en/download) |
| Python | Diagnósticos e backend histórico Netflix | Ambiente validado: 3.14.3; scripts do backend usam biblioteca padrão |
| mpv Windows | DSP, codificação e saída HDMI | `configuracao-pc/mpv-portatil/mpv.exe`; [opções de instalação](https://mpv.io/installation/) |
| SoundVolumeView | Seleção de dispositivos, formato e defaults Windows | `configuracao-pc/ferramentas/soundvolumeview/SoundVolumeView.exe`; [página da NirSoft](https://www.nirsoft.net/utils/sound_volume_view.html) |
| VB-CABLE | Endpoint virtual de áudio | [Página do fabricante](https://vb-audio.com/Cable/); instalação/reinício conforme instruções do fabricante |
| Equalizer APO | Upmix por aplicativo antes da mistura | [Projeto](https://sourceforge.net/projects/equalizerapo/), [referência da configuração](https://sourceforge.net/p/equalizerapo/wiki/Configuration%20reference/) |

Para mpv, confira uma compilação com `lavfi` e `lavcac3enc`; a página do projeto diferencia builds de terceiros e CI. Não deduza disponibilidade de filtros apenas do nome do ZIP.

## Preparar uma cópia nova

```powershell
git clone https://github.com/RafaelTerra-web/sistema-5.1-artesanal.git
cd sistema-5.1-artesanal
npm test
npm run test:netflix
powershell.exe -NoProfile -ExecutionPolicy Bypass -File .\scripts\Preparar exemplos locais.ps1
```

O script de exemplos recusa sobrescrever arquivos existentes. Copia o preset e os dois perfis mpv, sem instalar driver, abrir áudio, iniciar serviço ou modificar o registro.

**Antes de iniciar a rota**, adapte os GUIDs e a descoberta de HDMI descritos em [Reaproveitamento](REAPROVEITAMENTO.md). O GUID zerado nos exemplos é um marcador a ser substituído, não uma saída válida. O gerenciador e os scripts históricos ainda conhecem o VB-CABLE e o SONY/NVIDIA do PC de origem.

Configure o endpoint virtual em seis canais e 48 kHz e confira o upmix no APO para esse dispositivo. Os scripts de instalação do APO possuem mudanças no registro específicas da máquina de origem; revise-os para o novo endpoint em vez de executá-los indiscriminadamente.

Se deseja eliminar a calibração de deriva anterior, ajuste a etapa `asetrate` nos dois perfis e a geração de perfil em `Ajustar qualidade do audio.ps1`. Alterar somente um `.conf` não basta quando um gerenciador posteriormente o regenera.

## Entradas de execução

No PC já adaptado, os iniciadores originais ficam em `configuracao-pc/`:

- `Controle do sistema 5.1.cmd`: painel local de ligar/parar/perfil.
- `Iniciar audio 5.1 do sistema.cmd` e `Parar audio 5.1 do sistema.cmd`.
- `Equalizador do sub.cmd`: painel do LFE e volume mestre.
- `Controle remoto do PC.cmd`: servidor LAN e página com endereço/PIN.
- `Parar controle remoto.cmd`.
- `Netflix - app com Dolby 5.1.cmd` e `Netflix - Edge com Dolby 5.1.cmd`.

Os atalhos da Área de Trabalho e tarefas automáticas do PC original não fazem parte do Git. Recrie-os no destino depois de revisar a implantação.

## Controle na rede local

O iniciador descobre uma interface conectada, preferindo a rede apropriada do PC. A porta é 8787. O endereço do celular deve usar o IP LAN anunciado pelo novo PC, e não copiar o IP antigo do autor. Ethernet no PC e Wi-Fi no celular funcionam quando fazem parte da mesma rede local.

O servidor cria `connection-private.json` e o arquivo da ponte na primeira abertura. Instale a extensão somente depois disso, se precisar da integração específica de faixas Netflix. Esses arquivos gerados permanecem ignorados pelo Git.

A regra de firewall do iniciador é limitada ao executável Node e à sub-rede local. Se você mudar o caminho do Node ou a porta, ajuste o iniciador e `controle-remoto/firewall.ps1` juntos. O controle é um serviço de rede local com PIN, não uma aplicação pronta para publicação na internet.

## Verificação inicial

1. Rode os testes isolados antes de abrir áudio.
2. Confirme a saída física e o formato do cabo virtual.
3. Verifique os seis canais individualmente; geradores de sinais estão incluídos, mas os WAVs locais não.
4. Confira que apenas a instância pertencente à aplicação usa o HDMI.
5. Inicie em volume baixo, meça atraso/deriva e só então ajuste EQ e graves.
6. Consulte o estado pelo painel e examine seus logs locais quando houver descartes ou reinícios.
