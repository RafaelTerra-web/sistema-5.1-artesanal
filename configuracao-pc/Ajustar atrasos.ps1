param(
    [ValidateRange(0,500)][double]$FrontaisMs = 76.8,
    [ValidateRange(0,500)][double]$CentralMs = 5.8,
    [ValidateRange(0,500)][double]$SurroundsMs = 71,
    [switch]$Json
)
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot 'LFE equalizador comum.ps1')

function Convert-DelayToSamples([string]$Value) {
    if ($Value -notmatch '^(\d+(?:\.\d+)?)(S?)$') { throw ('Atraso de canal nao reconhecido: ' + $Value) }
    $number = [double]::Parse($Matches[1],[Globalization.CultureInfo]::InvariantCulture)
    if ($Matches[2] -eq 'S') {
        if ($number -ne [math]::Floor($number)) { throw 'Atraso em amostras precisa ser inteiro.' }
        return [int]$number
    }
    return [int][math]::Round($number * 48,[MidpointRounding]::AwayFromZero)
}

$lifecycleMutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalAudio51Controle')
$configMutex = [Threading.Mutex]::new($false,'Local\SistemaArtesanalLfeEqualizador')
$lifecycleLocked = $false; $configLocked = $false
try {
    $lifecycleLocked = Wait-AudioMutex $lifecycleMutex 10000
    if (-not $lifecycleLocked) { throw 'Outra mudanca da rota de audio esta em andamento.' }
    $configLocked = Wait-AudioMutex $configMutex 5000
    if (-not $configLocked) { throw 'Outro ajuste do audio esta em andamento.' }

    $configPath = Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf'
    $templatePath = Join-Path $PSScriptRoot 'mpv-sistema-dolby-baixa-latencia.conf'
    $oldConfig = [IO.File]::ReadAllText($configPath)
    $oldTemplate = [IO.File]::ReadAllText($templatePath)
    $pattern = ',adelay=([^,\]\r\n]+)'
    $configMatches = [regex]::Matches($oldConfig,$pattern)
    $templateMatches = [regex]::Matches($oldTemplate,$pattern)
    if ($configMatches.Count -ne 1 -or $templateMatches.Count -ne 1) {
        throw 'A rota e o perfil salvo precisam ter exatamente um filtro de atraso.'
    }
    $oldSpec = $configMatches[0].Groups[1].Value
    if ($oldSpec -ne $templateMatches[0].Groups[1].Value) { throw 'Os atrasos da rota e do perfil salvo nao coincidem.' }
    $parts = $oldSpec.Split('|')
    if ($parts.Count -ne 6) { throw 'Era esperado audio 5.1 com seis atrasos.' }
    $samples = @($parts | ForEach-Object { Convert-DelayToSamples $_ })
    if ($samples[3] -ne 0 -or $samples[4] -ne $samples[5]) {
        throw 'LFE ou surrounds estao diferentes da calibracao esperada.'
    }
    $frontSamples = [int][math]::Round($FrontaisMs * 48,[MidpointRounding]::AwayFromZero)
    $centerSamples = [int][math]::Round($CentralMs * 48,[MidpointRounding]::AwayFromZero)
    $surroundSamples = [int][math]::Round($SurroundsMs * 48,[MidpointRounding]::AwayFromZero)
    $newSpec = ('{0}S|{0}S|{1}S|0S|{2}S|{2}S' -f $frontSamples,$centerSamples,$surroundSamples)
    $oldToken = ',adelay=' + $oldSpec
    $newToken = ',adelay=' + $newSpec
    $newConfig = $oldConfig.Replace($oldToken,$newToken)
    $newTemplate = $oldTemplate.Replace($oldToken,$newToken)
    $frontMs = [math]::Round($frontSamples / 48,3)
    $centerMs = [math]::Round($centerSamples / 48,3)
    $surroundMs = [math]::Round($surroundSamples / 48,3)
    $comment = '# Correcao relativa: FL/FR ' + $frontMs.ToString([Globalization.CultureInfo]::InvariantCulture) + ' ms; CEN ' + $centerMs.ToString([Globalization.CultureInfo]::InvariantCulture) + ' ms; SL/SR ' + $surroundMs.ToString([Globalization.CultureInfo]::InvariantCulture) + ' ms; LFE 0 ms.'
    $newConfig = [regex]::Replace($newConfig,'(?m)^# Correcao relativa[^\r\n]*',$comment)
    $newTemplate = [regex]::Replace($newTemplate,'(?m)^# Correcao relativa[^\r\n]*',$comment)
    $changed = $newConfig -ne $oldConfig -or $newTemplate -ne $oldTemplate
    $newFilter = [regex]::Match($newConfig,'(?m)^af=([^\r\n]+)').Groups[1].Value
    if (-not $newFilter) { throw 'Filtro af da rota nao encontrado.' }

    $status = & (Join-Path $PSScriptRoot 'Controle do sistema 5.1.ps1') -Acao Status | ConvertFrom-Json
    $oldLive = $null; $appliedLive = $false
    if ($status.Ligado) {
        $oldLive = @(Invoke-LfeMpv @('get_property','af'))
        $oldLiveGraph = [string]$oldLive[0].params.graph
        if ($oldLiveGraph -notlike ('*' + $oldToken.Substring(1) + '*')) {
            throw 'O atraso do player nao corresponde a configuracao salva; ajuste cancelado.'
        }
        if ($oldSpec -ne $newSpec) {
            try {
                Invoke-LfeMpv @('af','set',$newFilter) | Out-Null
                $liveAfter = @(Invoke-LfeMpv @('get_property','af'))
                if ([string]$liveAfter[0].params.graph -notlike ('*' + $newToken.Substring(1) + '*')) {
                    throw 'O player nao confirmou o novo atraso.'
                }
                $appliedLive = $true
            } catch {
                try { Invoke-LfeMpv @('set_property','af',@($oldLive)) | Out-Null } catch { }
                throw
            }
        } else { $appliedLive = $true }
    }
    try {
        if ($changed) {
            Set-AudioFileTransaction @(
                (New-AudioTextFile $configPath $newConfig 'ASCII'),
                (New-AudioTextFile $templatePath $newTemplate 'ASCII')
            )
        }
    } catch {
        if ($appliedLive -and $null -ne $oldLive) {
            try { Invoke-LfeMpv @('set_property','af',@($oldLive)) | Out-Null } catch { }
        }
        throw
    }
    $result = [pscustomobject]@{
        FrontaisMs = $frontMs
        CentralMs = $centerMs
        SurroundsMs = $surroundMs
        DiferencaMs = [math]::Round(($frontSamples - $surroundSamples) / 48,3)
        AcrescimoSurroundsMs = [math]::Round(($surroundSamples - $samples[4]) / 48,3)
        LfeMs = 0
        Especificacao = $newSpec
        AplicadoAoVivo = $appliedLive
        Alterado = $changed
    }
    if ($Json) { $result | ConvertTo-Json -Compress }
    else { $result }
} finally {
    if ($configLocked) { $configMutex.ReleaseMutex() }; $configMutex.Dispose()
    if ($lifecycleLocked) { $lifecycleMutex.ReleaseMutex() }; $lifecycleMutex.Dispose()
}
