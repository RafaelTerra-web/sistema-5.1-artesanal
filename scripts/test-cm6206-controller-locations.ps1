# Read-only canonical discovery against temporary folders, no manager execution.
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot '..\configuracao-pc\CM6206 controlador comum.ps1')
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ('sistema51-locations-'+[Guid]::NewGuid().ToString('N'))
$bench=Join-Path $testRoot 'bench\configuracao-pc'
$canonical=Join-Path $testRoot 'canonical'
$localConfig=Join-Path $bench 'cm6206-local.json'
$statePath=Join-Path $testRoot 'controller-state.json'
try {
    [IO.Directory]::CreateDirectory($bench)|Out-Null
    $defaults=Resolve-Cm6206ControllerLocations $bench
    if ($defaults.ControllerPath -ne [IO.Path]::GetFullPath((Join-Path $bench '..\scripts\pc-cm6206-system.ps1')) -or
        $defaults.ControllerConfigPath -ne $localConfig -or $defaults.ControllerStatePath -ne (Join-Path $bench 'cm6206-state.json')) {throw 'Standalone paths changed without references.'}
    $managerPath=Join-Path $canonical 'scripts\pc-cm6206-system.ps1'
    [IO.File]::WriteAllText($localConfig,(@{ControllerPath=$managerPath}|ConvertTo-Json))
    $inferred=Resolve-Cm6206ControllerLocations $bench
    $canonicalConfigBase=Join-Path $canonical 'configuracao-pc'
    if ($inferred.ControllerConfigPath -ne (Join-Path $canonicalConfigBase 'cm6206-local.json') -or
        $inferred.ControllerPreferencesPath -ne (Join-Path $canonicalConfigBase 'cm6206-panel.json')) {throw 'Controller-only reference did not infer canonical state/config/preferences.'}
    $explicit=@{ControllerPath='..\..\canonical\scripts\pc-cm6206-system.ps1';ControllerConfigPath='..\..\canonical\private\settings.json';ControllerStatePath=$statePath;ControllerPreferencesPath='..\..\canonical\private\ui.json'}
    [IO.File]::WriteAllText($localConfig,($explicit|ConvertTo-Json))
    $resolved=Resolve-Cm6206ControllerLocations $bench
    if ($resolved.ControllerPath -ne $managerPath -or $resolved.ControllerStatePath -ne $statePath -or
        $resolved.ControllerConfigPath -ne (Join-Path $canonical 'private\settings.json') -or
        $resolved.ControllerPreferencesPath -ne (Join-Path $canonical 'private\ui.json')) {throw 'Explicit/relative canonical references did not resolve.'}
    $state=@{Estado='Ligado';Ligado=$true;Solicitado=$true;Modo='Pcm';InputMode='Native';Gain=0.3;RunnerId=$PID;RunnerStartedUtc=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o');AtualizadoEm=[DateTimeOffset]::UtcNow.ToString('o')}
    [IO.File]::WriteAllText($statePath,($state|ConvertTo-Json))
    $status=Read-Cm6206ControllerStatus $resolved.ControllerStatePath
    if (-not $status.Ligado -or $status.InputMode -ne 'Native' -or $status.Gain -ne 0.3) {throw 'Canonical state was not read.'}
    $state.AtualizadoEm=[DateTimeOffset]::UtcNow.AddMinutes(-1).ToString('o')
    [IO.File]::WriteAllText($statePath,($state|ConvertTo-Json))
    if ((Read-Cm6206ControllerStatus $resolved.ControllerStatePath).Ligado) {throw 'Canonical stale state was advertised as active.'}
} finally {
    foreach ($file in @($statePath,$localConfig)) {if ([IO.File]::Exists($file)) {[IO.File]::Delete($file)}}
    foreach ($directory in @($bench,(Split-Path $bench),$testRoot)) {if ([IO.Directory]::Exists($directory)) {[IO.Directory]::Delete($directory)}}
}
Write-Output 'Passed: standalone defaults, canonical inference, explicit/relative references, fresh/stale canonical state. No manager/audio/hardware changed.'
