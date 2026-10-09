# Exercise the real remote command builder against a local stub, never audio.
$ErrorActionPreference='Stop'
$worker=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\configuracao-pc\controle-remoto\worker.ps1'))
$tokens=$null;$errors=$null
$ast=[Management.Automation.Language.Parser]::ParseFile($worker,[ref]$tokens,[ref]$errors)
if ($errors.Count) {throw ($errors|Out-String)}
$functionAst=$ast.Find({param($node) $node -is [Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Set-Cm6206MasterVolume'},$true)
if (-not $functionAst) {throw 'Remote volume function missing.'}
Invoke-Expression $functionAst.Extent.Text
$testRoot=Join-Path ([IO.Path]::GetTempPath()) ("sistema51-volume 'stub "+[Guid]::NewGuid().ToString('N'))
$audioDir=Join-Path $testRoot 'configuracao-pc'
$scriptsDir=Join-Path $testRoot 'scripts'
$manager=Join-Path $scriptsDir 'pc-cm6206-system.ps1'
$observed=Join-Path $scriptsDir 'observed.json'
$stub=@'
param([string]$Action,[double]$Gain,[bool]$Muted)
[IO.File]::WriteAllText((Join-Path $PSScriptRoot 'observed.json'),(@{Action=$Action;Gain=$Gain;Muted=$Muted}|ConvertTo-Json))
$state=@{Ligado=$true}
if ($Gain -eq 0.17) {$state.ConfigurationApplied=$false}
$state|ConvertTo-Json -Compress
'@
try {
    [IO.Directory]::CreateDirectory($audioDir)|Out-Null
    [IO.Directory]::CreateDirectory($scriptsDir)|Out-Null
    [IO.File]::WriteAllText($manager,$stub,[Text.Encoding]::ASCII)
    foreach ($case in @(@{Percent=42;Muted=$true;AppliedLive=$true},@{Percent=17;Muted=$false;AppliedLive=$false})) {
        $result=Set-Cm6206MasterVolume $case.Percent $case.Muted
        $actual=[IO.File]::ReadAllText($observed)|ConvertFrom-Json
        if ($actual.Action -ne 'Configure' -or [Math]::Abs($actual.Gain-$case.Percent/100.0) -gt 1e-9 -or
            $actual.Muted -ne $case.Muted -or $result.AppliedLive -ne $case.AppliedLive) {throw 'Typed gain/mute or live acknowledgement did not survive the remote PowerShell boundary.'}
    }
    $rejected=$false
    try {Set-Cm6206MasterVolume 101 $false|Out-Null} catch {$rejected=$true}
    if (-not $rejected) {throw 'Out-of-range volume was accepted.'}
} finally {
    foreach ($file in @($manager,$observed)) {if ([IO.File]::Exists($file)) {[IO.File]::Delete($file)}}
    foreach ($directory in @($scriptsDir,$audioDir,$testRoot)) {if ([IO.Directory]::Exists($directory)) {[IO.Directory]::Delete($directory)}}
}
Write-Output 'Passed: typed gain, mute on/off, live acknowledgement, quoted local path, range rejection. No audio or hardware changed.'
