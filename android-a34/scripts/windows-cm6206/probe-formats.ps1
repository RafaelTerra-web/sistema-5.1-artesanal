[CmdletBinding()]
param(
    [string]$OutputDirectory = (Join-Path $PSScriptRoot '..\..\artifacts\hardware-2026-10-08\windows-audio')
)
$ErrorActionPreference = 'Stop'
$outputPath = [System.IO.Path]::GetFullPath($OutputDirectory)
New-Item -ItemType Directory -Path $outputPath -Force | Out-Null

if (-not ('Sistema51.Cm6206.FormatProbe' -as [type])) {
    Add-Type -Path (Join-Path $PSScriptRoot 'CoreAudioFormatProbe.cs')
}
$endpoints = @([Sistema51.Cm6206.FormatProbe]::Run())
$pnpDevices = @(Get-PnpDevice -PresentOnly | Where-Object {
    $_.InstanceId -like '*VID_0D8C&PID_0102*' -or
    ($_.Class -eq 'AudioEndpoint' -and $_.FriendlyName -like '*USB Sound Device*')
} | ForEach-Object {
    $device = $_
    $properties = @(Get-PnpDeviceProperty -InstanceId $device.InstanceId -ErrorAction SilentlyContinue |
        Where-Object { $_.KeyName -match 'Parent|ContainerId|HardwareIds|CompatibleIds|Driver|Service|Location|ProblemCode' } |
        Select-Object KeyName, Type, Data)
    [pscustomobject]@{
        Status = $device.Status
        Class = $device.Class
        FriendlyName = $device.FriendlyName
        InstanceId = $device.InstanceId
        Properties = $properties
    }
})
$drivers = @(Get-CimInstance Win32_PnPSignedDriver | Where-Object {
    $_.DeviceID -like '*VID_0D8C&PID_0102*'
} | Select-Object DeviceID, DeviceName, DriverProviderName, Manufacturer, InfName,
    DriverVersion, DriverDate, IsSigned, Signer)
$report = [pscustomobject]@{
    ProbeVersion = 1
    CapturedAtUtc = [DateTime]::UtcNow.ToString('o')
    LocalDateForArtifacts = '2026-10-08'
    Machine = $env:COMPUTERNAME
    ProcessBitness = [IntPtr]::Size * 8
    Mode = 'read_only_format_discovery'
    Operations = @('EnumAudioEndpoints active render/capture', 'OpenPropertyStore STGM_READ',
        'Activate IAudioClient', 'GetMixFormat', 'GetDevicePeriod', 'IsFormatSupported')
    StreamStarted = $false
    DefaultEndpointChanged = $false
    Limitations = @(
        'IsFormatSupported reports API/driver format acceptance, not physical channel routing.',
        'Generic eight-slot PCM acceptance does not establish eight physical analog outputs on a six-output codec.',
        'No optical source or amplifiers are attached; SPDIF lock, encoded capture, channel mapping and latency remain untested.',
        'No capture, playback, Initialize or Start operation is performed.'
    )
    Endpoints = $endpoints
    PnpDevices = $pnpDevices
    NativeDriverInfo = $drivers
}
$jsonPath = Join-Path $outputPath 'coreaudio-formats.json'
$report | ConvertTo-Json -Depth 12 | Set-Content -LiteralPath $jsonPath -Encoding UTF8
$endpoints | Where-Object IsCm6206Candidate | ForEach-Object {
    [pscustomobject]@{
        FriendlyName = $_.FriendlyName
        Flow = $_.Flow
        Id = $_.Id
        Mix = if ($_.MixFormat) { '{0}ch/{1}Hz/{2}bit/{3}/{4}' -f $_.MixFormat.Channels,
            $_.MixFormat.SampleRate, $_.MixFormat.BitsPerSample, $_.MixFormat.Encoding,
            $_.MixFormat.ChannelMask } else { 'unavailable' }
        Exact48kPcm16 = @($_.Queries | Where-Object Result -eq 'supported_exactly' | ForEach-Object {
            '{0}:{1}ch:{2}:tag{3}' -f $_.ShareMode, $_.Requested.Channels,
                $_.Requested.ChannelMask, $_.Requested.Tag
        }) -join ', '
        Errors = $_.Errors -join '; '
    }
} | Format-List
Write-Output "Report: $jsonPath"
