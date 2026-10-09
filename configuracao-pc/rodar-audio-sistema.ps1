# Compatibility entry point for existing shortcuts/task scheduler.
# One CM6206 manager owns readiness and restores routing when stopped.
param(
    [ValidateSet('Pcm','Optical','Auto')][string]$Mode='Pcm',
    [ValidateSet('Auto','Stereo','Native')][string]$InputMode='Auto'
)
$ErrorActionPreference='Stop'
if ([IO.File]::Exists((Join-Path $PSScriptRoot 'audio-sistema.desligado'))) {exit 0}
$manager=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\scripts\pc-cm6206-system.ps1'))
if (-not [IO.File]::Exists($manager)) {throw 'Gerenciador CM6206 ausente; atualize o projeto completo.'}
& $manager -Action Start -Mode $Mode -InputMode $InputMode
