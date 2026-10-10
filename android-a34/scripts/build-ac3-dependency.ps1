param(
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$BashPath = 'C:\Program Files\Git\bin\bash.exe'
)
$ErrorActionPreference = 'Stop'
if (-not (Test-Path -LiteralPath $BashPath)) { throw 'Instale Git for Windows (Git Bash) ou informe -BashPath.' }
$taskNdk = Join-Path $SdkPath 'ndk\27.0.12077973'
if (-not (Test-Path -LiteralPath $taskNdk)) { throw 'Instale o Android NDK 27.0.12077973.' }
$taskPriorNdk = $env:ANDROID_NDK_HOME
try {
    $env:ANDROID_NDK_HOME = $taskNdk.Replace('\','/')
    $taskScript = (Join-Path $PSScriptRoot 'build-ac3-dependency.sh').Replace('\','/')
    & $BashPath $taskScript
    if ($LASTEXITCODE -ne 0) { throw 'Compilação da dependência AC-3 falhou.' }
} finally { $env:ANDROID_NDK_HOME = $taskPriorNdk }
