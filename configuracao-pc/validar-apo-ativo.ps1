$ErrorActionPreference = 'Stop'
$apoKey = 'HKLM:\SOFTWARE\EqualizerAPO'
$statusPath = Join-Path $PSScriptRoot 'validacao-apo-status.txt'
$tracePath = 'C:\Windows\ServiceProfiles\LocalService\AppData\Local\Temp\EqualizerAPO.log'
$originalTrace = (Get-ItemProperty -LiteralPath $apoKey).EnableTrace
$testExitCode = $null
$validationError = $null
try {
    'Verificando carregamento do filtro no audio compartilhado...' | Set-Content -LiteralPath $statusPath -Encoding UTF8
    Set-ItemProperty -LiteralPath $apoKey -Name EnableTrace -Value 'true'
    Restart-Service -Name AudioSrv -Force
    $mpvPath = Join-Path $PSScriptRoot 'mpv-portatil\mpv.com'
    $configPath = Join-Path $PSScriptRoot 'mpv-pcm-direto.conf'
    $silencePath = Join-Path $PSScriptRoot 'silencio-5.1.wav'
    $playerLogPath = Join-Path $PSScriptRoot 'teste-pcm51-apo-ativo.log'
    $testArgs = @('--no-config', ('--include="' + $configPath + '"'), '--no-video', ('--log-file="' + $playerLogPath + '"'), '--msg-level=all=v', ('"' + $silencePath + '"'))
    $testProcess = Start-Process -FilePath $mpvPath -WindowStyle Hidden -ArgumentList $testArgs -PassThru -Wait
    $testExitCode = $testProcess.ExitCode
    if ($testExitCode -ne 0) { throw ('Reproducao de verificacao retornou ' + $testExitCode) }
} catch {
    $validationError = $_.Exception.Message
} finally {
    if ($null -eq $originalTrace) { $originalTrace = 'false' }
    Set-ItemProperty -LiteralPath $apoKey -Name EnableTrace -Value $originalTrace
    Restart-Service -Name AudioSrv -Force
}
if (Test-Path -LiteralPath $tracePath) {
    Copy-Item -LiteralPath $tracePath -Destination (Join-Path $PSScriptRoot 'apo-validacao.log') -Force
}
$validationResult = [ordered]@{
    TestExitCode = $testExitCode
    Error = $validationError
    TraceLogCopied = Test-Path -LiteralPath (Join-Path $PSScriptRoot 'apo-validacao.log')
    AudioServiceStatus = (Get-Service -Name AudioSrv).Status.ToString()
    TraceRestored = (Get-ItemProperty -LiteralPath $apoKey).EnableTrace
}
$validationResult | ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
