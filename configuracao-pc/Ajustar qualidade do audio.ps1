param([ValidateSet('Fidelidade','Estavel')][string]$Perfil = 'Fidelidade', [ValidateSet(0,16,24,32,64)][int]$BufferMs = 0, [ValidateSet('Atual','Padrao','Minimo')][string]$PeriodoHdmi = 'Atual', [switch]$Json)
$ErrorActionPreference = 'Stop'
if ([IO.File]::Exists((Join-Path $PSScriptRoot 'cm6206-local.json')) -or
    [IO.File]::Exists((Join-Path $PSScriptRoot 'cm6206-state.json'))) {
    throw 'Os perfis 448/640 kbps pertencem ao codificador HDMI antigo; use o gerenciador CM6206 para a rota atual.'
}
. (Join-Path $PSScriptRoot 'LFE equalizador comum.ps1')
$controlMutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalAudio51Controle')
$eqMutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalLfeEqualizador')
$controlLocked = $false; $eqLocked = $false
try {
# Lock ordering is lifecycle -> configuration. The runner only uses the latter.
$controlLocked = Wait-AudioMutex $controlMutex 10000
if (-not $controlLocked) { throw 'Outra mudanca da rota de audio esta em andamento.' }
$eqLocked = Wait-AudioMutex $eqMutex 5000
if (-not $eqLocked) { throw 'Outro ajuste do LFE esta em andamento.' }
$configPath = Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'
$templatePath = Join-Path $PSScriptRoot 'mpv-sistema-dolby-baixa-latencia.conf'
$controller = Join-Path $PSScriptRoot 'Controle do sistema 5.1.ps1'
$config = Get-Content -LiteralPath $configPath -Raw
$template = Get-Content -LiteralPath $templatePath -Raw
foreach ($text in @($config,$template)) {
    if ([regex]::Matches($text, '(?m)^af=').Count -ne 1 -or [regex]::Matches($text, '(?m)^audio-buffer=').Count -ne 1) {
        throw 'O perfil precisa de exatamente uma linha af= e uma linha audio-buffer= em ambos os arquivos.'
    }
}
$delayPattern = ',adelay=([^,\]\r\n]+)'
$configDelay = [regex]::Matches($config,$delayPattern)
$templateDelay = [regex]::Matches($template,$delayPattern)
if ($configDelay.Count -ne 1 -or $templateDelay.Count -ne 1 -or $configDelay[0].Groups[1].Value -ne $templateDelay[0].Groups[1].Value) {
    throw 'Os atrasos dos canais precisam existir e coincidir nos dois perfis.'
}
$delaySpec = $configDelay[0].Groups[1].Value
$previousConfig = $config; $previousTemplate = $template
$status = (& $controller -Acao Status | ConvertFrom-Json)
$bitrate = if ($Perfil -eq 'Fidelidade') { 640 } else { 448 }
$buffer = if ($BufferMs -gt 0) { ($BufferMs / 1000.0).ToString('0.000',[Globalization.CultureInfo]::InvariantCulture) }
    elseif ($Perfil -eq 'Fidelidade') { '0.032' } else { '0.064' }
# Calibration observed on this CABLE -> NVIDIA HDMI pair: about +41 ppm.
# Resample BEFORE channel delays so sample counts use the final 48 kHz rate.
$filter = 'af=lavfi=[asetrate=48002,aresample=48000:filter_size=64:phase_shift=10:cutoff=0.97,adelay=' + $delaySpec + '],lavcac3enc=tospdif=yes:bitrate=' + $bitrate + ':minch=6'
if (Test-Path -LiteralPath $script:lfeStatePath) { $filter = 'af=' + (Get-LfeAf $filter.Substring(3) (Get-LfeSettings)) }
$config = [regex]::Replace($config, '(?m)^af=[^\r\n]*', $filter)
$config = [regex]::Replace($config, '(?m)^audio-buffer=[^\r\n]*', ('audio-buffer=' + $buffer))
$template = [regex]::Replace($template, '(?m)^af=[^\r\n]*', $filter)
$template = [regex]::Replace($template, '(?m)^audio-buffer=[^\r\n]*', ('audio-buffer=' + $buffer))
if ($PeriodoHdmi -ne 'Atual') {
    foreach ($name in @('config','template')) {
        $value = Get-Variable -Name $name -ValueOnly
        if ([regex]::Matches($value,'(?m)^wasapi-exclusive-buffer=').Count -gt 1) { throw 'Periodo WASAPI repetido na configuracao.' }
        $value = [regex]::Replace($value,'(?m)^wasapi-exclusive-buffer=[^\r\n]*\r?\n','')
        if ($PeriodoHdmi -eq 'Minimo') {
            $exclusiveLine = [regex]::Match($value,'(?m)^audio-exclusive=yes\r?\n').Value
            if (-not $exclusiveLine) { throw 'Saida HDMI exclusiva nao encontrada.' }
            $value = $value.Replace($exclusiveLine,($exclusiveLine + "wasapi-exclusive-buffer=min`r`n"))
        }
        Set-Variable -Name $name -Value $value
    }
}
$changed = $config -ne $previousConfig -or $template -ne $previousTemplate
if ($changed) {
    Set-AudioFileTransaction @(
        (New-AudioTextFile $configPath ($config.TrimEnd() + "`r`n") 'ASCII'),
        (New-AudioTextFile $templatePath ($template.TrimEnd() + "`r`n") 'ASCII')
    )
}
# Release before restarting: a newly launched relay needs this same mutex.
$eqMutex.ReleaseMutex(); $eqLocked = $false
$newStatus = $status
if ($status.Ligado -and $changed) {
    try {
        # Reuse the controller functions within this process: the profile already
        # owns its lifecycle mutex, so a child controller cannot acquire it.
        . $controller -Acao Biblioteca
        Stop-AudioSystem | Out-Null
        $newStatus = Start-AudioSystem
        if (-not $newStatus.Ligado) { throw 'A nova rota nao iniciou; verifique a conexao HDMI.' }
    } catch {
        $profileError = $_.Exception.Message
        $eqLocked = Wait-AudioMutex $eqMutex 5000
        if (-not $eqLocked) { throw ('Falha ao trocar perfil; nao foi possivel bloquear a restauracao: ' + $profileError) }
        # EQ/master may have been adjusted while reconnecting. Restore the old
        # quality profile while retaining those newer, independently saved values.
        $restoreConfig = $previousConfig; $restoreTemplate = $previousTemplate
        if (Test-Path -LiteralPath $script:lfeStatePath) {
            $latestSettings = Get-LfeSettings
            $restoreConfigFilter = Get-LfeAf ([regex]::Match($previousConfig,'(?m)^af=(.*)').Groups[1].Value.Trim()) $latestSettings
            $restoreTemplateFilter = Get-LfeAf ([regex]::Match($previousTemplate,'(?m)^af=(.*)').Groups[1].Value.Trim()) $latestSettings
            $restoreConfig = [regex]::Replace($previousConfig,'(?m)^af=[^\r\n]*',('af=' + $restoreConfigFilter))
            $restoreTemplate = [regex]::Replace($previousTemplate,'(?m)^af=[^\r\n]*',('af=' + $restoreTemplateFilter))
        }
        Set-AudioFileTransaction @(
            (New-AudioTextFile $configPath $restoreConfig 'ASCII'),
            (New-AudioTextFile $templatePath $restoreTemplate 'ASCII')
        )
        $eqMutex.ReleaseMutex(); $eqLocked = $false
        $recovered = $false
        try {
            Stop-AudioSystem | Out-Null
            $recoveredStatus = Start-AudioSystem
            $recovered = [bool]$recoveredStatus.Ligado
        } catch { }
        $recoveryText = if ($recovered) { 'O perfil anterior foi restaurado e voltou a tocar.' } else { 'O perfil anterior foi restaurado; verifique o HDMI e clique em Ligar.' }
        throw ($profileError + ' ' + $recoveryText)
    }
}
$frontDelay = $delaySpec.Split('|')[0]
$frontMs = [double]::Parse($frontDelay.TrimEnd('S'),[Globalization.CultureInfo]::InvariantCulture)
if ($frontDelay.EndsWith('S')) { $frontMs /= 48 }
$result = [pscustomobject]@{Perfil=$Perfil;BitrateKbps=$bitrate;BufferMs=([double]::Parse($buffer,[Globalization.CultureInfo]::InvariantCulture)*1000);PeriodoHdmi=if($config -match '(?m)^wasapi-exclusive-buffer=min\s*$'){'Minimo'}else{'Padrao'};AtrasoMs=[math]::Round($frontMs,1);Atrasos=$delaySpec;Alterado=$changed;Ligado=[bool]$newStatus.Ligado}
if ($Json) { $result | ConvertTo-Json -Compress }
else {
    Write-Output ('Perfil ' + $Perfil + ': AC-3 ' + $bitrate + ' kbit/s; buffer ' + $buffer + ' s; atrasos preservados (' + $delaySpec + ').')
    if (-not $changed) { Write-Output 'O perfil ja estava aplicado; nenhuma reinicializacao foi necessaria.' }
}
} finally {
    if ($eqLocked) { $eqMutex.ReleaseMutex() }; $eqMutex.Dispose()
    if ($controlLocked) { $controlMutex.ReleaseMutex() }; $controlMutex.Dispose()
}
