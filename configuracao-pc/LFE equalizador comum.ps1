# Shared by the panel and the quality profile switch. No new audio buffers.
. (Join-Path $PSScriptRoot 'Audio gerenciamento comum.ps1')
$script:lfeStatePath = Join-Path $PSScriptRoot 'equalizador-lfe.json'
$script:lfeFrequencies = @(20,25,30,40,50,60,80,100,120)

function Get-LfeSettings {
    if (Test-Path -LiteralPath $script:lfeStatePath) {
        try { $settings = Get-Content -LiteralPath $script:lfeStatePath -Raw | ConvertFrom-Json }
        catch { throw ('O arquivo equalizador-lfe.json nao pode ser lido: ' + $_.Exception.Message) }
        if (-not $settings -or $settings -is [array]) { throw 'O arquivo equalizador-lfe.json precisa conter um preset valido.' }
    } else {
        $bands = [ordered]@{}
        foreach ($frequency in $script:lfeFrequencies) { $bands["$frequency"] = 0.0 }
        $bands['30'] = 3.0; $bands['40'] = 3.0; $bands['60'] = -2.0
        $settings = [pscustomobject]@{Enabled=$true;AutoHeadroom=$true;Bands=[pscustomobject]$bands;Q=2.0}
    }
    # Upgrade older presets without changing their sound.
    $frequencies = [ordered]@{}; $widths = [ordered]@{}
    foreach ($frequency in $script:lfeFrequencies) { $frequencies["$frequency"]=$frequency; $widths["$frequency"]=2.0 }
    $defaults = [ordered]@{Frequencies=[pscustomobject]$frequencies;Widths=[pscustomobject]$widths;LevelDb=0.0;BassEnabled=$false;BassCutoffHz=80.0;BassSendDb=-6.0;BassAutoHeadroom=$true;CenterBassEnabled=$false;CenterBassCutoffHz=120.0;CenterBassSendDb=0.0;CenterBassAutoHeadroom=$true;MasterPercent=100;MasterMuted=$false}
    foreach ($name in $defaults.Keys) {
        if (-not $settings.PSObject.Properties[$name]) { $settings | Add-Member $name $defaults[$name] }
    }
    Assert-LfeSettings $settings
    return $settings
}

function Assert-LfeSettings($Settings) {
    if ($Settings.MasterMuted -isnot [bool] -or $null -eq $Settings.MasterPercent -or
        [double]::IsNaN([double]$Settings.MasterPercent) -or [double]$Settings.MasterPercent -lt 0 -or [double]$Settings.MasterPercent -gt 100) { throw 'Volume mestre invalido.' }
    if ($Settings.BassEnabled -isnot [bool] -or $Settings.BassAutoHeadroom -isnot [bool]) { throw 'Estado do corte das surrounds invalido.' }
    $cutoff = [double]$Settings.BassCutoffHz; $send = [double]$Settings.BassSendDb
    if ($null -eq $Settings.BassCutoffHz -or $null -eq $Settings.BassSendDb -or [double]::IsNaN($cutoff) -or $cutoff -lt 40 -or $cutoff -gt 120 -or [double]::IsNaN($send) -or $send -lt -24 -or $send -gt 0) { throw 'Corte ou envio de graves invalido.' }
    if ($Settings.CenterBassEnabled -isnot [bool] -or $Settings.CenterBassAutoHeadroom -isnot [bool]) { throw 'Estado dos graves da central invalido.' }
    $centerCutoff = [double]$Settings.CenterBassCutoffHz; $centerSend = [double]$Settings.CenterBassSendDb
    if ($null -eq $Settings.CenterBassCutoffHz -or $null -eq $Settings.CenterBassSendDb -or [double]::IsNaN($centerCutoff) -or $centerCutoff -lt 40 -or $centerCutoff -gt 120 -or [double]::IsNaN($centerSend) -or $centerSend -lt -24 -or $centerSend -gt 0) { throw 'Corte ou envio da central invalido.' }
    if ($Settings.Enabled -isnot [bool] -or $Settings.AutoHeadroom -isnot [bool]) { throw 'Estado do equalizador invalido.' }
    if ($null -eq $Settings.LevelDb -or [double]::IsNaN([double]$Settings.LevelDb) -or [double]$Settings.LevelDb -lt -24 -or [double]$Settings.LevelDb -gt 0) { throw 'Volume LFE invalido.' }
    foreach ($frequency in $script:lfeFrequencies) {
        $property = $Settings.Bands.PSObject.Properties["$frequency"]
        if (-not $property -or $null -eq $property.Value) { throw "Banda $frequency Hz ausente." }
        $gain = [double]$property.Value
        if ([double]::IsNaN($gain) -or [double]::IsInfinity($gain) -or $gain -lt -12 -or $gain -gt 6) {
            throw "Ganho invalido em $frequency Hz. Use -12 a +6 dB."
        }
        $hz = [double]$Settings.Frequencies.PSObject.Properties["$frequency"].Value
        $q = [double]$Settings.Widths.PSObject.Properties["$frequency"].Value
        if ($null -eq $Settings.Frequencies.PSObject.Properties["$frequency"].Value -or $null -eq $Settings.Widths.PSObject.Properties["$frequency"].Value) { throw "Frequencia ou largura da banda $frequency Hz ausente." }
        if ([double]::IsNaN($hz) -or $hz -lt 10 -or $hz -gt 200 -or [double]::IsNaN($q) -or $q -lt 0.3 -or $q -gt 10) { throw 'Frequencia ou largura de banda invalida.' }
    }
}

