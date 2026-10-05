param([ValidateSet('Painel','Ligar','Desligar','Status','UpmixAuto','Nativo','Biblioteca')][string]$Acao = 'Painel')
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'Audio gerenciamento comum.ps1')
$script:baseDir = $PSScriptRoot
$script:runnerPath = Join-Path $PSScriptRoot 'rodar-audio-sistema.ps1'
$script:soundTool = Join-Path $PSScriptRoot 'ferramentas\soundvolumeview\SoundVolumeView.exe'
$script:cableId = '{0.0.0.00000000}.{1480f3d6-872e-45ff-a839-c8b330d0127e}'
$script:stopFile = Join-Path $PSScriptRoot 'audio-sistema.stop'
$script:disabledFile = Join-Path $PSScriptRoot 'audio-sistema.desligado'
$script:pidFile = Join-Path $PSScriptRoot 'audio-sistema.pid'
$script:normalFormat = Join-Path $PSScriptRoot 'formato-sony-com-sistema.dat'
$script:normalState = Join-Path $PSScriptRoot 'controle-audio-estado.json'
$script:actionErrorPath = Join-Path $PSScriptRoot 'controle-audio-erro.json'

function Invoke-SoundTool([string[]]$Arguments) {
    Invoke-AudioSoundTool $script:baseDir $Arguments
}

function Get-SonyEndpoint {
    $root = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render'
    $sonyCandidates = @()
    foreach ($key in Get-ChildItem -LiteralPath $root) {
        if ($key.GetValue('DeviceState') -ne 1) { continue }
        $properties = Get-Item -LiteralPath (Join-Path $key.PSPath 'Properties') -ErrorAction SilentlyContinue
        if ($properties -and $properties.GetValue('{a45c254e-df1c-4efd-8020-67d146a850e0},2') -match 'SONY' -and
            $properties.GetValue('{b3f8fa53-0004-438e-9003-51a46e139bfc},6') -match 'NVIDIA') {
            $sonyCandidates += '{0.0.0.00000000}.' + $key.PSChildName
        }
    }
    if ($sonyCandidates.Count -eq 1) { return $sonyCandidates[0] }
    return $null
}

function Get-OurRunner {
    return Get-AudioRunnerProcess $script:baseDir
}

function Get-AudioStatus {
    $runner = Get-OurRunner
    $sony = Get-SonyEndpoint
    $requested = -not (Test-Path -LiteralPath $script:disabledFile)
    $players = @(Get-AudioPlayerProcesses $runner $script:baseDir)
    $outputAlive = $players.Count -gt 0
    $updated = $null; $lastError = $null
    $statePath = Join-Path $script:baseDir 'audio-sistema-estado.json'
    if ([IO.File]::Exists($statePath)) {
        try {
            $runnerState = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
            if (-not $runner -or ($runnerState.RunnerId -eq $runner.ProcessId -and
                $runnerState.InicioProcessoUtc -eq $runner.CreationDate.ToUniversalTime().ToString('o'))) {
                $updated = $runnerState.AtualizadoEm; $lastError = $runnerState.UltimoErro
            }
        } catch { $lastError = 'Nao foi possivel ler o diagnostico do audio.' }
    }
    $profile = 'Personalizado'
    $delayMs = @($null,$null,$null,$null,$null,$null)
    try {
        $activeConfig = [IO.File]::ReadAllText((Join-Path $script:baseDir 'mpv-sistema-dolby.conf'))
        if ($activeConfig -match 'bitrate=640:minch=6' -and $activeConfig -match '(?m)^audio-buffer=0\.032\s*$') { $profile = 'Fidelidade' }
        elseif ($activeConfig -match 'bitrate=448:minch=6' -and $activeConfig -match '(?m)^audio-buffer=0\.064\s*$') { $profile = 'Estavel' }
        $delayMatch = [regex]::Match($activeConfig,',adelay=([^,\]\r\n]+)')
        if ($delayMatch.Success) {
            $parts = $delayMatch.Groups[1].Value.Split('|')
            if ($parts.Count -eq 6) {
                for ($i = 0; $i -lt 6; $i++) {
                    if ($parts[$i] -notmatch '^(\d+(?:\.\d+)?)(S?)$') { break }
                    $value = [double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)
                    if ($Matches[2] -eq 'S') { $value /= 48 }
                    $delayMs[$i] = [math]::Round($value,3)
                }
            }
        }
    } catch { $lastError = 'Nao foi possivel ler a configuracao do player.' }
    if ([IO.File]::Exists($script:actionErrorPath)) {
        try { $lastError = ([IO.File]::ReadAllText($script:actionErrorPath) | ConvertFrom-Json).Mensagem }
        catch { $lastError = 'Nao foi possivel ler o erro da ultima troca de audio.' }
    }
    $status = if (-not $requested) { 'Desligado - audio direto na TV' }
        elseif (-not $sony) { 'Aguardando HDMI da TV / decoder' }
        elseif ($runner -and $outputAlive) { 'Ligado - Dolby Digital 5.1' }
        elseif ($runner) { 'Reconectando a saida HDMI...' }
        else { 'Desligado - clique em Ligar' }
    $channelDelays = [pscustomobject]@{FL=$delayMs[0];FR=$delayMs[1];Central=$delayMs[2];LFE=$delayMs[3];SL=$delayMs[4];SR=$delayMs[5]}
    [pscustomobject]@{Estado=$status;Ligado=($null -ne $runner -and $outputAlive);Solicitado=$requested;HDMI=$sony;RunnerId=if($runner){$runner.ProcessId}else{$null};PlayerId=if($outputAlive){$players[0].ProcessId}else{$null};AtrasoMs=$delayMs[0];AtrasosMs=$channelDelays;Perfil=$profile;UpmixAutomatico=(-not [IO.File]::Exists((Join-Path $script:baseDir 'audio-sistema.nativo')));AtualizadoEm=$updated;UltimoErro=$lastError}
}

