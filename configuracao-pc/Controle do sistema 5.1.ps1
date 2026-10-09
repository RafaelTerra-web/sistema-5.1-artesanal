param(
    [ValidateSet('Painel','Ligar','Desligar','Status','UpmixAuto','Stereo','Nativo','Biblioteca')][string]$Acao='Painel',
    [ValidateSet('Pcm','Optical','Auto')][string]$Mode='Pcm',
    [ValidateSet('Auto','Stereo','Native')][string]$InputMode='Auto'
)
$ErrorActionPreference='Stop'
. (Join-Path $PSScriptRoot 'Audio gerenciamento comum.ps1')
. (Join-Path $PSScriptRoot 'CM6206 controlador comum.ps1')
$script:baseDir=$PSScriptRoot
$locations=Resolve-Cm6206ControllerLocations $PSScriptRoot
$script:systemPath=$locations.ControllerPath
$script:systemConfigPath=$locations.ControllerConfigPath
$script:systemStatePath=$locations.ControllerStatePath
$script:preferencePath=$locations.ControllerPreferencesPath
$script:actionErrorPath=$locations.ControllerActionErrorPath

function Get-AudioPreferences {
    $preferences=[pscustomobject]@{Mode='Pcm';InputMode='Auto'}
    if ([IO.File]::Exists($script:preferencePath)) {
        try {
            $saved=[IO.File]::ReadAllText($script:preferencePath)|ConvertFrom-Json
            if ($saved.Mode -in @('Pcm','Optical','Auto')) {$preferences.Mode=$saved.Mode}
            if ($saved.InputMode -in @('Auto','Stereo','Native')) {$preferences.InputMode=$saved.InputMode}
        } catch { }
    }
    # The active canonical session wins over an old UI preference. This keeps
    # a CLI Native start from becoming Stereo when a second shortcut is opened.
    try {
        $active=Read-Cm6206ControllerStatus $script:systemStatePath
        if (($active.Ligado -or $active.Solicitado) -and (Test-Cm6206ControllerOwner $active)) {
            if ($active.Modo -in @('Pcm','Optical','Auto')) {$preferences.Mode=$active.Modo}
            if ($active.InputMode -in @('Auto','Stereo','Native')) {$preferences.InputMode=$active.InputMode}
        }
    } catch { }
    return $preferences
}

function Save-AudioPreferences([string]$SelectedMode,[string]$SelectedInputMode) {
    $preferences=[ordered]@{Mode=$SelectedMode;InputMode=$SelectedInputMode}
    Set-AudioFileTransaction @((New-AudioTextFile $script:preferencePath ($preferences|ConvertTo-Json -Compress)))
}

function Invoke-AudioSystem([string]$Action,[string]$SelectedMode,[string]$SelectedInputMode) {
    if (-not [IO.File]::Exists($script:systemPath)) {throw 'Gerenciador CM6206 ausente: scripts/pc-cm6206-system.ps1.'}
    # The manager owns routing, readiness and restoration. The panel never
    # changes endpoint formats, volumes, HID or defaults itself.
    $start=[Diagnostics.ProcessStartInfo]::new()
    $start.FileName='powershell.exe';$start.UseShellExecute=$false;$start.CreateNoWindow=$true
    $start.RedirectStandardOutput=$true;$start.RedirectStandardError=$true
    $start.Arguments='-NoLogo -NoProfile -ExecutionPolicy Bypass -File "'+$script:systemPath+'" -ConfigPath "'+$script:systemConfigPath+'" -Action '+$Action+' -Mode '+$SelectedMode+' -InputMode '+$SelectedInputMode
    $process=[Diagnostics.Process]::new();$process.StartInfo=$start
    try {
        if (-not $process.Start()) {throw 'Nao foi possivel iniciar o gerenciador CM6206.'}
        $output=$process.StandardOutput.ReadToEndAsync();$errors=$process.StandardError.ReadToEndAsync()
        if (-not $process.WaitForExit(40000)) {
            try {$process.Kill()} catch { }
            throw 'O gerenciador demorou a responder. Consulte o estado antes de repetir.'
        }
        if ($process.ExitCode -ne 0) {throw ('Falha na rota de audio: '+$errors.Result.Trim())}
        $result=$output.Result.Trim()
        if (-not $result) {throw 'O gerenciador nao retornou o estado de audio.'}
        return ($result|ConvertFrom-Json)
    } finally {$process.Dispose()}
}

