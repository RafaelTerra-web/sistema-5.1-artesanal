param(
    [Parameter(Mandatory = $true)]
    [ValidatePattern('^\{0\.0\.1\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')]
    [string]$CaptureEndpointId,
    [ValidatePattern('^[A-Za-z0-9][A-Za-z0-9._-]{0,63}$')]
    [string]$OutputName = ('spdif-' + [DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff')),
    [ValidateRange(1, 10)]
    [int]$Seconds = 3
)
$ErrorActionPreference = 'Stop'
if ([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA') {
    throw 'Use powershell.exe -STA -NoProfile -File capture-windows-spdif.ps1 ...'
}
$workspacePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
$artifactPath = [IO.Path]::GetFullPath((Join-Path $workspacePath 'android-a34\artifacts'))
$destinationPath = Join-Path $artifactPath ('windows-optical-' + [DateTime]::UtcNow.ToString('yyyy-MM-dd'))
$pcmPath = Join-Path $destinationPath ($OutputName + '.pcm')
$reportPath = Join-Path $destinationPath ($OutputName + '-report.json')
if ((Test-Path -LiteralPath $pcmPath) -or (Test-Path -LiteralPath $reportPath)) {
    throw 'Refusing to overwrite capture files. Choose a new OutputName.'
}
$definitionsPath = Join-Path $workspacePath 'android-a34\scripts\windows-cm6206\CoreAudioFormatProbe.cs'
$captureSourcePath = Join-Path $PSScriptRoot 'WindowsSpdifCapture.cs'
Add-Type -Path @($definitionsPath, $captureSourcePath)
# Only the explicitly named, active SPDIF USB Sound Device input is permitted.
# This helper never renders audio and never changes default endpoints or volume.
$result = [Sistema51.Cm6206.WindowsSpdifCapture]::Run($CaptureEndpointId, $pcmPath, $artifactPath, $Seconds)
New-Item -ItemType Directory -Path $destinationPath -Force | Out-Null
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $reportPath -Encoding UTF8
$result | Select-Object Ok, Outcome, FriendlyName, Frames, Bytes, DiscontinuityPackets, StreamMilliseconds, CleanupComplete, Error, PcmFile
Write-Output ('Report: ' + $reportPath)
if (-not $result.Ok) { exit 1 }
