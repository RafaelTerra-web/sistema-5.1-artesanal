$ErrorActionPreference = 'Stop'
$trace = 'C:\Windows\ServiceProfiles\LocalService\AppData\Local\Temp\EqualizerAPO.log'
Copy-Item -LiteralPath $trace -Destination (Join-Path $PSScriptRoot 'apo-upmix-trace.log') -Force
Set-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\EqualizerAPO' -Name EnableTrace -Value 'false'
Restart-Service -Name AudioSrv -Force
[ordered]@{AudioService=(Get-Service AudioSrv).Status.ToString(); EnableTrace=(Get-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\EqualizerAPO').EnableTrace} | ConvertTo-Json | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'upmix-finalizacao-status.json') -Encoding UTF8