function Start-AudioSystem {
    Remove-Item -LiteralPath $script:disabledFile -ErrorAction SilentlyContinue
    $runner = Get-OurRunner
    $sony = Get-SonyEndpoint
    if (-not $runner -and $sony) {
        if ((Test-Path -LiteralPath $script:normalState) -and (Test-Path -LiteralPath $script:normalFormat)) {
            $saved = Get-Content -LiteralPath $script:normalState -Raw | ConvertFrom-Json
            if ($saved.SonyEndpoint -eq $sony) {
                Invoke-SoundTool @('/SetSpeakersConfig', $sony, '0x3f', '0x3f', '0x3f')
                Invoke-SoundTool @('/LoadDeviceFormat', $sony, ('"' + $script:normalFormat + '"'))
            }
        }
        Invoke-SoundTool @('/SetAllowExclusive', $sony, '1')
        Invoke-SoundTool @('/SetExclusivePriority', $sony, '1')
    }
    Invoke-SoundTool @('/SetDefault', $script:cableId, 'all')
    Invoke-SoundTool @('/SetAppDefault', $script:cableId, 'all', 'opera.exe')
    # Netflix Store 7.x is hosted by Edge; both the PWA and web player
    # must feed the six-channel virtual endpoint, not the occupied HDMI.
    Invoke-SoundTool @('/SetAppDefault', $script:cableId, 'all', 'msedge.exe')
    if (-not $runner) {
        Remove-Item -LiteralPath $script:stopFile -ErrorAction SilentlyContinue
        $arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script:runnerPath + '"'
        Start-Process -FilePath powershell.exe -WindowStyle Hidden -ArgumentList $arguments | Out-Null
    }
    $wait = [Diagnostics.Stopwatch]::StartNew()
    do {
        Start-Sleep -Milliseconds 250
        $status = Get-AudioStatus
    } while (-not $status.Ligado -and $status.HDMI -and $wait.ElapsedMilliseconds -lt 7000)
    return $status
}

