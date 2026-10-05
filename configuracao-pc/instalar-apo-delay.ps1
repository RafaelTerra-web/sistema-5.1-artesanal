# Instalacao local autorizada para aplicar o atraso solicitado ao HDMI direto.
$ErrorActionPreference = 'Stop'
$installerPath = Join-Path $PSScriptRoot 'EqualizerAPO-x64-1.4.2.exe'
$expectedHash = '7403BE7427BBE1936A40DDED082829B6E217FC4F5990FEE5CBA501F0AE055AFA'
$statusPath = Join-Path $PSScriptRoot 'instalacao-apo-status.txt'
try {
    if ((Get-FileHash -LiteralPath $installerPath -Algorithm SHA256).Hash -ne $expectedHash) {
        throw 'O hash do instalador nao corresponde a copia verificada.'
    }
    'Instalando Equalizer APO 1.4.2...' | Set-Content -LiteralPath $statusPath -Encoding UTF8
    $installProcess = Start-Process -FilePath $installerPath -ArgumentList '/S' -PassThru -Wait
    if ($installProcess.ExitCode -ne 0) { throw ('Instalador retornou ' + $installProcess.ExitCode) }
    $apoConfigDir = Join-Path $env:ProgramFiles 'EqualizerAPO\config'
    if (-not (Test-Path -LiteralPath $apoConfigDir)) { throw 'Pasta config do Equalizer APO nao encontrada.' }
    $mainConfigPath = Join-Path $apoConfigDir 'config.txt'
    $mainBackupPath = Join-Path $apoConfigDir 'config-antes-delay.txt'
    if ((Test-Path -LiteralPath $mainConfigPath) -and -not (Test-Path -LiteralPath $mainBackupPath)) {
        Copy-Item -LiteralPath $mainConfigPath -Destination $mainBackupPath
    }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'delay-5.1-70ms.txt') -Destination (Join-Path $apoConfigDir 'delay-5.1-70ms.txt') -Force
    'Include: delay-5.1-70ms.txt' | Set-Content -LiteralPath $mainConfigPath -Encoding ASCII
    'Programa instalado; perfil copiado. Verificar vinculo ao HDMI no Device Selector antes de afirmar que o atraso esta ativo.' | Set-Content -LiteralPath $statusPath -Encoding UTF8
} catch {
    ('ERRO: ' + $_.Exception.Message) | Set-Content -LiteralPath $statusPath -Encoding UTF8
    exit 1
}
