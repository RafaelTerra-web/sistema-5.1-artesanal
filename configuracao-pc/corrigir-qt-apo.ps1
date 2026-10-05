$ErrorActionPreference = 'Stop'
$apoDir = 'C:\Program Files\EqualizerAPO'
$statusPath = Join-Path $PSScriptRoot 'correcao-qt-status.txt'
try {
    $qtConfigPath = Join-Path $apoDir 'qt.conf'
    if (Test-Path -LiteralPath $qtConfigPath) {
        $qtBackupPath = Join-Path $PSScriptRoot 'qt.conf.antes'
        if (-not (Test-Path -LiteralPath $qtBackupPath)) { Copy-Item -LiteralPath $qtConfigPath -Destination $qtBackupPath }
    }
    @('[Paths]', 'Plugins=qt') | Set-Content -LiteralPath $qtConfigPath -Encoding ASCII
    $env:QT_PLUGIN_PATH = Join-Path $apoDir 'qt'
    $env:QT_QPA_PLATFORM_PLUGIN_PATH = Join-Path $apoDir 'qt\platforms'
    $env:QT_DEBUG_PLUGINS = '1'
    Set-Location -LiteralPath $apoDir
    $qtErrorPath = Join-Path $PSScriptRoot 'qt-deviceselector.log'
    $qtOutputPath = Join-Path $PSScriptRoot 'qt-deviceselector-stdout.log'
    $selectorProcess = Start-Process -FilePath (Join-Path $apoDir 'DeviceSelector.exe') -WorkingDirectory $apoDir -RedirectStandardError $qtErrorPath -RedirectStandardOutput $qtOutputPath -PassThru
    ('Configuracao Qt gravada; Device Selector iniciado, PID ' + $selectorProcess.Id) | Set-Content -LiteralPath $statusPath -Encoding UTF8
} catch {
    ('ERRO: ' + $_.Exception.Message) | Set-Content -LiteralPath $statusPath -Encoding UTF8
    exit 1
}