function Stop-AudioSystem {
    Set-AudioFileTransaction @(
        (New-AudioTextFile $script:disabledFile 'Desligado pelo controle do usuario' 'ASCII'),
        (New-AudioTextFile $script:stopFile '' 'ASCII')
    )
    $runner = Get-OurRunner
    if ($runner) {
        $wait = [Diagnostics.Stopwatch]::StartNew()
        while ((Get-OurRunner) -and $wait.ElapsedMilliseconds -lt 7000) { Start-Sleep -Milliseconds 200 }
        $remaining = Get-OurRunner
        if ($remaining -and $remaining.ProcessId -eq $runner.ProcessId -and $remaining.CreationDate -eq $runner.CreationDate) {
            # Refresh ownership after waiting; a captured PID may already have ended.
            foreach ($child in @(Get-AudioPlayerProcesses $remaining $script:baseDir)) {
                $currentChild = Get-CimInstance Win32_Process -Filter "ProcessId=$($child.ProcessId)" -ErrorAction SilentlyContinue
                if ($currentChild -and $currentChild.CreationDate -eq $child.CreationDate) { Stop-Process -Id $child.ProcessId -ErrorAction SilentlyContinue }
            }
            $currentRunner = Get-OurRunner
            if ($currentRunner -and $currentRunner.CreationDate -eq $runner.CreationDate) { Stop-Process -Id $runner.ProcessId -ErrorAction SilentlyContinue }
            Remove-Item -LiteralPath $script:pidFile -ErrorAction SilentlyContinue
        }
    }
    $sony = Get-SonyEndpoint
    if ($sony) {
        $previousState = if(Test-Path -LiteralPath $script:normalState){Get-Content -LiteralPath $script:normalState -Raw | ConvertFrom-Json}else{$null}
        if (-not $previousState -or $previousState.SonyEndpoint -ne $sony) {
            $savedFormatPath = $script:normalFormat + '.save.' + [Guid]::NewGuid().ToString('N')
            try {
                Invoke-SoundTool @('/SaveDeviceFormat', $sony, ('"' + $savedFormatPath + '"'))
                if (-not [IO.File]::Exists($savedFormatPath) -or (Get-Item -LiteralPath $savedFormatPath).Length -eq 0) { throw 'Nao foi possivel salvar o formato HDMI para a proxima reconexao.' }
                Set-AudioFileTransaction @(
                    ([pscustomobject]@{Path=$script:normalFormat;Bytes=[IO.File]::ReadAllBytes($savedFormatPath)}),
                    (New-AudioTextFile $script:normalState (@{SonyEndpoint=$sony;Data=(Get-Date).ToString('s')} | ConvertTo-Json))
                )
            } finally { Remove-Item -LiteralPath $savedFormatPath -ErrorAction SilentlyContinue }
        }
        # Stereo bypasses the six-channel APO delay/upmix rules as well.
        Invoke-SoundTool @('/SetSpeakersConfig', $sony, '0x3', '0x3', '0x3')
        Invoke-SoundTool @('/SetDefaultFormat', $sony, '16', '48000', '2')
        Invoke-SoundTool @('/SetDefault', $sony, 'all')
    }
    Invoke-SoundTool @('/SetAppDefault', 'DefaultRenderDevice', 'all', 'opera.exe')
    Invoke-SoundTool @('/SetAppDefault', 'DefaultRenderDevice', 'all', 'msedge.exe')
    return Get-AudioStatus
}

if ($Acao -eq 'Biblioteca') { return }
if ($Acao -eq 'Status') { Get-AudioStatus | ConvertTo-Json -Compress; exit }
if ($Acao -in @('Ligar','Desligar','UpmixAuto','Nativo')) {
    $controlMutex = [Threading.Mutex]::new($false, 'Local\SistemaArtesanalAudio51Controle')
    $controlLocked = $false
    try {
        $controlLocked = Wait-AudioMutex $controlMutex 10000
        if (-not $controlLocked) { throw 'Outra mudanca de audio esta em andamento.' }
        Remove-Item -LiteralPath $script:actionErrorPath -ErrorAction SilentlyContinue
        if ($Acao -eq 'Ligar') { Start-AudioSystem | ConvertTo-Json -Compress }
        elseif ($Acao -eq 'Desligar') { Stop-AudioSystem | ConvertTo-Json -Compress }
        else {
            $nativeFlag = Join-Path $script:baseDir 'audio-sistema.nativo'
            if ($Acao -eq 'Nativo') { Set-AudioFileTransaction @((New-AudioTextFile $nativeFlag '' 'ASCII')) }
            elseif (Test-Path -LiteralPath $nativeFlag) { Remove-Item -LiteralPath $nativeFlag -ErrorAction Stop }
            Get-AudioStatus | ConvertTo-Json -Compress
        }
    } catch {
        $actionError = $_
        if ($controlLocked) {
            try {
                $errorRecord = @{Acao=$Acao;Mensagem=$actionError.Exception.Message;AtualizadoEm=[DateTime]::UtcNow.ToString('o')}
                Set-AudioFileTransaction @((New-AudioTextFile $script:actionErrorPath ($errorRecord | ConvertTo-Json -Compress)))
            } catch { }
        }
        throw $actionError
    } finally {
        if ($controlLocked) { $controlMutex.ReleaseMutex() }
        $controlMutex.Dispose()
    }
    exit
}

