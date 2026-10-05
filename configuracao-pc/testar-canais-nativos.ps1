param([string]$AudioFile = 'teste-caixas-5.1.wav', [string]$LogName = 'teste-caixas-sistema-global.log')
$ErrorActionPreference = 'Stop'
$audioPath = Join-Path $PSScriptRoot $AudioFile
if (-not (Test-Path -LiteralPath $audioPath)) { throw "Teste inexistente: $audioPath" }
$testMarker = Join-Path $PSScriptRoot 'audio-sistema.teste-nativo'
try {
    [DateTime]::UtcNow.ToString('o') | Set-Content -LiteralPath $testMarker -Encoding ASCII
    $testArgs = '--no-config "--include=' + (Join-Path $PSScriptRoot 'mpv-sistema-player.conf') + '" --force-window=yes --keep-open=yes "--log-file=' + (Join-Path $PSScriptRoot $LogName) + '" "' + $audioPath + '"'
    Start-Process -FilePath (Join-Path $PSScriptRoot 'mpv-portatil\mpv.exe') -ArgumentList $testArgs -Wait
} finally {
    Remove-Item -LiteralPath $testMarker -ErrorAction SilentlyContinue
}
