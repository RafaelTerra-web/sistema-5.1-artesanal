[CmdletBinding()]
param(
    [ValidateSet('Start','Stop','Status','Configure')][string]$Action='Status',
    [ValidateSet('Pcm','Optical','Auto')][string]$Mode='Pcm',
    [ValidateSet('Auto','Stereo','Native')][string]$InputMode='Auto',
    [ValidateRange(0,1)][double]$Gain,
    [bool]$Muted,
    [string]$ConfigPath,
    [switch]$Restart,
    [switch]$Json
)
$ErrorActionPreference='Stop'
$root=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if(-not $ConfigPath){$ConfigPath=Join-Path $root 'configuracao-pc/cm6206-local.json'}
if(Test-Path -LiteralPath $ConfigPath -PathType Leaf){
    $aliasConfig=[IO.File]::ReadAllText($ConfigPath)|ConvertFrom-Json
    if($aliasConfig.ControllerPath -and [IO.Path]::GetFullPath($aliasConfig.ControllerPath) -ne [IO.Path]::GetFullPath($PSCommandPath)){
        if(-not(Test-Path -LiteralPath $aliasConfig.ControllerPath -PathType Leaf)){throw 'Controlador canônico ausente.'}
        $forward=@{};foreach($key in $PSBoundParameters.Keys){$forward[$key]=$PSBoundParameters[$key]}
        if($aliasConfig.ControllerConfigPath){$forward.ConfigPath=$aliasConfig.ControllerConfigPath}
        & $aliasConfig.ControllerPath @forward
        return
    }
}
$statePath=Join-Path $root 'configuracao-pc/cm6206-state.json'
function Read-State {
    $defaults=[ordered]@{Estado='Desligado';Ligado=$false;Solicitado=$false;Modo='Pcm';InputMode='Auto';Gain=0.1;Muted=$false;RunnerId=0;RunnerStartedUtc='';StopPath='';UltimoErro='';AtualizadoEm=[DateTimeOffset]::UtcNow.ToString('o')}
    try {$state=[IO.File]::ReadAllText($statePath)|ConvertFrom-Json} catch {$state=$null}
    if(-not $state){return [pscustomobject]$defaults}
    foreach($key in $defaults.Keys){if(-not $state.PSObject.Properties[$key]){$state|Add-Member -NotePropertyName $key -NotePropertyValue $defaults[$key]}}
    return $state
}
function State-OwnerAlive($state) {
    if(-not $state.RunnerId -or -not $state.RunnerStartedUtc){return $false}
    $process=Get-Process -Id ([int]$state.RunnerId) -ErrorAction SilentlyContinue
    try {return ($process -and $process.StartTime.ToUniversalTime().ToString('o') -eq $state.RunnerStartedUtc)} catch {return $false}
}
function State-Fresh($state) {
    try {$age=([DateTimeOffset]::UtcNow-[DateTimeOffset]::Parse($state.AtualizadoEm)).TotalSeconds;return ($age -ge -2 -and $age -le 30)} catch {return $false}
}
function Emit-State($state) { $state|ConvertTo-Json -Depth 9 -Compress }
function Save-Json($path,$value) {
    $temporary=$path+'.tmp'
    [IO.File]::WriteAllText($temporary,($value|ConvertTo-Json -Depth 9),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $temporary -Destination $path -Force
}
function Stop-Session($state) {
    if(State-OwnerAlive $state){
        if(-not $state.StopPath){throw 'Caminho de parada ausente.'}
        [IO.File]::WriteAllText($state.StopPath,'stop')
        $deadline=[DateTime]::UtcNow.AddSeconds(30)
        while((State-OwnerAlive $state) -and [DateTime]::UtcNow -lt $deadline){Start-Sleep -Milliseconds 200}
        if(State-OwnerAlive $state){throw 'A sessão ainda está encerrando; não foi iniciado outro renderer.'}
    }
}
if($Action -eq 'Status') {
    $state=Read-State
    if($state.Ligado -and (-not(State-OwnerAlive $state) -or -not(State-Fresh $state))){
        $state.Ligado=$false;$state.Estado='Falha';$state.UltimoErro='Estado antigo ou processo ausente.'
    }
    Emit-State $state;return
}
$mutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalCM6206SystemControl')
$locked=$false
try {
    $locked=$mutex.WaitOne(5000)
    if(-not $locked){throw 'Outra ação do sistema ainda está em execução.'}
    $state=Read-State
    if($Action -eq 'Stop') {
        [IO.File]::WriteAllText((Join-Path $root 'configuracao-pc/audio-sistema.desligado'),'off')
        Stop-Session $state;Emit-State (Read-State);return
    }
    if(-not(Test-Path -LiteralPath $ConfigPath)){throw 'Configure cm6206-local.json com os caminhos e endpoints desta máquina.'}
    $cfg=[IO.File]::ReadAllText($ConfigPath)|ConvertFrom-Json
    if($PSBoundParameters.ContainsKey('Gain')){$cfg.Gain=$Gain}
    if($PSBoundParameters.ContainsKey('Muted')){$cfg.Muted=$Muted}
    if($Action -eq 'Configure') {
        Save-Json $ConfigPath $cfg
        if(-not(State-OwnerAlive $state)){
            $state.Gain=$cfg.Gain;$state.Muted=[bool]$cfg.Muted;$state.Ligado=$false;$state.Solicitado=$false;$state.Estado='Desligado'
            $state|Add-Member -NotePropertyName ConfigurationApplied -NotePropertyValue $false -Force
            Save-Json $statePath $state;Emit-State $state;return
        }
        $deadline=[DateTime]::UtcNow.AddSeconds(4)
        do {$state=Read-State;if(-not(State-OwnerAlive $state)){break};if($state.Gain -eq $cfg.Gain -and $state.Muted -eq $cfg.Muted){break};Start-Sleep -Milliseconds 100} while([DateTime]::UtcNow -lt $deadline)
        $applied=($state.Gain -eq $cfg.Gain -and $state.Muted -eq $cfg.Muted -and $state.Ligado -and (State-Fresh $state))
        $state|Add-Member -NotePropertyName ConfigurationApplied -NotePropertyValue ([bool]$applied) -Force
        Emit-State $state;return
    }
    foreach($key in @('MpvPath','PythonPath','SoundVolumeViewPath')){if(-not(Test-Path -LiteralPath $cfg.$key -PathType Leaf)){throw ('Executável ausente: '+$key)}}
    foreach($key in @('CaptureEndpointId','RenderEndpointId')){if($cfg.$key -notmatch '^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$'){throw ('Endpoint render inválido: '+$key)}}
    if(-not $cfg.PSObject.Properties['Gain'] -or $cfg.Gain -lt 0 -or $cfg.Gain -gt 1){throw 'Ganho linear inválido.'}
    $resolved=if($Mode -eq 'Auto'){'Pcm'}else{$Mode}
    Save-Json (Join-Path $root 'configuracao-pc/cm6206-panel.json') ([ordered]@{Mode=$resolved;InputMode=$InputMode})
    if(-not $Restart -and (State-OwnerAlive $state) -and (State-Fresh $state) -and $state.Estado -ne 'Falha' -and $state.Modo -eq $resolved -and $state.InputMode -eq $InputMode){Save-Json $ConfigPath $cfg;Emit-State $state;return}
    Stop-Session $state
    Save-Json $ConfigPath $cfg
    $off=Join-Path $root 'configuracao-pc/audio-sistema.desligado'
    if(Test-Path -LiteralPath $off){Remove-Item -LiteralPath $off}
    $run=Join-Path $root ('android-a34/artifacts/cm6206-system/'+[Guid]::NewGuid().ToString('N'))
    New-Item -ItemType Directory -Path $run -Force|Out-Null
    $pipe='\\.\pipe\SistemaArtesanalCM6206_'+(Split-Path -Leaf $run)
    $initial=[ordered]@{Estado='Inicializando';Ligado=$false;Solicitado=$true;Modo=$resolved;InputMode=$InputMode;Gain=$cfg.Gain;Muted=[bool]$cfg.Muted;StopPath=(Join-Path $run 'system.stop');RunDirectory=$run;IpcPath=$pipe;UltimoErro='';Perfil='PCM';AtrasosMs=@{};AtualizadoEm=[DateTimeOffset]::UtcNow.ToString('o')}
    $launch=Join-Path $run 'launch.json'
    Save-Json $launch ([ordered]@{ConfigPath=[IO.Path]::GetFullPath($ConfigPath);StatePath=$statePath;RunDirectory=$run;IpcPath=$pipe;InitialState=$initial})
    $scriptPath=Join-Path $PSScriptRoot 'pc-cm6206-engine.py'
    $process=Start-Process -FilePath $cfg.PythonPath -ArgumentList @(('"'+$scriptPath+'"'),'--launch',('"'+$launch+'"')) -WindowStyle Hidden -RedirectStandardOutput (Join-Path $run 'engine-console.txt') -RedirectStandardError (Join-Path $run 'engine-errors.txt') -PassThru
    $owner=[ordered]@{RunnerId=$process.Id;RunnerStartedUtc=$process.StartTime.ToUniversalTime().ToString('o')}
    Save-Json (Join-Path $run 'owner.json') $owner
    # Publish ownership before the worker can fail during its first import.
    if(-not(Test-Path -LiteralPath $statePath) -or (Read-State).RunnerId -ne $process.Id){
        foreach($key in $owner.Keys){$initial[$key]=$owner[$key]};Save-Json $statePath $initial
    }
    $deadline=[DateTime]::UtcNow.AddSeconds(27)
    do {
        Start-Sleep -Milliseconds 250
        $state=Read-State
        if($state.RunnerId -eq $process.Id -and ($state.Ligado -or $state.Estado -eq 'Falha')){break}
        $process.Refresh();if($process.HasExited){break}
    } while([DateTime]::UtcNow -lt $deadline)
    Emit-State (Read-State)
} finally {
    if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()
}