Add-Type -AssemblyName System.Windows.Forms
Add-Type -AssemblyName System.Drawing
[Windows.Forms.Application]::EnableVisualStyles()
$form = [Windows.Forms.Form]::new()
$form.Text = 'Sistema de audio 5.1'
$form.ClientSize = [Drawing.Size]::new(560,370)
$form.StartPosition = 'CenterScreen'
$form.FormBorderStyle = 'FixedDialog'
$form.MaximizeBox = $false
$form.Font = [Drawing.Font]::new('Segoe UI',11)
$title = [Windows.Forms.Label]::new()
$title.Text = 'Sistema de audio 5.1'
$title.Font = [Drawing.Font]::new('Segoe UI',20,[Drawing.FontStyle]::Bold)
$title.SetBounds(24,20,510,42)
$stateLabel = [Windows.Forms.Label]::new()
$stateLabel.SetBounds(26,75,505,32)
$stateLabel.Font = [Drawing.Font]::new('Segoe UI',12,[Drawing.FontStyle]::Bold)
$details = [Windows.Forms.Label]::new()
$details.Text = "Ligado: Dolby Digital, upmix e correcao FL/FR 76,8 ms; CEN 5,8 ms; SL/SR 71 ms; LFE 0 ms.`r`nDesligado: audio direto na TV em estereo."
$details.SetBounds(26,112,510,56)
$on = [Windows.Forms.Button]::new()
$on.Text = 'Ligar 5.1'
$on.SetBounds(26,180,238,52)
$off = [Windows.Forms.Button]::new()
$off.Text = 'Desligar / audio direto'
$off.SetBounds(286,180,248,52)
$eq = [Windows.Forms.Button]::new()
$eq.Text = 'Equalizador do subwoofer'
$eq.SetBounds(26,244,508,42)
$eq.Add_Click({
    $eqPath = Join-Path $script:baseDir 'Equalizador do sub.ps1'
    Start-Process powershell.exe -WindowStyle Hidden -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $eqPath + '"') | Out-Null
})
$tip = [Windows.Forms.Label]::new()
$tip.Text = 'Se o navegador nao acompanhar a troca, atualize o video (F5).'
$tip.SetBounds(26,300,505,50)
$tip.Font = [Drawing.Font]::new('Segoe UI',9)
$form.Controls.AddRange(@($title,$stateLabel,$details,$on,$off,$eq,$tip))
$script:actionProcess = $null
$script:controlPath = $PSCommandPath
$refresh = {
    if ($script:actionProcess -and -not $script:actionProcess.HasExited) {
        $stateLabel.Text = 'Trocando a rota de audio...'
        $stateLabel.ForeColor = [Drawing.Color]::DarkOrange
        return
    }
    if ($script:actionProcess) { $script:actionProcess.Dispose(); $script:actionProcess = $null }
    $on.Enabled=$true; $off.Enabled=$true
    $status = Get-AudioStatus
    $stateLabel.Text = $status.Estado
    $stateLabel.ForeColor = if($status.Ligado){[Drawing.Color]::ForestGreen}else{[Drawing.Color]::DimGray}
    $tip.Text = if ($status.UltimoErro) { 'Ultimo erro: ' + $status.UltimoErro } else { 'Se o navegador nao acompanhar a troca, atualize o video (F5).' }
    $tip.ForeColor = if ($status.UltimoErro) { [Drawing.Color]::Firebrick } else { [Drawing.Color]::DimGray }
}
$runAction = {
    param($action)
    $on.Enabled=$false; $off.Enabled=$false
    $stateLabel.Text = 'Trocando a rota de audio...'
    $arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + $script:controlPath + '" -Acao ' + $action
    $script:actionProcess = Start-Process powershell.exe -WindowStyle Hidden -ArgumentList $arguments -PassThru
}
$on.Add_Click({ & $runAction 'Ligar' })
$off.Add_Click({ & $runAction 'Desligar' })
$timer = [Windows.Forms.Timer]::new()
$timer.Interval=1500
$timer.Add_Tick($refresh)
$form.Add_Shown({ & $refresh; $timer.Start() })
$form.Add_FormClosed({ $timer.Stop(); $timer.Dispose() })
[void]$form.ShowDialog()
