param([Parameter(Mandatory=$true)][string]$Serial,[string]$AdbPath)
$ErrorActionPreference='Stop'
if([string]::IsNullOrWhiteSpace($AdbPath)){
    $sdkAdb=Join-Path $env:LOCALAPPDATA 'Android\Sdk\platform-tools\adb.exe'
    $AdbPath=if(Test-Path -LiteralPath $sdkAdb){$sdkAdb}else{'adb'}
}
$projectPath=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
$apkPath=Join-Path $projectPath 'app\build\outputs\apk\debug\app-debug.apk'
if(-not(Test-Path -LiteralPath $apkPath)){throw 'Compile o APK antes: scripts\build.ps1'}
$list=& $AdbPath devices -l | Out-String
if($list -notmatch ([regex]::Escape($Serial)+'\s+device(?:\s|$)')){throw 'O aparelho precisa estar autorizado no ADB.'}
& $AdbPath -s $Serial install --user 0 --no-streaming -r $apkPath
if($LASTEXITCODE -ne 0){throw 'Instalação falhou.'}
$outputDirectory=Join-Path $projectPath 'artifacts\device'
New-Item -ItemType Directory -Path $outputDirectory -Force|Out-Null
$text=& $AdbPath -s $Serial shell am instrument -w br.com.sistema51.a34/br.com.sistema51.a34.AppTestInstrumentation 2>&1|Out-String
[IO.File]::WriteAllText((Join-Path $outputDirectory 'instrumentation-console.txt'),$text)
Write-Output $text
if($LASTEXITCODE -ne 0 -or $text -notmatch 'INSTRUMENTATION_CODE: 0' -or $text -notmatch '"ok":true'){throw 'Testes instrumentados falharam; confira o relatório.'}
& $AdbPath -s $Serial shell am start -n br.com.sistema51.a34/.ui.MainActivity
