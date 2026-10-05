$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Audio gerenciamento comum.ps1')
if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'audio-sistema.desligado')) { exit 0 }
$mutex = [System.Threading.Mutex]::new($false, 'Local\SistemaArtesanalAudio51')
if (-not (Wait-AudioMutex $mutex 0)) { $mutex.Dispose(); exit 0 }
$pidPath = Join-Path $PSScriptRoot 'audio-sistema.pid'
$stopPath = Join-Path $PSScriptRoot 'audio-sistema.stop'
$logPath = Join-Path $PSScriptRoot 'audio-sistema.log'
if ((Test-Path -LiteralPath (Join-Path $PSScriptRoot 'audio-sistema.desligado')) -or
    (Test-Path -LiteralPath $stopPath)) {
    $mutex.ReleaseMutex()
    $mutex.Dispose()
    exit 0
}
$script:lastRunnerError = $null
$script:runnerStartedUtc = $null
$script:lastRunnerState = $null; $script:lastStateWrite = [DateTime]::MinValue
function Set-RunnerState([string]$State, [string]$ErrorText) {
    if (-not $ErrorText -and $State -eq $script:lastRunnerState -and ([DateTime]::UtcNow - $script:lastStateWrite).TotalSeconds -lt 15) { return }
    if ($ErrorText) { $script:lastRunnerError = $ErrorText }
    $stateRecord = [ordered]@{RunnerId=$PID;InicioProcessoUtc=$script:runnerStartedUtc;Estado=$State;AtualizadoEm=[DateTime]::UtcNow.ToString('o');UltimoErro=$script:lastRunnerError}
    # A diagnostic write failure must not interrupt otherwise healthy playback.
    try { Set-AudioFileTransaction @((New-AudioTextFile (Join-Path $PSScriptRoot 'audio-sistema-estado.json') ($stateRecord | ConvertTo-Json -Compress))) }
    catch {
        try { ('Falha no diagnostico: ' + $_.Exception.Message) | Add-Content -LiteralPath $logPath -Encoding UTF8 } catch { }
    }
    $script:lastRunnerState = $State; $script:lastStateWrite = [DateTime]::UtcNow
}
try {
    $script:runnerStartedUtc = (Get-CimInstance Win32_Process -Filter "ProcessId=$PID").CreationDate.ToUniversalTime().ToString('o')
    Set-AudioFileTransaction @((New-AudioTextFile $pidPath ([string]$PID) 'ASCII'))
    Set-RunnerState 'Iniciando' $null
    $soundTool = Join-Path $PSScriptRoot 'ferramentas\soundvolumeview\SoundVolumeView.exe'
    $cableEndpoint = '{0.0.0.00000000}.{1480f3d6-872e-45ff-a839-c8b330d0127e}'
    $renderRoot = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render'
    if (Test-Path -LiteralPath $stopPath) { return }
    Invoke-AudioSoundTool $PSScriptRoot @('/SetDefault', $cableEndpoint, 'all')
    Add-Type -Path @((Join-Path $PSScriptRoot 'StereoUpmix.cs'), (Join-Path $PSScriptRoot 'RelayLoopback.cs'), (Join-Path $PSScriptRoot 'RelayLoopbackLowLatency.cs'))
    $configPath = Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'
    $recoveryLog = Join-Path $PSScriptRoot 'audio-sistema-recuperacao.log'
    while (-not (Test-Path -LiteralPath $stopPath)) {
        if (Test-Path -LiteralPath (Join-Path $PSScriptRoot 'audio-sistema.desligado')) { break }
        $sonyGuids = @()
        foreach ($endpointKey in (Get-ChildItem -LiteralPath $renderRoot)) {
            if ($endpointKey.GetValue('DeviceState') -ne 1) { continue }
            $properties = Get-Item -LiteralPath (Join-Path $endpointKey.PSPath 'Properties') -ErrorAction SilentlyContinue
            if ($properties -and $properties.GetValue('{a45c254e-df1c-4efd-8020-67d146a850e0},2') -match 'SONY' -and
                $properties.GetValue('{b3f8fa53-0004-438e-9003-51a46e139bfc},6') -match 'NVIDIA') {
                $sonyGuids += $endpointKey.PSChildName
            }
        }
        if ($sonyGuids.Count -ne 1) {
            Set-RunnerState 'Aguardando HDMI' $null
            Start-Sleep -Milliseconds 1000
            continue
        }
        try {
            # HDMI reconnection and EQ/volume changes share one configuration lock.
            $configMutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalLfeEqualizador')
            $configLocked = $false
            try {
                $configLocked = Wait-AudioMutex $configMutex 5000
                if (-not $configLocked) { throw 'Outra gravacao da configuracao de audio esta em andamento.' }
                $config = Get-Content -LiteralPath $configPath -Raw
                if ([regex]::Matches($config,'(?m)^audio-device=wasapi/\{[^}]+\}\s*$').Count -ne 1) { throw 'A configuracao precisa de uma unica saida HDMI WASAPI.' }
                $updatedConfig = [regex]::Replace($config, '(?m)^audio-device=wasapi/\{[^}]+\}', ('audio-device=wasapi/' + $sonyGuids[0]))
                if ($updatedConfig -ne $config) {
                    $sonyEndpoint = '{0.0.0.00000000}.' + $sonyGuids[0]
                    Invoke-AudioSoundTool $PSScriptRoot @('/SetAllowExclusive', $sonyEndpoint, '1')
                    Invoke-AudioSoundTool $PSScriptRoot @('/SetExclusivePriority', $sonyEndpoint, '1')
                    Set-AudioFileTransaction @((New-AudioTextFile $configPath $updatedConfig 'ASCII'))
                    Invoke-AudioSoundTool $PSScriptRoot @('/SetDefault', $cableEndpoint, 'all')
                }
            } finally {
                if ($configLocked) { $configMutex.ReleaseMutex() }; $configMutex.Dispose()
            }
            if (Test-Path -LiteralPath $stopPath) { break }
            Set-RunnerState 'Iniciando saida HDMI' $null
            [RelayLoopbackLowLatency]::Run(
                $cableEndpoint,
                (Join-Path $PSScriptRoot 'mpv-portatil\mpv.exe'),
                $configPath,
                $logPath,
                $stopPath
            )
        } catch {
            Set-RunnerState 'Reconectando' $_.Exception.Message
            if ([IO.File]::Exists($recoveryLog) -and (Get-Item -LiteralPath $recoveryLog).Length -gt 2MB) {
                Move-Item -LiteralPath $recoveryLog -Destination ($recoveryLog + '.anterior') -Force
            }
            ((Get-Date).ToString('s') + ' ' + ($_ | Out-String)) | Add-Content -LiteralPath $recoveryLog -Encoding UTF8
        }
        if (-not (Test-Path -LiteralPath $stopPath)) {
            # Retain diagnostics when reconnecting a temporarily invalid HDMI endpoint.
            Copy-Item -LiteralPath $logPath -Destination ($logPath + '.recuperacao') -Force -ErrorAction SilentlyContinue
            Copy-Item -LiteralPath ($logPath + '.mpv.log') -Destination ($logPath + '.mpv.recuperacao.log') -Force -ErrorAction SilentlyContinue
            Start-Sleep -Milliseconds 1000
        }
    }
} catch {
    try { Set-RunnerState 'Falha' $_.Exception.Message } catch { }
    ($_ | Out-String) | Add-Content -LiteralPath $logPath -Encoding UTF8
    exit 1
} finally {
    if ([IO.File]::Exists($pidPath)) {
        try {
            if ([IO.File]::ReadAllText($pidPath).Trim() -eq [string]$PID) { Remove-Item -LiteralPath $pidPath -ErrorAction SilentlyContinue }
        } catch { }
    }
    try { Set-RunnerState 'Encerrado' $null } catch { }
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