function Get-LfeHeadroom($Settings) {
    # Conservative margin: sum all positive gains, including overlapping bands.
    $margin = 0.0
    if ($Settings.Enabled -and $Settings.AutoHeadroom) {
        foreach ($frequency in $script:lfeFrequencies) { $margin += [Math]::Max(0, [double]$Settings.Bands.PSObject.Properties["$frequency"].Value) }
    }
    $mixPeak = 1.0
    if ($Settings.BassEnabled -and $Settings.BassAutoHeadroom) { $mixPeak += 2 * [Math]::Pow(10, [double]$Settings.BassSendDb / 20) }
    if ($Settings.CenterBassEnabled -and $Settings.CenterBassAutoHeadroom) { $mixPeak += [Math]::Pow(10, [double]$Settings.CenterBassSendDb / 20) }
    $margin += 20 * [Math]::Log10($mixPeak)
    return $margin
}

function Get-LfeAf([string]$BaseFilter, $Settings) {
    Assert-LfeSettings $Settings
    if ($BaseFilter -notmatch '^lavfi=\[(?<graph>.*)\](?<encoder>,lavcac3enc=.*)$') {
        throw 'Rota de audio nao reconhecida; nenhum ajuste foi aplicado.'
    }
    $graph = $Matches.graph; $encoder = $Matches.encoder
    # Only remove filters owned by this panel, preserving clock correction/delays.
    $graph = [regex]::Replace($graph, ',(?:pan@lfeheadroom|equalizer@lfe\d+|volume@master51)=[^,]+', '')
    $graph = [regex]::Replace($graph, ',asplit@surBass=2.*?amix@surBass=inputs=3:normalize=0:dropout_transition=0', '')
    $graph = [regex]::Replace($graph, ',asplit@cenBass=2.*?amix@cenBass=inputs=2:normalize=0:dropout_transition=0', '')
    $culture = [Globalization.CultureInfo]::InvariantCulture
    if ($Settings.BassEnabled) {
        $cutoff = ([double]$Settings.BassCutoffHz).ToString('0.0', $culture)
        $send = [Math]::Pow(10, [double]$Settings.BassSendDb / 20).ToString('0.############', $culture)
        # Split surround bass BEFORE adelay; the sub keeps the LFE timing.
        # Index 4/5 works for both back and side surround input layouts.
        $bass = ',asplit@surBass=2[sbmain][sbsur];' +
            '[sbmain]pan=5.1|c0=c0|c1=c1|c2=c2|c3=c3|c4=0*c4|c5=0*c5[sbkeep];' +
            '[sbsur]pan=stereo|c0=c4|c1=c5,acrossover=split=' + $cutoff + ':order=4th:precision=double[sblo][sbhi];' +
            '[sblo]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c0|c3=' + $send + '*c0+' + $send + '*c1|c4=0*c0|c5=0*c1[sblfe];' +
            '[sbhi]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c0|c3=0*c0|c4=c0|c5=c1[sbtop];' +
            '[sbkeep][sblfe][sbtop]amix@surBass=inputs=3:normalize=0:dropout_transition=0'
        if ([regex]::Matches($graph, ',adelay=').Count -ne 1) { throw 'Atrasos nao reconhecidos; corte nao aplicado.' }
        $graph = $graph.Replace(',adelay=', $bass + ',adelay=')
    }
    if ($Settings.CenterBassEnabled) {
        $centerCutoff = ([double]$Settings.CenterBassCutoffHz).ToString('0.0', $culture)
        $centerSend = [Math]::Pow(10, [double]$Settings.CenterBassSendDb / 20).ToString('0.############', $culture)
        # Keep the center full-range. A separate causal low-pass copy feeds LFE
        # before adelay, so the center and LFE retain their requested timings.
        $centerBass = ',asplit@cenBass=2[cbmain][cbsub];' +
            '[cbsub]pan=mono|c0=c2,lowpass@cenBassA=f=' + $centerCutoff + ':p=2,lowpass@cenBassB=f=' + $centerCutoff + ':p=2,' +
            'pan=5.1|c0=0*c0|c1=0*c0|c2=0*c0|c3=' + $centerSend + '*c0|c4=0*c0|c5=0*c0[cblfe];' +
            '[cbmain][cblfe]amix@cenBass=inputs=2:normalize=0:dropout_transition=0'
        if ([regex]::Matches($graph, ',adelay=').Count -ne 1) { throw 'Atrasos nao reconhecidos; envio da central nao aplicado.' }
        $graph = $graph.Replace(',adelay=', $centerBass + ',adelay=')
    }
    if ($Settings.Enabled -or $Settings.BassEnabled -or $Settings.CenterBassEnabled) {
        $level = if ($Settings.Enabled) { [double]$Settings.LevelDb } else { 0.0 }
        $scale = [Math]::Pow(10, ($level - (Get-LfeHeadroom $Settings)) / 20).ToString('0.############', $culture)
        $graph += ',pan@lfeheadroom=5.1|c0=c0|c1=c1|c2=c2|c3=' + $scale + '*c3|c4=c4|c5=c5'
    }
    if ($Settings.Enabled) {
        foreach ($frequency in $script:lfeFrequencies) {
            if ([double]$Settings.Bands.PSObject.Properties["$frequency"].Value -eq 0) { continue }
            $gain = ([double]$Settings.Bands.PSObject.Properties["$frequency"].Value).ToString('0.0', $culture)
            $hz = ([double]$Settings.Frequencies.PSObject.Properties["$frequency"].Value).ToString('0.0', $culture)
            $q = ([double]$Settings.Widths.PSObject.Properties["$frequency"].Value).ToString('0.0', $culture)
            # Causal IIR (block_size=0), no lookahead; only LFE is selected.
            $graph += ',equalizer@lfe' + $frequency + '=f=' + $hz + ':t=q:w=' + $q + ':g=' + $gain + ':c=LFE:b=0:r=f64'
        }
    }
    $master = if ($Settings.MasterMuted) { 0.0 } else { [double]$Settings.MasterPercent / 100 }
    $graph += ',volume@master51=volume=' + $master.ToString('0.00',$culture) + ':precision=double'
    return 'lavfi=[' + $graph + ']' + $encoder
}

