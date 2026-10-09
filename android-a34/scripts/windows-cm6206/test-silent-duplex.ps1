[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\hardware-2026-10-08\windows-audio')
)
$ErrorActionPreference = 'Stop'
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
$formatReportPath = Join-Path $outputPath 'coreaudio-formats.json'
if (-not (Test-Path -LiteralPath $formatReportPath)) {
    throw "Run probe-formats.ps1 first; no format report exists at $formatReportPath"
}
$formatReport = Get-Content -LiteralPath $formatReportPath -Raw | ConvertFrom-Json
$render = @($formatReport.Endpoints | Where-Object {
    $_.IsCm6206Candidate -and $_.Flow -eq 'render' -and
    $_.MatchReason -eq 'Endpoint property directly references VID_0D8C&PID_0102.'
})
$capture = @($formatReport.Endpoints | Where-Object {
    $_.IsCm6206Candidate -and $_.Flow -eq 'capture' -and $_.FriendlyName -match 'SPDIF' -and
    $_.MatchReason -eq 'Endpoint property directly references VID_0D8C&PID_0102.'
})
if ($render.Count -ne 1 -or $capture.Count -ne 1) {
    throw 'Exactly one CM6206 render endpoint and one identified SPDIF capture endpoint are required.'
}
if ('Sistema51.Cm6206.FormatProbe' -as [type]) {
    throw 'Run this script in a fresh STA PowerShell process; it compiles CoreAudio and duplex types together.'
}
Add-Type -Path @((Join-Path $PSScriptRoot 'CoreAudioFormatProbe.cs'),
    (Join-Path $PSScriptRoot 'SilentDuplexProbe.cs'))
$result = [Sistema51.Cm6206.SilentDuplexProbe]::Run($render[0].Id, $capture[0].Id)
$jsonPath = Join-Path $outputPath 'silent-duplex.json'
$result | ConvertTo-Json -Depth 8 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$result | Select-Object Outcome, Error, RenderEndpointId, CaptureEndpointId,
    RenderBufferFrames, CaptureBufferFrames, RenderFramesWritten, RenderFramesConsumedEstimate,
    CaptureFramesReturned, CaptureFramesReleased, CapturePackets, CaptureFlags,
    CaptureDiscontinuityPackets, StreamElapsedMilliseconds, TotalElapsedMilliseconds,
    MaximumWriteGapMilliseconds, MaximumWriteCallMilliseconds, MaximumPollGapMilliseconds,
    OnlyZeroSamplesWritten, CleanupComplete | Format-List
$result.Calls | Format-Table -AutoSize
Write-Output "Report: $jsonPath"
if ($result.Outcome -ne 'duplex_api_streams_passed' -or -not $result.CleanupComplete) { exit 1 }