function Get-AudioStatus {
    $preferences=Get-AudioPreferences
    $status=[pscustomobject]@{
        Estado='Desligado - clique em Ligar';Ligado=$false;Solicitado=$false
        Modo=$preferences.Mode;InputMode=$preferences.InputMode;RunnerId=$null;PlayerId=$null
        UltimoErro=$null;AtualizadoEm=$null;Perfil='PCM USB';AtrasosMs=$null;Gain=$null;Muted=$null
        RoutedApplications=@();RouteWarnings=@()
        UpmixAutomatico=($preferences.InputMode -eq 'Auto')
    }
    if ([IO.File]::Exists($script:systemStatePath)) {
        try {
            $saved=Read-Cm6206ControllerStatus $script:systemStatePath
            foreach ($name in @('Estado','Ligado','Solicitado','Modo','InputMode','RunnerId','PlayerId','UltimoErro','AtualizadoEm','Perfil','AtrasosMs','Gain','Muted','RoutedApplications','RouteWarnings')) {
                if ($saved.PSObject.Properties[$name]) {$status.$name=$saved.$name}
            }
            # Ligado is authored only after fresh WASAPI output/frames. Reject
            # old success files, a stopped worker and recycled process IDs.
            if ($status.Ligado) {
                $updated=[DateTimeOffset]::MinValue
                $fresh=[DateTimeOffset]::TryParse([string]$status.AtualizadoEm,[ref]$updated) -and
                    ([DateTimeOffset]::UtcNow-$updated.ToUniversalTime()).TotalSeconds -ge -5 -and
                    ([DateTimeOffset]::UtcNow-$updated.ToUniversalTime()).TotalSeconds -le 30
                $runner=if ($status.RunnerId -match '^\d+$') {Get-Process -Id ([int]$status.RunnerId) -ErrorAction SilentlyContinue} else {$null}
                $identity=$runner -and $saved.PSObject.Properties['RunnerStartedUtc'] -and
                    $runner.StartTime.ToUniversalTime().ToString('o') -eq [string]$saved.RunnerStartedUtc
                if (-not $fresh -or -not $identity) {
                    $status.Ligado=$false;$status.Estado='Rota sem confirmacao recente - consulte o diagnostico'
                    if (-not $status.UltimoErro) {$status.UltimoErro='O processo ou a confirmacao de saida deixou de responder.'}
                }
            }
            $status.UpmixAutomatico=$status.InputMode -eq 'Auto'
        } catch {
            $status.Ligado=$false;$status.Estado='Estado de audio temporariamente indisponivel'
            $status.UltimoErro='Nao foi possivel ler o diagnostico do gerenciador.'
        }
    }
    if ([IO.File]::Exists($script:actionErrorPath)) {
        try {$status.UltimoErro=([IO.File]::ReadAllText($script:actionErrorPath)|ConvertFrom-Json).Mensagem}
        catch {$status.UltimoErro='Nao foi possivel ler o erro da ultima troca de audio.'}
    }
    return $status
}

function Start-AudioSystem([string]$SelectedMode,[string]$SelectedInputMode) {
    $preferences=Get-AudioPreferences
    if ($SelectedMode) {$preferences.Mode=$SelectedMode}
    if ($SelectedInputMode) {$preferences.InputMode=$SelectedInputMode}
    return Invoke-AudioSystem 'Start' $preferences.Mode $preferences.InputMode
}

function Stop-AudioSystem {
    $preferences=Get-AudioPreferences
    return Invoke-AudioSystem 'Stop' $preferences.Mode $preferences.InputMode
}

function Get-AudioDelayText($Status) {
    $delays=$Status.AtrasosMs
    $values=@{}
    foreach ($channel in @('FL','FR','CEN','LFE','SL','SR')) {
        if (-not $delays -or -not $delays.PSObject.Properties[$channel] -or $null -eq $delays.$channel) {
            return 'Atrasos: aguardando valores confirmados nesta rota.'
        }
        try {$number=[double]$delays.$channel} catch {return 'Atrasos: aguardando valores confirmados nesta rota.'}
        if ([double]::IsNaN($number) -or [double]::IsInfinity($number) -or $number -lt 0) {
            return 'Atrasos: aguardando valores confirmados nesta rota.'
        }
        $values[$channel]=$number.ToString('0.0',[Globalization.CultureInfo]::GetCultureInfo('pt-BR'))
    }
    $front=if ($values.FL -eq $values.FR) {$values.FL} else {$values.FL+'/'+$values.FR}
    $surround=if ($values.SL -eq $values.SR) {$values.SL} else {$values.SL+'/'+$values.SR}
    $prefix=if ($Status.Ligado) {'Atrasos aplicados'} else {'Atrasos configurados'}
    return ($prefix+': FL/FR '+$front+' ms; CEN '+$values.CEN+' ms; LFE '+$values.LFE+' ms; SL/SR '+$surround+' ms.')
}

