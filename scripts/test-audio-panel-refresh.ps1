# Exercise the actual timer handler without opening a form or changing audio.
param([string]$PanelPath = (Join-Path $PSScriptRoot '..\configuracao-pc\Controle do sistema 5.1.ps1'))
$ErrorActionPreference = 'Stop'
. (Join-Path $PSScriptRoot '..\configuracao-pc\CM6206 controlador comum.ps1')
Add-Type -AssemblyName System.Drawing
$tokens = $null; $parseErrors = $null
$ast = [System.Management.Automation.Language.Parser]::ParseFile(
    [IO.Path]::GetFullPath($PanelPath), [ref]$tokens, [ref]$parseErrors)
if ($parseErrors.Count) { throw ($parseErrors | Out-String) }
$assignment = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.AssignmentStatementAst] -and
    $node.Left.Extent.Text -eq '$refresh'
}, $true)
if (-not $assignment) { throw 'Panel timer handler not found.' }
$text = $assignment.Right.Extent.Text.Trim()
$refresh = [scriptblock]::Create($text.Substring(1, $text.Length - 2))
$script:actionProcess = $null
$stateLabel = [pscustomobject]@{Text='';ForeColor=$null}
$tip = [pscustomobject]@{Text='';ForeColor=$null}
$on = [pscustomobject]@{Enabled=$false}
$off = [pscustomobject]@{Enabled=$false}
$routeMode = [pscustomobject]@{Enabled=$false}
$sourceMode = [pscustomobject]@{Enabled=$false}
$delayLabel = [pscustomobject]@{Text=''}
$delayFunction=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-AudioDelayText'},$true)
if (-not $delayFunction) {throw 'Delay display function missing.'}
Invoke-Expression $delayFunction.Extent.Text
function Get-AudioStatus { throw [IO.IOException]::new('Simulated registry change during timer tick.') }
& $refresh
if ($stateLabel.Text -ne 'Atualizando dispositivos de audio...' -or
    $tip.Text -notmatch 'nova tentativa automatica') { throw 'Timer did not handle the registry read failure.' }
function Get-AudioStatus { [pscustomobject]@{Estado='Recovered';Ligado=$false;UltimoErro=$null} }
& $refresh
if ($stateLabel.Text -ne 'Recovered' -or -not $on.Enabled -or -not $off.Enabled) {
    throw 'Timer did not recover on its next successful tick.'
}
$script:actionProcess = [pscustomobject]@{HasExited=$false}
function Get-AudioStatus { throw 'Status must not be queried during an action.' }
& $refresh
if ($stateLabel.Text -ne 'Trocando a rota de audio...') { throw 'Busy-action refresh failed.' }
$script:actionProcess = $null
$testDir=Join-Path ([IO.Path]::GetTempPath()) ('sistema51-panel-test-'+[Guid]::NewGuid().ToString('N'))
[IO.Directory]::CreateDirectory($testDir)|Out-Null
$script:systemStatePath=Join-Path $testDir 'state.json'
$script:preferencePath=Join-Path $testDir 'preferences.json'
$script:actionErrorPath=Join-Path $testDir 'action-error.json'
foreach ($functionName in @('Get-AudioPreferences','Get-AudioStatus')) {
    $functionAst=$ast.Find({param($node) $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq $functionName},$true)
    if (-not $functionAst) {throw ('Missing function: '+$functionName)}
    Invoke-Expression $functionAst.Extent.Text
}
function Write-TestState($State) {[IO.File]::WriteAllText($script:systemStatePath,($State|ConvertTo-Json -Depth 8))}
try {
    $state=@{Estado='Ligado';Ligado=$true;Solicitado=$true;Modo='Pcm';InputMode='Native';RunnerId=$PID;PlayerId=1;AtualizadoEm=[DateTime]::UtcNow.ToString('o');RunnerStartedUtc=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o');AtrasosMs=@{FL=76.8;FR=76.8;CEN=5.8;LFE=5.8;SL=71;SR=71}}
    Write-TestState $state
    [IO.File]::WriteAllText($script:preferencePath,'{"Mode":"Optical","InputMode":"Stereo"}')
    $activePreferences=Get-AudioPreferences
    if ($activePreferences.Mode -ne 'Pcm' -or $activePreferences.InputMode -ne 'Native') {throw 'Old UI preferences replaced the active canonical session.'}
    if (-not (Get-AudioStatus).Ligado) {throw 'Fresh state from its matching worker was rejected.'}
    $delayText=Get-AudioDelayText (Get-AudioStatus)
    if ($delayText -notmatch '^Atrasos aplicados: FL/FR 76,8 ms; CEN 5,8 ms; LFE 5,8 ms; SL/SR 71,0 ms\.$') {throw ('Current session delays were not displayed: '+$delayText)}
    $state.AtrasosMs.FR=12.2;Write-TestState $state
    if ((Get-AudioDelayText (Get-AudioStatus)) -notmatch 'FL/FR 76,8/12,2 ms') {throw 'Different channel delays were flattened in the display.'}
    $state.AtrasosMs.LFE=-1;Write-TestState $state
    if ((Get-AudioDelayText (Get-AudioStatus)) -notmatch 'aguardando valores') {throw 'Invalid delay metadata was displayed as applied.'}
    $state.AtrasosMs.LFE=5.8;Write-TestState $state
    $state.RunnerStartedUtc=[DateTime]::UtcNow.ToString('o');Write-TestState $state
    if ((Get-AudioStatus).Ligado) {throw 'Recycled PID advertised playback.'}
    $state.RunnerStartedUtc=(Get-Process -Id $PID).StartTime.ToUniversalTime().ToString('o')
    $state.AtualizadoEm=[DateTime]::UtcNow.AddMinutes(-1).ToString('o');Write-TestState $state
    if ((Get-AudioStatus).Ligado) {throw 'Stale ready state advertised playback.'}
    $inactivePreferences=Get-AudioPreferences
    if ($inactivePreferences.Mode -ne 'Optical' -or $inactivePreferences.InputMode -ne 'Stereo') {throw 'A stale worker overrode saved inactive UI preferences.'}
    [IO.File]::WriteAllText($script:systemStatePath,'{"Ligado":')
    if ((Get-AudioStatus).Ligado -or -not (Get-AudioStatus).UltimoErro) {throw 'Malformed state was not safely reported.'}
} finally {
    foreach ($file in @($script:systemStatePath,$script:preferencePath,$script:actionErrorPath)) {if ([IO.File]::Exists($file)) {[IO.File]::Delete($file)}}
    [IO.Directory]::Delete($testDir)
}
Write-Output 'Passed: timer failure/recovery, busy action, fresh worker identity, recycled PID, stale/malformed state and live delay display. No audio or hardware changed.'
