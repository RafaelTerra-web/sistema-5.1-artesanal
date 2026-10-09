param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\{0\.0\.1\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')]
    [string]$CaptureEndpointId,
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')]
    [string]$RenderEndpointId,
    [ValidateRange(5, 30)] [int]$Seconds = 10,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$OutputName = ('relay-muted-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')),
    [switch]$AudibleFronts,
    [switch]$AudibleSix,
    [switch]$AudibleSatellites,
    [ValidateRange(1, 60)] [int]$Volume = 4
)
$ErrorActionPreference = 'Stop'
if (($AudibleFronts -and $AudibleSix) -or ($AudibleFronts -and $AudibleSatellites) -or ($AudibleSix -and $AudibleSatellites)) { throw 'Choose one audible mode.' }
if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') { throw 'Use powershell.exe -STA -NoProfile -File test-windows-spdif-relay.ps1 ...' }
$workspacePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$artifactPath = [IO.Path]::GetFullPath((Join-Path $workspacePath 'android-a34\artifacts'))
$destinationPath = Join-Path $artifactPath ('windows-optical-relay-' + [DateTime]::UtcNow.ToString('yyyy-MM-dd'))
$pcmPath = Join-Path $destinationPath ($OutputName + '.pcm')
$logPath = Join-Path $destinationPath ($OutputName + '-mpv.log')
$stopPath = Join-Path $destinationPath ($OutputName + '.stop')
$reportPath = Join-Path $destinationPath ($OutputName + '-report.json')
foreach ($probePath in @($pcmPath, $logPath, $stopPath, $reportPath)) {
    if (Test-Path -LiteralPath $probePath) { throw 'Refusing to overwrite probe artifacts. Choose a new OutputName.' }
}
Add-Type -Path @(
    (Join-Path $workspacePath 'android-a34\scripts\windows-cm6206\CoreAudioFormatProbe.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifCapture.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifRelay.cs')
)
if ($AudibleSatellites) { Write-Output ('Four satellites only, FC/LFE muted, linear gain {0} percent.' -f $Volume) }
elseif ($AudibleSix) { Write-Output ('Six-channel listening probe, linear gain {0} percent.' -f $Volume) }
elseif ($AudibleFronts) { Write-Output ('Fronts listening probe: FL/FR only, linear gain {0} percent.' -f $Volume) }
else { Write-Output 'Live optical decode-to-USB probe: mpv output muted, volume 0.' }
Write-Output ('Stop sentinel: ' + $stopPath)
$methodArguments = @(
    $CaptureEndpointId, $RenderEndpointId,
    (Join-Path $workspacePath 'configuracao-pc\mpv-portatil\mpv.exe'),
    $pcmPath, $logPath, $stopPath, $artifactPath, $Seconds
)
if ($AudibleSatellites) {
    $result = [Sistema51.Cm6206.WindowsSpdifRelay]::RunSatellites(
        $CaptureEndpointId, $RenderEndpointId, $methodArguments[2],
        $pcmPath, $logPath, $stopPath, $artifactPath, $Seconds, $Volume)
} elseif ($AudibleSix) {
    $result = [Sistema51.Cm6206.WindowsSpdifRelay]::RunSixChannels(
        $CaptureEndpointId, $RenderEndpointId, $methodArguments[2],
        $pcmPath, $logPath, $stopPath, $artifactPath, $Seconds, $Volume)
} elseif ($AudibleFronts) {
    $result = [Sistema51.Cm6206.WindowsSpdifRelay]::RunFronts(
        $CaptureEndpointId, $RenderEndpointId, $methodArguments[2],
        $pcmPath, $logPath, $stopPath, $artifactPath, $Seconds, $Volume)
} else {
    $result = [Sistema51.Cm6206.WindowsSpdifRelay]::Run(
        $CaptureEndpointId, $RenderEndpointId, $methodArguments[2],
        $pcmPath, $logPath, $stopPath, $artifactPath, $Seconds)
}
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
$result | Select-Object Ok, Outcome, CaptureFriendlyName, RenderFriendlyName, Frames, BytesSent, MpvExitCode, PlaybackMuted, SixChannelUsbOutputNegotiated, Ac3SixChannelSourceDetected, DecoderCrcCheckingEnabled, DecoderLogClean, CleanupComplete, Error
Write-Output ('Report: ' + $reportPath)
if (-not $result.Ok) { exit 1 }
