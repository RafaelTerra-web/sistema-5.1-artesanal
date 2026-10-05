$ErrorActionPreference = 'Stop'
[Console]::InputEncoding = [Text.UTF8Encoding]::new($false)
[Console]::OutputEncoding = [Text.UTF8Encoding]::new($false)
$audioDir = Split-Path $PSScriptRoot
. (Join-Path $audioDir 'LFE equalizador comum.ps1')
Add-Type -Path (Join-Path $PSScriptRoot 'UniversalRemote.cs')
$script:mediaError = $null
$script:manager = $null
$script:sessionRegistry = @()
$script:audioSnapshot = $null
$script:audioSnapshotAt = [DateTime]::MinValue
try {
    Add-Type -AssemblyName System.Runtime.WindowsRuntime
    $script:managerType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionManager,Windows.Media.Control,ContentType=WindowsRuntime]
    $script:propsType = [Windows.Media.Control.GlobalSystemMediaTransportControlsSessionMediaProperties,Windows.Media.Control,ContentType=WindowsRuntime]
    $script:asTask = [System.WindowsRuntimeSystemExtensions].GetMethods() | Where-Object {
        $_.Name -eq 'AsTask' -and $_.IsGenericMethodDefinition -and $_.GetParameters().Count -eq 1 -and $_.GetParameters()[0].ParameterType.Name -eq 'IAsyncOperation`1'
    } | Select-Object -First 1
} catch { $script:mediaError = $_.Exception.Message }