function Invoke-LfeMpv([object[]]$Command, [int]$ConnectTimeoutMs = 1500) {
    $pipe = [IO.Pipes.NamedPipeClientStream]::new('.', 'SistemaArtesanalAudio51', [IO.Pipes.PipeDirection]::InOut, [IO.Pipes.PipeOptions]::Asynchronous)
    $reader = $null; $writer = $null
    try {
        $pipe.Connect($ConnectTimeoutMs)
        $utf8 = [Text.UTF8Encoding]::new($false)
        $reader = [IO.StreamReader]::new($pipe,$utf8,$false,4096,$true)
        $writer = [IO.StreamWriter]::new($pipe,$utf8,4096,$true); $writer.AutoFlush = $true
        $writer.WriteLine((@{command=$Command;request_id=5101} | ConvertTo-Json -Depth 10 -Compress))
        $clock = [Diagnostics.Stopwatch]::StartNew()
        while ($clock.ElapsedMilliseconds -lt 3500) {
            $read = $reader.ReadLineAsync()
            if (-not $read.Wait([Math]::Max(1,3500-[int]$clock.ElapsedMilliseconds))) { throw 'O player nao respondeu ao ajuste.' }
            if ($null -eq $read.Result) { throw 'O player encerrou a conexao.' }
            $reply = $read.Result | ConvertFrom-Json
            if ($reply.request_id -ne 5101) { continue }
            if ($reply.error -ne 'success') { throw ('Player: ' + $reply.error) }
            return $reply.data
        }
        throw 'Tempo esgotado ao ajustar o equalizador.'
    } finally {
        if ($writer) { $writer.Dispose() }; if ($reader) { $reader.Dispose() }; $pipe.Dispose()
    }
}

