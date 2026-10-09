# Compile and test offline in Windows PowerShell 5.1 (.NET Framework).
# No audio endpoint or player process is opened.
$ErrorActionPreference = 'Stop'
Add-Type -Path @(
    (Join-Path $PSScriptRoot 'StereoUpmix.cs'),
    (Join-Path $PSScriptRoot 'RelayLoopback.cs'),
    (Join-Path $PSScriptRoot 'RelayLoopbackLowLatency.cs'),
    (Join-Path $PSScriptRoot 'testar-upmix-metadata.cs'),
    (Join-Path $PSScriptRoot 'testar-relay-pool.cs')
)
[StereoUpmixMetadataTests]::Run()
[RelayPoolOfflineTests]::Run()
