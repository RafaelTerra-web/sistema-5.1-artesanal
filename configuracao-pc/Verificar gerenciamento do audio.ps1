# Isolated regressions: never launch mpv, change a real endpoint or write a live preset.
$ErrorActionPreference = 'Stop'
$sourceDir = $PSScriptRoot
$testDir = Join-Path $sourceDir ('validacao-audio-' + [Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testDir) | Out-Null
$checks = @()
function Assert-Check([bool]$Condition,[string]$Name) {
    if (-not $Condition) { throw ('Falhou: ' + $Name) }
    $script:checks += $Name
}
$liveFiles = @('mpv-sistema-dolby.conf','mpv-sistema-dolby-baixa-latencia.conf','equalizador-lfe.json','audio-sistema.pid')
$before = @{}
foreach ($file in $liveFiles) {
    $path = Join-Path $sourceDir $file
    if ([IO.File]::Exists($path)) { $before[$file] = (Get-FileHash -LiteralPath $path).Hash }
}
foreach ($scriptFile in @('Audio gerenciamento comum.ps1','Controle do sistema 5.1.ps1','rodar-audio-sistema.ps1','LFE equalizador comum.ps1','Ajustar qualidade do audio.ps1','Ajustar atrasos.ps1','Verificar DSP central.ps1')) {
    $tokens = $null; $parseErrors = $null
    [Management.Automation.Language.Parser]::ParseFile((Join-Path $sourceDir $scriptFile),[ref]$tokens,[ref]$parseErrors) | Out-Null
    Assert-Check ($parseErrors.Count -eq 0) ('Sintaxe Windows PowerShell: ' + $scriptFile)
}
foreach ($file in @('Audio gerenciamento comum.ps1','LFE equalizador comum.ps1','Ajustar qualidade do audio.ps1','Ajustar atrasos.ps1','mpv-sistema-dolby.conf','mpv-sistema-dolby-baixa-latencia.conf','equalizador-lfe.json')) {
    Copy-Item -LiteralPath (Join-Path $sourceDir $file) -Destination (Join-Path $testDir $file)
}
[IO.File]::WriteAllText((Join-Path $testDir 'Controle do sistema 5.1.ps1'), 'param($Acao) if ($Acao -ne ''Status'') { throw ''Tentativa de reiniciar audio na simulacao.'' }; ''{"Ligado":false,"Solicitado":true}''')
. (Join-Path $testDir 'LFE equalizador comum.ps1')
$first = Join-Path $testDir 'first.txt'; $second = Join-Path $testDir 'second.txt'
Set-AudioFileTransaction @((New-AudioTextFile $first 'original-1'),(New-AudioTextFile $second 'original-2'))
$lockedFile = [IO.FileStream]::new($second,[IO.FileMode]::Open,[IO.FileAccess]::Read,[IO.FileShare]::Read)
$rejected = $false
try {
    try { Set-AudioFileTransaction @((New-AudioTextFile $first 'alterado-1'),(New-AudioTextFile $second 'alterado-2')) }
    catch { $rejected = $true }
} finally { $lockedFile.Dispose() }
Assert-Check ($rejected -and [IO.File]::ReadAllText($first) -eq 'original-1' -and [IO.File]::ReadAllText($second) -eq 'original-2') 'Falha parcial restaura ambos os arquivos'
Assert-Check (@(Get-ChildItem -LiteralPath $testDir -Filter '*.tmp.*').Count -eq 0) 'Escritas atomicas nao deixam temporarios'
Set-AudioFileTransaction @((New-AudioTextFile (Join-Path $testDir 'empty.flag') '' 'ASCII'))
Assert-Check ((Get-Item -LiteralPath (Join-Path $testDir 'empty.flag')).Length -eq 0) 'Flags vazias sao gravadas corretamente'
$expectedPath = Join-Path $testDir 'runner.ps1'
$fakeProcess = [pscustomobject]@{Name='powershell.exe';CommandLine=('powershell.exe -NoProfile -File "' + $expectedPath + '"')}
Assert-Check (Test-AudioProcessIdentity $fakeProcess $expectedPath) 'PID exige caminho exato do script'
$fakeProcess.CommandLine = 'powershell.exe -File "' + $expectedPath + '.outro"'
Assert-Check (-not (Test-AudioProcessIdentity $fakeProcess $expectedPath)) 'PID rejeita script com prefixo semelhante'
$settings = Get-LfeSettings
$settings.MasterPercent = 23; $settings.MasterMuted = $true
Set-AudioFileTransaction @((New-AudioTextFile $script:lfeStatePath ($settings | ConvertTo-Json -Depth 5)))
$stalePanel = Get-LfeSettings
$stalePanel.MasterPercent = 100; $stalePanel.MasterMuted = $false
$stalePanel.Bands.'20' = 5.0
Save-LfeSettings $stalePanel | Out-Null
$saved = Get-LfeSettings
Assert-Check ($saved.MasterPercent -eq 23 -and $saved.MasterMuted -and $saved.Bands.'20' -eq 5.0) 'Salvar EQ preserva volume remoto e mudo mais recentes'
$config = [IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))
$delayPattern = 'adelay=3686S\|3686S\|278S\|0S\|3408S\|3408S'
Assert-Check ($config -match $delayPattern -and $config -match 'asetrate=48002,aresample=48000' -and $config -match 'bitrate=640:minch=6' -and $config -match '(?m)^audio-buffer=0\.032\s*$') 'EQ preserva atrasos independentes, compensacao de clock, AC3 e buffer'
Assert-Check ($config -match 'asplit@cenBass=2' -and $config -match 'amix@cenBass=inputs=2:normalize=0') 'EQ preserva copia de graves da central para LFE'
$dspResult = & (Join-Path $sourceDir 'Verificar DSP central.ps1') -ConfigPath (Join-Path $testDir 'mpv-sistema-dolby.conf') -MpvPath (Join-Path $sourceDir 'mpv-portatil\mpv.exe') -WorkDirectory $testDir
Assert-Check ($dspResult.Central80 -gt 0.09 -and $dspResult.Central300 -gt 0.09 -and $dspResult.Lfe80 -gt 0.005 -and $dspResult.Lfe300 -lt $dspResult.Lfe80 * 0.25) 'Sinal sintetico: central intacta, LFE auxilia abaixo de 120 Hz'
$delayResult = & (Join-Path $testDir 'Ajustar atrasos.ps1') -Json | ConvertFrom-Json
Assert-Check (-not $delayResult.Alterado -and -not $delayResult.AplicadoAoVivo -and $delayResult.Especificacao -eq '3686S|3686S|278S|0S|3408S|3408S') 'Ajuste dos atrasos e idempotente na configuracao isolada'
$adjustedSurround = & (Join-Path $testDir 'Ajustar atrasos.ps1') -SurroundsMs 72 -Json | ConvertFrom-Json
Assert-Check ($adjustedSurround.Alterado -and $adjustedSurround.Especificacao -eq '3686S|3686S|278S|0S|3456S|3456S') 'Ajustar surrounds preserva frontais, central e LFE'
$restoredSurround = & (Join-Path $testDir 'Ajustar atrasos.ps1') -Json | ConvertFrom-Json
Assert-Check ($restoredSurround.Alterado -and $restoredSurround.Especificacao -eq '3686S|3686S|278S|0S|3408S|3408S') 'Restaurar surrounds recompoe a calibracao de 71 ms'
$adjustedCenter = & (Join-Path $testDir 'Ajustar atrasos.ps1') -CentralMs 8 -Json | ConvertFrom-Json
Assert-Check ($adjustedCenter.Alterado -and $adjustedCenter.Especificacao -eq '3686S|3686S|384S|0S|3408S|3408S') 'Ajustar central preserva frontais, LFE e surrounds'
$restoredCenter = & (Join-Path $testDir 'Ajustar atrasos.ps1') -Json | ConvertFrom-Json
Assert-Check ($restoredCenter.Alterado -and $restoredCenter.Especificacao -eq '3686S|3686S|278S|0S|3408S|3408S') 'Restaurar central recompoe a calibracao de 5,8 ms'
$templatePath = Join-Path $testDir 'mpv-sistema-dolby-baixa-latencia.conf'
$template = [IO.File]::ReadAllText($templatePath) + '# marcador exclusivo do template' + "`r`n"
Set-AudioFileTransaction @((New-AudioTextFile $templatePath $template 'ASCII'))
Save-LfeSettings (Get-LfeSettings) | Out-Null
Assert-Check ([IO.File]::ReadAllText($templatePath).Contains('# marcador exclusivo do template')) 'EQ preserva opcoes exclusivas do template'
$profileJson = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade -Json | ConvertFrom-Json
Assert-Check ($profileJson.BitrateKbps -eq 640 -and $profileJson.BufferMs -eq 32) 'Perfil tem retorno JSON com qualidade correta'
Assert-Check (([IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))) -match $delayPattern) 'Perfil preserva 76,792 ms nos frontais, 5,792 ms na central e 71 ms nas surrounds'
Assert-Check (([IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))) -match 'amix@cenBass=inputs=2:normalize=0') 'Perfil preserva graves auxiliares da central'
$minimumPeriod = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade -PeriodoHdmi Minimo -Json | ConvertFrom-Json
$minimumConfig = [IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))
$minimumTemplate = [IO.File]::ReadAllText($templatePath)
Assert-Check ($minimumPeriod.Alterado -and $minimumPeriod.PeriodoHdmi -eq 'Minimo' -and [regex]::Matches($minimumConfig,'(?m)^wasapi-exclusive-buffer=min\s*$').Count -eq 1 -and [regex]::Matches($minimumTemplate,'(?m)^wasapi-exclusive-buffer=min\s*$').Count -eq 1 -and $minimumConfig -match $delayPattern -and $minimumConfig -match 'amix@cenBass=inputs=2:normalize=0') 'Periodo HDMI minimo altera so o buffer e preserva atrasos e DSP'
$keepPeriod = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade -PeriodoHdmi Atual -Json | ConvertFrom-Json
Assert-Check (-not $keepPeriod.Alterado -and $keepPeriod.PeriodoHdmi -eq 'Minimo') 'Perfil reaplicado preserva periodo HDMI atual'
$defaultPeriod = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade -PeriodoHdmi Padrao -Json | ConvertFrom-Json
Assert-Check ($defaultPeriod.Alterado -and $defaultPeriod.PeriodoHdmi -eq 'Padrao' -and ([IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))) -notmatch '(?m)^wasapi-exclusive-buffer=' -and ([IO.File]::ReadAllText($templatePath)) -notmatch '(?m)^wasapi-exclusive-buffer=') 'Periodo HDMI padrao restaura ambos os arquivos'
$sameProfile = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade -Json | ConvertFrom-Json
Assert-Check (-not $sameProfile.Alterado) 'Reaplicar perfil igual evita reinicio'
$stableProfile = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Estavel -Json | ConvertFrom-Json
$stableConfig = [IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))
Assert-Check ($stableProfile.Alterado -and $stableConfig -match $delayPattern -and $stableConfig -match 'amix@cenBass=inputs=2:normalize=0' -and $stableConfig -match 'bitrate=448:minch=6') 'Trocar perfil preserva atrasos e graves auxiliares da central'
$fidelityProfile = & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade -Json | ConvertFrom-Json
Assert-Check ($fidelityProfile.Alterado -and ([IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))) -match $delayPattern -and ([IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))) -match 'amix@cenBass=inputs=2:normalize=0') 'Restaurar fidelidade preserva atrasos e graves auxiliares da central'
$controllerStub = @'
param($Acao)
if ($Acao -eq 'Status') { '{"Ligado":true,"Solicitado":true}'; return }
$statePath = Join-Path $PSScriptRoot 'equalizador-lfe.json'
$current = [IO.File]::ReadAllText($statePath) | ConvertFrom-Json
$current.MasterPercent = 34; $current.MasterMuted = $false
[IO.File]::WriteAllText($statePath,($current | ConvertTo-Json -Depth 5))
throw 'HDMI desconectado na simulacao.'
'@
[IO.File]::WriteAllText((Join-Path $testDir 'Controle do sistema 5.1.ps1'),$controllerStub)
$profileRejected = $false
try { & (Join-Path $testDir 'Ajustar qualidade do audio.ps1') -Perfil Estavel -Json | Out-Null }
catch { $profileRejected = $true }
$restoredConfig = [IO.File]::ReadAllText((Join-Path $testDir 'mpv-sistema-dolby.conf'))
$restoredTemplate = [IO.File]::ReadAllText($templatePath)
Assert-Check ($profileRejected -and $restoredConfig -match 'bitrate=640:minch=6' -and $restoredConfig -match 'volume@master51=volume=0\.34' -and $restoredConfig -match $delayPattern -and $restoredTemplate.Contains('# marcador exclusivo do template') -and $restoredTemplate -match $delayPattern) 'Rollback do perfil preserva volume concorrente, atrasos e template'
Add-Type -TypeDefinition @'
using System.Threading;
public static class AudioAbandonedMutexFixture {
    public static Mutex Abandon(string name) {
        Mutex mutex = new Mutex(false, name);
        Thread owner = new Thread(() => { mutex.WaitOne(); });
        owner.Start(); owner.Join();
        return mutex;
    }
}
'@
$abandoned = [AudioAbandonedMutexFixture]::Abandon('Local\AudioValidacao-' + [Guid]::NewGuid().ToString('N'))
$abandonedLocked = $false
try {
    $abandonedLocked = Wait-AudioMutex $abandoned 1000
    Assert-Check $abandonedLocked 'Mutex abandonado permite recuperacao'
} finally { if ($abandonedLocked) { $abandoned.ReleaseMutex() }; $abandoned.Dispose() }
foreach ($file in $before.Keys) {
    Assert-Check ((Get-FileHash -LiteralPath (Join-Path $sourceDir $file)).Hash -eq $before[$file]) ('Arquivo real preservado: ' + $file)
}
$report = [pscustomobject]@{Passed=$true;Checks=$checks;Directory=$testDir;Timestamp=[DateTime]::UtcNow.ToString('o')}
$report | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath (Join-Path $testDir 'resultado.json') -Encoding UTF8
$report | ConvertTo-Json -Depth 5
