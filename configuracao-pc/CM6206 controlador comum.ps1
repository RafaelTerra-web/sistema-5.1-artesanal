# Read-only discovery of the canonical controller used by shortcuts/remote UI.
# Machine references live in ignored cm6206-local.json, never in this source.
function Resolve-Cm6206ControllerLocations([string]$LocalBaseDir) {
    $localBase=[IO.Path]::GetFullPath($LocalBaseDir)
    $localConfig=Join-Path $localBase 'cm6206-local.json'
    $cfg=$null
    if ([IO.File]::Exists($localConfig)) {
        $cfg=[IO.File]::ReadAllText($localConfig)|ConvertFrom-Json
        if (-not $cfg -or $cfg -is [array]) {throw 'cm6206-local.json precisa conter um objeto de configuracao.'}
    }
    function Resolve-ControllerPath([string]$Value,[string]$Fallback) {
        if (-not $Value) {return [IO.Path]::GetFullPath($Fallback)}
        if ([IO.Path]::IsPathRooted($Value)) {return [IO.Path]::GetFullPath($Value)}
        return [IO.Path]::GetFullPath((Join-Path $localBase $Value))
    }
    $controller=Resolve-ControllerPath $cfg.ControllerPath (Join-Path $localBase '..\scripts\pc-cm6206-system.ps1')
    $controllerBase=if ($cfg.ControllerPath) {
        Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetDirectoryName($controller))) 'configuracao-pc'
    } else {$localBase}
    $config=Resolve-ControllerPath $cfg.ControllerConfigPath (Join-Path $controllerBase 'cm6206-local.json')
    $configBase=[IO.Path]::GetDirectoryName($config)
    return [pscustomobject]@{
        ControllerPath=$controller;ControllerConfigPath=$config
        ControllerStatePath=(Resolve-ControllerPath $cfg.ControllerStatePath (Join-Path $configBase 'cm6206-state.json'))
        ControllerPreferencesPath=(Resolve-ControllerPath $cfg.ControllerPreferencesPath (Join-Path $configBase 'cm6206-panel.json'))
        ControllerActionErrorPath=(Join-Path $configBase 'controle-audio-erro.json')
    }
}

function Test-Cm6206ControllerOwner($State) {
    $runnerId=0
    if (-not [int]::TryParse([string]$State.RunnerId,[ref]$runnerId) -or $runnerId -le 0 -or -not $State.RunnerStartedUtc) {return $false}
    $updated=[DateTimeOffset]::MinValue
    if (-not [DateTimeOffset]::TryParse([string]$State.AtualizadoEm,[ref]$updated)) {return $false}
    $age=([DateTimeOffset]::UtcNow-$updated.ToUniversalTime()).TotalSeconds
    if ($age -lt -2 -or $age -gt 30) {return $false}
    try {
        $runner=Get-Process -Id $runnerId -ErrorAction SilentlyContinue
        return [bool]($runner -and $runner.StartTime.ToUniversalTime().ToString('o') -eq [string]$State.RunnerStartedUtc)
    } catch {return $false}
}

function Read-Cm6206ControllerStatus([string]$StatePath) {
    $defaults=[ordered]@{Estado='Desligado - clique em Ligar';Ligado=$false;Solicitado=$false;Modo='Pcm';InputMode='Auto';Gain=0.1;Muted=$false;RunnerId=0;RunnerStartedUtc='';PlayerId=0;AtualizadoEm=$null;UltimoErro=$null;Perfil='PCM USB';AtrasosMs=$null;RoutedApplications=@();RouteWarnings=@()}
    if (-not [IO.File]::Exists($StatePath)) {return [pscustomobject]$defaults}
    $state=[IO.File]::ReadAllText($StatePath)|ConvertFrom-Json
    if (-not $state -or $state -is [array]) {throw 'O diagnostico CM6206 precisa conter um objeto.'}
    foreach ($name in $defaults.Keys) {if (-not $state.PSObject.Properties[$name]) {$state|Add-Member -NotePropertyName $name -NotePropertyValue $defaults[$name]}}
    if ($state.Ligado -and -not (Test-Cm6206ControllerOwner $state)) {
        $state.Ligado=$false;$state.Estado='Rota sem confirmacao recente - consulte o diagnostico'
        if (-not $state.UltimoErro) {$state.UltimoErro='O processo ou a confirmacao de saida deixou de responder.'}
    }
    $state|Add-Member -NotePropertyName UpmixAutomatico -NotePropertyValue ($state.InputMode -eq 'Auto') -Force
    return $state
}