function Save-LfeSettings($Settings) {
    if ([IO.File]::Exists((Join-Path $PSScriptRoot 'cm6206-local.json')) -or
        [IO.File]::Exists((Join-Path $PSScriptRoot 'cm6206-state.json'))) {
        throw 'Este equalizador pertence ao codificador HDMI antigo. A rota CM6206 usa sua propria configuracao DSP.'
    }
    $mutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalLfeEqualizador')
    $locked = $false
    try {
        $locked = Wait-AudioMutex $mutex 5000
        if (-not $locked) { throw 'Outro ajuste do LFE esta em andamento.' }
        # Master controls save immediately through Set-SystemMasterVolume.
        # An older open EQ panel must never replace the latest remote volume.
        $current = Get-LfeSettings
        foreach ($property in @('BassEnabled','BassCutoffHz','BassSendDb','BassAutoHeadroom','CenterBassEnabled','CenterBassCutoffHz','CenterBassSendDb','CenterBassAutoHeadroom')) {
            if (-not $Settings.PSObject.Properties[$property]) { $Settings | Add-Member $property $current.$property }
        }
        foreach ($property in @('MasterPercent','MasterMuted')) {
            if ($Settings.PSObject.Properties[$property]) { $Settings.$property = $current.$property }
            else { $Settings | Add-Member $property $current.$property }
        }
        Assert-LfeSettings $Settings
        $configPath = Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'
        $templatePath = Join-Path $PSScriptRoot 'mpv-sistema-dolby-baixa-latencia.conf'
        $oldConfig = Get-Content -LiteralPath $configPath -Raw
        $oldTemplate = Get-Content -LiteralPath $templatePath -Raw
        if ([regex]::Matches($oldConfig,'(?m)^af=').Count -ne 1) { throw 'Configuracao af invalida.' }
        $base = [regex]::Match($oldConfig,'(?m)^af=(.*)').Groups[1].Value.Trim()
        $newFilter = Get-LfeAf $base $Settings
        $newConfig = [regex]::Replace($oldConfig,'(?m)^af=[^\r\n]*',('af=' + $newFilter))
        if ([regex]::Matches($oldTemplate,'(?m)^af=').Count -ne 1) { throw 'Configuracao af do template invalida.' }
        $templateBase = [regex]::Match($oldTemplate,'(?m)^af=(.*)').Groups[1].Value.Trim()
        $newTemplate = [regex]::Replace($oldTemplate,'(?m)^af=[^\r\n]*',('af=' + (Get-LfeAf $templateBase $Settings)))
        $status = & (Join-Path $PSScriptRoot 'Controle do sistema 5.1.ps1') -Acao Status | ConvertFrom-Json
        $oldLive = $null
        if ($status.Ligado) {
            $oldLive = Invoke-LfeMpv @('get_property','af')
            try {
                Invoke-LfeMpv @('af','set',$newFilter) | Out-Null
            } catch {
                if ($null -ne $oldLive) { try { Invoke-LfeMpv @('set_property','af',@($oldLive)) | Out-Null } catch {} }
                throw
            }
        }
        try {
            Set-AudioFileTransaction @(
                (New-AudioTextFile $configPath ($newConfig.TrimEnd() + "`r`n") 'ASCII'),
                (New-AudioTextFile $templatePath ($newTemplate.TrimEnd() + "`r`n") 'ASCII'),
                (New-AudioTextFile $script:lfeStatePath ($Settings | ConvertTo-Json -Depth 5))
            )
        } catch {
            if ($null -ne $oldLive) { try { Invoke-LfeMpv @('set_property','af',@($oldLive)) | Out-Null } catch {} }
            throw
        }
        return [pscustomobject]@{AppliedLive=[bool]$status.Ligado;HeadroomDb=(Get-LfeHeadroom $Settings);Enabled=$Settings.Enabled}
    } finally {
        if ($locked) { $mutex.ReleaseMutex() }; $mutex.Dispose()
    }
}