function Await-Operation($Operation,[type]$ResultType) {
    $task = $script:asTask.MakeGenericMethod($ResultType).Invoke($null,@($Operation))
    if (-not $task.Wait(2000)) { try { $Operation.Cancel() } catch {}; throw 'O aplicativo demorou a responder.' }
    return $task.Result
}
function Get-MediaManager {
    if ($script:mediaError) { throw $script:mediaError }
    if (-not $script:manager) { $script:manager=Await-Operation ($script:managerType::RequestAsync()) $script:managerType }
    return $script:manager
}
function Get-SessionRegistry($manager) {
    $next=@()
    foreach($session in $manager.GetSessions()) {
        $old=$script:sessionRegistry | Where-Object { [object]::ReferenceEquals($_.Session,$session) -or $_.Session.Equals($session) } | Select-Object -First 1
        $key=if($old){$old.Id}else{'media:'+([Guid]::NewGuid().ToString('N'))}
        $next += [pscustomobject]@{Id=$key;Session=$session;Title=if($old){$old.Title}else{''};TitleAt=if($old){$old.TitleAt}else{[DateTime]::MinValue}}
    }
    $script:sessionRegistry=$next
    return @($next)
}
function Get-MediaSessions {
    $manager = Get-MediaManager
    $current = $manager.GetCurrentSession()
    $list = @();$watch=[Diagnostics.Stopwatch]::StartNew()
    foreach ($record in @(Get-SessionRegistry $manager)) {
        $session=$record.Session
        try {
            $info=$session.GetPlaybackInfo(); $timeline=$session.GetTimelineProperties()
            # Keep a slow or closed app from delaying every connected phone.
            if(([DateTime]::UtcNow-$record.TitleAt).TotalSeconds -gt 8 -and $watch.ElapsedMilliseconds -lt 4000) {
                try { $record.Title=(Await-Operation ($session.TryGetMediaPropertiesAsync()) $script:propsType).Title } catch {}
                $record.TitleAt=[DateTime]::UtcNow
            }
            $position=$timeline.Position.TotalSeconds
            if($info.PlaybackStatus.ToString() -eq 'Playing'){$position += [Math]::Max(0,([DateTimeOffset]::UtcNow-$timeline.LastUpdatedTime).TotalSeconds)}
            $duration=[Math]::Max(0,$timeline.EndTime.TotalSeconds)
            if($duration -gt 0){$position=[Math]::Max($timeline.StartTime.TotalSeconds,[Math]::Min($position,$duration))}
            $list += [pscustomobject]@{id=$record.Id;app=$session.SourceAppUserModelId;title=$record.Title;state=$info.PlaybackStatus.ToString();position=[Math]::Round($position,1);duration=[Math]::Round($duration,1);canPlay=$info.Controls.IsPlayEnabled;canPause=$info.Controls.IsPauseEnabled;canSeek=$info.Controls.IsPlaybackPositionEnabled;current=($current -and ([object]::ReferenceEquals($current,$session) -or $current.Equals($session)))}
        } catch { }
    }
    return @($list)
}
function Invoke-MediaAction($request) {
    $manager=Get-MediaManager
    $records=@(Get-SessionRegistry $manager); $session=$null
    if ($request.session) {
        $record=$records | Where-Object Id -EQ $request.session | Select-Object -First 1
        if($record){$session=$record.Session}
    } else { $session=$manager.GetCurrentSession() }
    if (-not $session) { throw 'Abra um video no PC e escolha o aplicativo no controle.' }
    switch ($request.action) {
        'pause' { $op=$session.TryPauseAsync() }
        'play' { $op=$session.TryPlayAsync() }
        'toggle' { $op=$session.TryTogglePlayPauseAsync() }
        'next' { $op=$session.TrySkipNextAsync() }
        'previous' { $op=$session.TrySkipPreviousAsync() }
        'seek' {
            if (-not $session.GetPlaybackInfo().Controls.IsPlaybackPositionEnabled) { throw 'Este aplicativo nao permite buscar pelo controle do Windows.' }
            $timeline=$session.GetTimelineProperties()
            $ticks=$timeline.Position.Ticks + [long]([double]$request.seconds*10000000)
            if ($session.GetPlaybackInfo().PlaybackStatus.ToString() -eq 'Playing') { $ticks += [long](([DateTimeOffset]::UtcNow-$timeline.LastUpdatedTime).TotalSeconds*10000000) }
            $ticks=[Math]::Max($timeline.StartTime.Ticks,[Math]::Min($ticks,$timeline.EndTime.Ticks))
            $op=$session.TryChangePlaybackPositionAsync($ticks)
        }
        default { throw 'Comando de reproducao invalido.' }
    }
    if (-not (Await-Operation $op ([bool]))) { throw 'O aplicativo recusou esse comando.' }
    return @{accepted=$true}
}
function Invoke-AudioScript([string]$Script,[string[]]$Arguments,[int]$TimeoutMs=35000) {
    # A lifecycle script may call exit; a separate process keeps this worker alive.
    $start=[Diagnostics.ProcessStartInfo]::new('powershell.exe')
    $start.Arguments='-NoLogo -NoProfile -ExecutionPolicy Bypass -File "'+(Join-Path $audioDir $Script)+'" '+($Arguments -join ' ')
    $start.UseShellExecute=$false;$start.CreateNoWindow=$true;$start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    try {
        if(-not $process.Start()){throw 'Nao foi possivel iniciar o gerenciamento do audio.'}
        $output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
        if(-not $process.WaitForExit($TimeoutMs)){try{$process.Kill()}catch{};throw 'O gerenciamento do audio demorou a responder. Confira o painel antes de repetir.'}
        $text=$output.Result.Trim()
        if($process.ExitCode -ne 0){throw ('Falha no gerenciamento do audio: '+$errors.Result.Trim())}
        return $text
    } finally {$process.Dispose()}
}
while ($null -ne ($line=[Console]::ReadLine())) {
    $request=$null
    try {
        $request=$line | ConvertFrom-Json
        switch ($request.type) {
            'snapshot' {
                $snapshotWatch=[Diagnostics.Stopwatch]::StartNew()
                $settings=Get-LfeSettings; $sessions=@(); $mediaError=$null
                try { $sessions=@(Get-MediaSessions) } catch { $mediaError=$_.Exception.Message }
                if(-not $script:audioSnapshot -or ([DateTime]::UtcNow-$script:audioSnapshotAt).TotalSeconds -ge 5) {
                    $audioStatus=$null;$audioError=$null
                    try {$audioStatus=(Invoke-AudioScript 'Controle do sistema 5.1.ps1' @('-Acao','Status') 6000) | ConvertFrom-Json} catch {$audioError=$_.Exception.Message}
                    $script:audioSnapshot=@{Status=$audioStatus;Error=$audioError};$script:audioSnapshotAt=[DateTime]::UtcNow
                }
                $result=@{volume=$settings.MasterPercent;muted=$settings.MasterMuted;audioRunning=($script:audioSnapshot.Status -and $script:audioSnapshot.Status.Ligado);audioStatus=$script:audioSnapshot.Status;audioError=$script:audioSnapshot.Error;sessions=$sessions;windows=@([UniversalRemote]::Windows());mediaError=$mediaError;updatedAt=[DateTime]::UtcNow.ToString('o');snapshotElapsedMs=[Math]::Round($snapshotWatch.Elapsed.TotalMilliseconds,1)}
            }
            'volume' { $result=Set-SystemMasterVolume -Percent ([int]$request.percent) -Muted ([bool]$request.muted) }
            'media' { $result=Invoke-MediaAction $request }
            'input' {
                [UniversalRemote]::Command([string]$request.window,[string]$request.action,[string]$request.mode,[string]$request.text,[int]$request.dx,[int]$request.dy)
                $result=@{accepted=$true}
            }
            'netflix' {
                if($request.mode -notin @('app','browser')){throw 'Modo Netflix invalido.'}
                $arguments=if($request.mode -eq 'browser'){@('-Navegador')}else{@()}
                Invoke-AudioScript 'Abrir Netflix 5.1.ps1' $arguments 75000 | Out-Null
                $script:audioSnapshot=$null
                $result=@{opened=$true;mode=$request.mode;dolby51=$true;outputBitrateKbps=640}
            }
            'audio' {
                if($request.action -notin @('Ligar','Desligar','UpmixAuto','Nativo')){throw 'Acao de audio invalida.'}
                $script:audioSnapshot=$null
                $result=(Invoke-AudioScript 'Controle do sistema 5.1.ps1' @('-Acao',$request.action)) | ConvertFrom-Json
            }
            'profile' {
                if($request.profile -notin @('Fidelidade','Estavel')){throw 'Perfil de audio invalido.'}
                $script:audioSnapshot=$null
                $profileStatus=(Invoke-AudioScript 'Ajustar qualidade do audio.ps1' @('-Perfil',$request.profile,'-Json') 75000) | ConvertFrom-Json
                $result=@{accepted=$true;profile=$request.profile;status=$profileStatus}
            }
            default { throw 'Comando invalido.' }
        }
        [Console]::WriteLine((@{id=$request.id;ok=$true;data=$result} | ConvertTo-Json -Depth 8 -Compress))
    } catch { [Console]::WriteLine((@{id=$request.id;ok=$false;error=$_.Exception.Message} | ConvertTo-Json -Depth 5 -Compress)) }
}
