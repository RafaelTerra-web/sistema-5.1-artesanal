# Run the real manager against isolated state/config files. No Start/Stop,
# executable, endpoint, HID, audio stream or live system file is touched.
$ErrorActionPreference='Stop'
$managerSource=Join-Path $PSScriptRoot 'pc-cm6206-system.ps1'
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('sistema51-state-'+[Guid]::NewGuid().ToString('N'))
$scriptDir=Join-Path $testRoot 'scripts'
$configDir=Join-Path $testRoot 'configuracao-pc'
$manager=Join-Path $scriptDir 'pc-cm6206-system.ps1'
$statePath=Join-Path $configDir 'cm6206-state.json'
$configPath=Join-Path $configDir 'cm6206-local.json'
function Write-TestJson([string]$Path,$Value) {[IO.File]::WriteAllText($Path,($Value|ConvertTo-Json -Depth 9))}
function Read-TestStatus {return (& $manager -Action Status|ConvertFrom-Json)}
try {
    [IO.Directory]::CreateDirectory($scriptDir)|Out-Null
    [IO.Directory]::CreateDirectory($configDir)|Out-Null
    # Isolate even the control lock from a real session being configured nearby.
    $source=[IO.File]::ReadAllText($managerSource).Replace('Local\SistemaArtesanalCM6206SystemControl','Local\Sistema51StateTest_'+[Guid]::NewGuid().ToString('N'))
    [IO.File]::WriteAllText($manager,$source,[Text.UTF8Encoding]::new($true))
    $state=@{Estado='Em execução';Ligado=$true;Solicitado=$true;Modo='Pcm';InputMode='Native';RunnerId=$PID;RunnerStartedUtc=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o');AtualizadoEm=[DateTime]::UtcNow.ToString('o');Gain=0.1;Muted=$false;UltimoErro=''}
    Write-TestJson $statePath $state
    if (-not (Read-TestStatus).Ligado) {throw 'Matching fresh owner was rejected.'}
    $state.RunnerStartedUtc=[DateTime]::UtcNow.ToString('o');Write-TestJson $statePath $state
    if ((Read-TestStatus).Ligado) {throw 'Recycled process ID advertised ready.'}
    $state.RunnerStartedUtc=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
    $state.AtualizadoEm=[DateTime]::UtcNow.AddMinutes(-1).ToString('o');Write-TestJson $statePath $state
    if ((Read-TestStatus).Ligado) {throw 'Stale heartbeat advertised ready.'}
    $state.AtualizadoEm=[DateTime]::UtcNow.AddMinutes(1).ToString('o');Write-TestJson $statePath $state
    if ((Read-TestStatus).Ligado) {throw 'Future heartbeat advertised ready.'}
    $state.AtualizadoEm='invalid-time';Write-TestJson $statePath $state
    if ((Read-TestStatus).Ligado) {throw 'Malformed heartbeat advertised ready.'}
    $state.Ligado=$false;$state.Solicitado=$false;$state.RunnerId=0;$state.AtualizadoEm=[DateTime]::UtcNow.ToString('o');Write-TestJson $statePath $state
    Write-TestJson $configPath @{Gain=0.1;Muted=$false}
    foreach ($case in @(@{Gain=0.42;Muted=$true},@{Gain=0.17;Muted=$false})) {
        $configured=(& $manager -Action Configure -Gain $case.Gain -Muted $case.Muted|ConvertFrom-Json)
        $saved=[IO.File]::ReadAllText($configPath)|ConvertFrom-Json
        if ([Math]::Abs($saved.Gain-$case.Gain) -gt 1e-9 -or $saved.Muted -ne $case.Muted) {throw 'Configure did not persist typed gain/mute.'}
        if ([Math]::Abs($configured.Gain-$case.Gain) -gt 1e-9 -or $configured.Muted -ne $case.Muted -or $configured.Ligado) {throw 'Configure while stopped returned stale controls or advertised playback.'}
    }
} finally {
    foreach ($file in @($manager,$statePath,$configPath,($configPath+'.tmp'),($statePath+'.tmp'))) {if ([IO.File]::Exists($file)) {[IO.File]::Delete($file)}}
    foreach ($directory in @($scriptDir,$configDir,$testRoot)) {if ([IO.Directory]::Exists($directory)) {[IO.Directory]::Delete($directory)}}
}
Write-Output 'Passed: fresh/stale/recycled/future/malformed status, stopped Configure typed gain/mute. No audio or hardware changed.'
