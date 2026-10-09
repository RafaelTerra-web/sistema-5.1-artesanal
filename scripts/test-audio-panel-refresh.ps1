# Exercise the actual timer handler without opening a form or changing audio.
param([string]$PanelPath = (Join-Path $PSScriptRoot '..\configuracao-pc\Controle do sistema 5.1.ps1'))
$ErrorActionPreference = 'Stop'
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
$sonyFunction = $ast.Find({ param($node)
    $node -is [System.Management.Automation.Language.FunctionDefinitionAst] -and $node.Name -eq 'Get-SonyEndpoint'
}, $true)
Invoke-Expression $sonyFunction.Extent.Text
$sony = Get-SonyEndpoint # Read-only named registry values; no stream initialization.
Write-Output ('Passed: timer error handling, recovery, action state and read-only Sony lookup: ' + $sony)