if ($Acao -eq 'Biblioteca') {return}
if ($Acao -eq 'Status') {Get-AudioStatus|ConvertTo-Json -Depth 8 -Compress;exit}
if ($Acao -ne 'Painel') {
    $controlMutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalAudio51Controle')
    $locked=$false
    try {
        $locked=Wait-AudioMutex $controlMutex 10000
        if (-not $locked) {throw 'Outra mudanca de audio esta em andamento.'}
        Remove-Item -LiteralPath $script:actionErrorPath -ErrorAction SilentlyContinue
        $preferences=Get-AudioPreferences
        if ($PSBoundParameters.ContainsKey('Mode')) {$preferences.Mode=$Mode}
        if ($PSBoundParameters.ContainsKey('InputMode')) {$preferences.InputMode=$InputMode}
        if ($Acao -eq 'UpmixAuto') {$preferences.InputMode='Auto'}
        elseif ($Acao -eq 'Nativo') {$preferences.InputMode='Native'}
        elseif ($Acao -eq 'Stereo') {$preferences.InputMode='Stereo'}
        Save-AudioPreferences $preferences.Mode $preferences.InputMode
        if ($Acao -eq 'Desligar') {$status=Stop-AudioSystem}
        elseif ($Acao -eq 'Ligar') {$status=Start-AudioSystem $preferences.Mode $preferences.InputMode}
        else {
            $status=Get-AudioStatus
            if ($status.Solicitado -or $status.Ligado) {$status=Start-AudioSystem $preferences.Mode $preferences.InputMode}
            else {$status.InputMode=$preferences.InputMode;$status.Modo=$preferences.Mode;$status.UpmixAutomatico=$preferences.InputMode -eq 'Auto'}
        }
        $status|ConvertTo-Json -Depth 8 -Compress
    } catch {
        $failure=$_
        if ($locked) {
            try {
                $errorRecord=@{Acao=$Acao;Mensagem=$failure.Exception.Message;AtualizadoEm=[DateTime]::UtcNow.ToString('o')}
                Set-AudioFileTransaction @((New-AudioTextFile $script:actionErrorPath ($errorRecord|ConvertTo-Json -Compress)))
            } catch { }
        }
        throw $failure
    } finally {if ($locked) {$controlMutex.ReleaseMutex()};$controlMutex.Dispose()}
    exit
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$form=[Windows.Forms.Form]::new();$form.Text='Sistema de audio 5.1'
$form.ClientSize=[Drawing.Size]::new(600,420);$form.StartPosition='CenterScreen'
$form.FormBorderStyle='FixedDialog';$form.MaximizeBox=$false;$form.Font=[Drawing.Font]::new('Segoe UI',11)
$title=[Windows.Forms.Label]::new();$title.Text='Sistema de audio 5.1'
$title.Font=[Drawing.Font]::new('Segoe UI',20,[Drawing.FontStyle]::Bold);$title.SetBounds(24,20,555,42)
$stateLabel=[Windows.Forms.Label]::new();$stateLabel.SetBounds(26,75,550,48)
$stateLabel.Font=[Drawing.Font]::new('Segoe UI',12,[Drawing.FontStyle]::Bold)
$routeLabel=[Windows.Forms.Label]::new();$routeLabel.Text='Rota de audio';$routeLabel.SetBounds(26,126,240,25)
$routeMode=[Windows.Forms.ComboBox]::new();$routeMode.DropDownStyle='DropDownList';$routeMode.SetBounds(26,152,260,30)
[void]$routeMode.Items.Add('PCM USB - PC para CM6206');[void]$routeMode.Items.Add('Optica - AC-3 recebido da TV')
$sourceLabel=[Windows.Forms.Label]::new();$sourceLabel.Text='Formato da fonte';$sourceLabel.SetBounds(306,126,270,25)
$sourceMode=[Windows.Forms.ComboBox]::new();$sourceMode.DropDownStyle='DropDownList';$sourceMode.SetBounds(306,152,270,30)
[void]$sourceMode.Items.Add('Auto - preservar formato informado');[void]$sourceMode.Items.Add('Estereo sem Dolby confirmado');[void]$sourceMode.Items.Add('Dolby / 5.1 - preservar canais')
$preferences=Get-AudioPreferences
$routeMode.SelectedIndex=if ($preferences.Mode -eq 'Optical') {1} else {0}
$sourceMode.SelectedIndex=@('Auto','Stereo','Native').IndexOf($preferences.InputMode)
$details=[Windows.Forms.Label]::new();$details.SetBounds(26,198,550,48)
$details.Text='PCM USB recebe o audio dos aplicativos. Upmix manual exige estereo sem Dolby confirmado. AC-3, E-AC-3 e fontes 5.1 conservam seus canais.'
$delayLabel=[Windows.Forms.Label]::new();$delayLabel.SetBounds(26,252,550,44)
$delayLabel.Font=[Drawing.Font]::new('Segoe UI',10);$delayLabel.Text='Atrasos: aguardando valores confirmados nesta rota.'
$on=[Windows.Forms.Button]::new();$on.Text='Ligar / aplicar rota';$on.SetBounds(26,302,260,44)
$off=[Windows.Forms.Button]::new();$off.Text='Desligar / restaurar audio';$off.SetBounds(306,302,270,44)
$tip=[Windows.Forms.Label]::new();$tip.SetBounds(26,356,550,56);$tip.Font=[Drawing.Font]::new('Segoe UI',9)
$form.Controls.AddRange(@($title,$stateLabel,$routeLabel,$routeMode,$sourceLabel,$sourceMode,$details,$delayLabel,$on,$off,$tip))
$script:actionProcess=$null;$script:controlPath=$PSCommandPath
$refresh={
    try {
        if ($script:actionProcess -and -not $script:actionProcess.HasExited) {
            $stateLabel.Text='Trocando a rota de audio...';$stateLabel.ForeColor=[Drawing.Color]::DarkOrange;return
        }
        if ($script:actionProcess) {$script:actionProcess.Dispose();$script:actionProcess=$null}
        $on.Enabled=$true;$off.Enabled=$true;$routeMode.Enabled=$true;$sourceMode.Enabled=$true
        $status=Get-AudioStatus
        $stateLabel.Text=$status.Estado
        $delayLabel.Text=Get-AudioDelayText $status
        $stateLabel.ForeColor=if ($status.Ligado) {[Drawing.Color]::ForestGreen} elseif ($status.UltimoErro) {[Drawing.Color]::Firebrick} else {[Drawing.Color]::DimGray}
        $tip.Text=if ($status.UltimoErro) {'Ultimo erro: '+$status.UltimoErro} else {'Depois de mudar a rota, atualize o video (F5). A confirmacao de saida nao substitui a verificacao de cada caixa.'}
        $tip.ForeColor=if ($status.UltimoErro) {[Drawing.Color]::Firebrick} else {[Drawing.Color]::DimGray}
    } catch {
        # Endpoint replacement and partial state writes cannot escape the timer.
        $stateLabel.Text='Atualizando dispositivos de audio...';$stateLabel.ForeColor=[Drawing.Color]::DarkOrange
        $tip.Text='Consulta temporariamente indisponivel; nova tentativa automatica.';$tip.ForeColor=[Drawing.Color]::DimGray
    }
}
$runAction={
    param($action)
    try {
        $on.Enabled=$false;$off.Enabled=$false;$routeMode.Enabled=$false;$sourceMode.Enabled=$false
        $stateLabel.Text='Trocando a rota de audio...'
        $selectedMode=if ($routeMode.SelectedIndex -eq 1) {'Optical'} else {'Pcm'}
        $selectedInput=@('Auto','Stereo','Native')[$sourceMode.SelectedIndex]
        $arguments='-NoProfile -ExecutionPolicy Bypass -File "'+$script:controlPath+'" -Acao '+$action+' -Mode '+$selectedMode+' -InputMode '+$selectedInput
        $script:actionProcess=Start-Process powershell.exe -WindowStyle Hidden -ArgumentList $arguments -PassThru
    } catch {
        $stateLabel.Text='Falha ao iniciar a troca';$tip.Text=$_.Exception.Message
        $on.Enabled=$true;$off.Enabled=$true;$routeMode.Enabled=$true;$sourceMode.Enabled=$true
    }
}
$on.Add_Click({& $runAction 'Ligar'});$off.Add_Click({& $runAction 'Desligar'})
$timer=[Windows.Forms.Timer]::new();$timer.Interval=1500;$timer.Add_Tick($refresh)
$form.Add_Shown({& $refresh;$timer.Start()})
$form.Add_FormClosed({$timer.Stop();$timer.Dispose();if ($script:actionProcess) {$script:actionProcess.Dispose()}})
[void]$form.ShowDialog()
