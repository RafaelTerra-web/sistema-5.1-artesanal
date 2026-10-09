param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')]
    [string]$RenderEndpointId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\{0\.0\.1\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')]
    [string]$CaptureEndpointId,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$Name = ('loop-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss')),
    [string]$MpvPath,
    [string]$SignalPath,
    [switch]$AssertSyntheticCopyright
)
# The user must have connected the external optical cable OUT -> IN.
# Only known synthetic PCM is used; no TV content or amplifiers participate.
$ErrorActionPreference = 'Stop'
if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    throw 'Run in powershell.exe -STA.'
}
$workspacePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$artifactPath = Join-Path $workspacePath 'android-a34\artifacts'
$outputPath = Join-Path $artifactPath ('optical-loopback-' + [DateTime]::UtcNow.ToString('yyyy-MM-dd'))
$outputPath = Join-Path $outputPath $Name
if (Test-Path -LiteralPath $outputPath) { throw 'Choose a new name; previous evidence must be preserved.' }
if(-not $SignalPath){$SignalPath=Join-Path $artifactPath 'optical-2026-10-09\vectors\pcm-tv-speakers-minus24dbfs-5s.wav'}
if (-not (Test-Path -LiteralPath $signalPath)) { throw 'Known synthetic PCM vector missing.' }
if ((Get-FileHash -LiteralPath $signalPath -Algorithm SHA256).Hash -ne '2A3085C54FBCF801D8245C65222BC3959B4AA9FD3FDCA737F2B221FF38620B48') {
    throw 'Synthetic PCM identity check failed.'
}
New-Item -ItemType Directory -Path $outputPath | Out-Null
Add-Type -Path @(
    (Join-Path $workspacePath 'android-a34\scripts\windows-cm6206\CoreAudioFormatProbe.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifCapture.cs'),
    (Join-Path $PSScriptRoot 'Cm6206OpticalGuard.cs')
)
$report = [ordered]@{
    kind = 'external_optical_pcm_loopback'
    startedAtUtc = [DateTime]::UtcNow.ToString('o')
    renderEndpointId = $RenderEndpointId
    captureEndpointId = $CaptureEndpointId
    cableSetup = 'User-confirmed external SPDIF OUT to SPDIF IN on the same CM6206'
    signal = 'Own synthetic stereo PCM16 48k, 400/700Hz, peak -24dBFS'
    syntheticCopyrightAsserted = [bool]$AssertSyntheticCopyright
    defaultEndpointChanged = $false
    endpointVolumeChanged = $false
    amplifierUsed = $false
    sourceValidated = $false
    bitPerfectValidated = $false
    ac3Validated = $false
}
$guard = $null
$player = $null
$capture = $null
$failure = $null
try {
    $render = [Sistema51.Cm6206.WindowsSpdifCapture]::ReadVolumeOnly($RenderEndpointId)
    $input = [Sistema51.Cm6206.WindowsSpdifCapture]::ReadVolumeOnly($CaptureEndpointId)
    $report.renderVolume = $render
    $report.captureVolume = $input
    if (-not $render.Ok -or -not $input.Ok -or
        $render.FriendlyName -notlike '*USB Sound Device*' -or
        $input.FriendlyName -notlike '*SPDIF*USB Sound Device*') {
        throw 'Explicit CM6206 render and SPDIF capture endpoints required.'
    }
    if ($render.EndpointMuted -or $input.EndpointMuted -or
        $render.EndpointMasterVolumeScalar -eq 0 -or $input.EndpointMasterVolumeScalar -eq 0) {
        throw 'Endpoint muted/zero volume; test cannot diagnose optical content.'
    }
    $guard = New-Object Sistema51.Hardware.Cm6206OpticalGuard (Join-Path $outputPath 'original-registers.json')
    $report.originalRegisters = $guard.Original
    $guard.EnsureExternalPcm48([bool](-not $AssertSyntheticCopyright))
    $report.configuredRegisters = $guard.ReadAll()
    if(-not $MpvPath){$MpvPath=Join-Path $workspacePath 'configuracao-pc\mpv-portatil\mpv.com'}
    if(-not(Test-Path -LiteralPath $MpvPath -PathType Leaf)){throw 'Existing local mpv is required.'}
    $guid = $RenderEndpointId.Substring('{0.0.0.00000000}.'.Length)
    $arguments = @('--no-config', '--no-video', '--ao=wasapi', '--audio-exclusive=yes',
        ('--audio-device=wasapi/' + $guid), '--audio-channels=stereo', '--audio-samplerate=48000',
        '--audio-format=s16', '--volume=100', '--loop-file=2',
        ('--log-file="{0}"' -f (Join-Path $outputPath 'mpv.log')),
        '--msg-level=all=info,ao/wasapi=debug', ('"{0}"' -f $signalPath))
    $report.playerArguments = $arguments
    $player = Start-Process -FilePath $mpvPath -ArgumentList $arguments -WindowStyle Hidden -PassThru `
        -RedirectStandardOutput (Join-Path $outputPath 'mpv-console.txt') `
        -RedirectStandardError (Join-Path $outputPath 'mpv-errors.txt')
    # Retain the native process handle so PowerShell can read ExitCode after completion.
    $player.Handle | Out-Null
    Start-Sleep -Milliseconds 600
    $player.Refresh()
    if ($player.HasExited) { throw 'PCM transmitter exited before capture.' }
    # Format initialization may update firmware state. Verify/reapply the scoped settings.
    $guard.EnsureExternalPcm48([bool](-not $AssertSyntheticCopyright))
    $report.duringPlaybackBeforeCapture = $guard.ReadAll()
    $capture = [Sistema51.Cm6206.WindowsSpdifCapture]::Run($CaptureEndpointId,
        (Join-Path $outputPath 'capture.pcm'), $artifactPath)
    $report.capture = $capture
    $report.duringPlaybackAfterCapture = $guard.ReadAll()
    $afterCapture = $report.duringPlaybackAfterCapture
    $expectedStatus = if ($AssertSyntheticCopyright) { 0x2000 } else { 0x2004 }
    if (($afterCapture[0] -band 0xF007) -ne $expectedStatus -or
        ($afterCapture[1] -band 0x000F) -ne 0 -or
        ($afterCapture[5] -band 0x3F00) -ne 0x3000) {
        throw 'Firmware configuration changed during capture; content cannot validate the intended loop.'
    }
    if (-not $capture.Ok) { throw ('Capture failed: ' + $capture.Error) }
    if (-not $player.WaitForExit(20000)) { throw 'Bounded PCM player exceeded its deadline.' }
    $player.Refresh()
    $report.playerExitCode = $player.ExitCode
    if ($player.ExitCode -ne 0) { throw 'PCM transmitter failed; inspect private mpv log.' }
}
catch { $failure = $_.Exception.Message; $report.error = $failure }
finally {
    if ($null -ne $player) {
        try {
            $player.Refresh()
            if (-not $player.HasExited) { Stop-Process -Id $player.Id -ErrorAction Stop; $player.WaitForExit(5000) | Out-Null }
        } catch { $report.playerCleanupError = $_.Exception.Message }
    }
    if ($null -ne $guard) {
        $guard.Dispose()
        $report.registerWritesPerformed = $guard.WritesPerformed
        $report.registerOperations = $guard.Operations
        $report.restorationVerified = $guard.RestorationVerified
        $report.restorationErrors = $guard.RestorationErrors
    } else { $report.registerWritesPerformed = $false; $report.restorationVerified = $true }
    $report.finishedAtUtc = [DateTime]::UtcNow.ToString('o')
    $report.transportTestCompleted = ($null -eq $failure -and $null -ne $capture -and $capture.Ok)
    $report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath (Join-Path $outputPath 'session.json') -Encoding UTF8
}
[pscustomobject]@{
    transportTestCompleted = $report.transportTestCompleted
    captureFrames = if ($null -ne $capture) { $capture.Frames } else { 0 }
    restorationVerified = $report.restorationVerified
    error = $failure
    report = (Join-Path $outputPath 'session.json')
} | Format-List
if ($null -ne $failure -or -not $report.restorationVerified) { exit 1 }