function Set-SystemMasterVolume([int]$Percent, [bool]$Muted) {
    if ($Percent -lt 0 -or $Percent -gt 100) { throw 'Use volume de 0 a 100%.' }
    $mutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalLfeEqualizador')
    $locked = $false
    try {
        $locked = Wait-AudioMutex $mutex 5000
        if (-not $locked) { throw 'Outro ajuste de audio esta em andamento.' }
        # Read the latest saved EQ/crossover so adjusting master never overwrites them.
        $settings = Get-LfeSettings
        $oldFactor = if ($settings.MasterMuted) { 0.0 } else { $settings.MasterPercent / 100.0 }
        $settings.MasterPercent=$Percent; $settings.MasterMuted=$Muted
        $factor = if ($Muted) { 0.0 } else { $Percent / 100.0 }
        $factorText = $factor.ToString('0.00',[Globalization.CultureInfo]::InvariantCulture)
        $configPath = Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'
        $templatePath = Join-Path $PSScriptRoot 'mpv-sistema-dolby-baixa-latencia.conf'
        $oldConfig = Get-Content -LiteralPath $configPath -Raw
        $oldTemplate = Get-Content -LiteralPath $templatePath -Raw
        $pattern = 'volume@master51=volume=[0-9.]+:precision=double'
        if ([regex]::Matches($oldConfig,$pattern).Count -ne 1 -or [regex]::Matches($oldTemplate,$pattern).Count -ne 1) { throw 'Volume mestre ausente ou repetido na configuracao; confira o painel.' }
        $replacement = 'volume@master51=volume=' + $factorText + ':precision=double'
        $live = $false
        try {
            # FFmpeg runtime command: no graph rebuild, no reset of delay/crossover.
            Invoke-LfeMpv -Command @('af-command','all','volume',$factorText,'volume@master51') -ConnectTimeoutMs 100 | Out-Null
            $live = $true
        } catch [TimeoutException] {
            # When the route is off, keep the desired volume for its next start.
        }
        try {
            Set-AudioFileTransaction @(
                (New-AudioTextFile $configPath ([regex]::Replace($oldConfig,$pattern,$replacement).TrimEnd() + "`r`n") 'ASCII'),
                (New-AudioTextFile $templatePath ([regex]::Replace($oldTemplate,$pattern,$replacement).TrimEnd() + "`r`n") 'ASCII'),
                (New-AudioTextFile $script:lfeStatePath ($settings | ConvertTo-Json -Depth 5))
            )
        } catch {
            if ($live) { try { Invoke-LfeMpv -Command @('af-command','all','volume',$oldFactor.ToString('0.00',[Globalization.CultureInfo]::InvariantCulture),'volume@master51') | Out-Null } catch {} }
            throw
        }
        [pscustomobject]@{AppliedLive=$live;MasterPercent=$Percent;MasterMuted=$Muted}
    } finally {
        if ($locked) { $mutex.ReleaseMutex() }; $mutex.Dispose()
    }
}
