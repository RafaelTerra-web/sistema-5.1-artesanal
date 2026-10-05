$ErrorActionPreference = 'Stop'
$mutex = [System.Threading.Mutex]::new($false, 'Local\SistemaArtesanalAudio51')
if (-not $mutex.WaitOne(0)) { $mutex.Dispose(); exit 0 }
$pidPath = Join-Path $PSScriptRoot 'audio-sistema.pid'
$stopPath = Join-Path $PSScriptRoot 'audio-sistema.stop'
$logPath = Join-Path $PSScriptRoot 'audio-sistema.log'
Remove-Item -LiteralPath $stopPath -ErrorAction SilentlyContinue
$PID | Set-Content -LiteralPath $pidPath -Encoding ASCII
try {
    $soundTool = Join-Path $PSScriptRoot 'ferramentas\soundvolumeview\SoundVolumeView.exe'
    $cableEndpoint = '{0.0.0.00000000}.{1480f3d6-872e-45ff-a839-c8b330d0127e}'
    $renderRoot = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render'
    $sonyGuids = @()
    foreach ($endpointKey in (Get-ChildItem -LiteralPath $renderRoot)) {
        if ($endpointKey.GetValue('DeviceState') -ne 1) { continue }
        $properties = Get-Item -LiteralPath (Join-Path $endpointKey.PSPath 'Properties') -ErrorAction SilentlyContinue
        if ($properties -and $properties.GetValue('{a45c254e-df1c-4efd-8020-67d146a850e0},2') -match 'SONY' -and
            $properties.GetValue('{b3f8fa53-0004-438e-9003-51a46e139bfc},6') -match 'NVIDIA') {
            $sonyGuids += $endpointKey.PSChildName
        }
    }
    if ($sonyGuids.Count -eq 1) {
        $sonyEndpoint = '{0.0.0.00000000}.' + $sonyGuids[0]
        Start-Process -FilePath $soundTool -WindowStyle Hidden -Wait -ArgumentList @('/SetAllowExclusive', $sonyEndpoint, '1')
        Start-Process -FilePath $soundTool -WindowStyle Hidden -Wait -ArgumentList @('/SetExclusivePriority', $sonyEndpoint, '1')
        $configPath = Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'
        $config = Get-Content -LiteralPath $configPath -Raw
        $config = [regex]::Replace($config, '(?m)^audio-device=wasapi/\{[^}]+\}', ('audio-device=wasapi/' + $sonyGuids[0]))
        Set-Content -LiteralPath $configPath -Value $config -Encoding ASCII
    }
    # Configure HDMI first; changing endpoint properties can alter Windows' default.
    Start-Process -FilePath $soundTool -WindowStyle Hidden -Wait -ArgumentList @('/SetDefault', $cableEndpoint, 'all')
    Add-Type -Path @((Join-Path $PSScriptRoot 'StereoUpmix.cs'), (Join-Path $PSScriptRoot 'RelayLoopback.cs'))
    [RelayLoopback]::Run(
        '{0.0.0.00000000}.{1480f3d6-872e-45ff-a839-c8b330d0127e}',
        (Join-Path $PSScriptRoot 'mpv-portatil\mpv.exe'),
        (Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'),
        $logPath,
        $stopPath
    )
} catch {
    ($_ | Out-String) | Add-Content -LiteralPath $logPath -Encoding UTF8
    exit 1
} finally {
    Remove-Item -LiteralPath $pidPath -ErrorAction SilentlyContinue
    $mutex.ReleaseMutex()
    $mutex.Dispose()
}
