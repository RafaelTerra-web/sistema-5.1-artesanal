$ErrorActionPreference = 'Stop'
$trace = 'C:\Windows\ServiceProfiles\LocalService\AppData\Local\Temp\EqualizerAPO.log'
Copy-Item -LiteralPath $trace -Destination (Join-Path $PSScriptRoot 'apo-upmix-trace.log') -Force
