param(
    [string]$SdkPath = (Join-Path $env:LOCALAPPDATA 'Android\Sdk'),
    [string]$JdkPath = $env:JAVA_HOME,
    [switch]$SkipChecks
)
$ErrorActionPreference = 'Stop'
$projectPath = (Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
if (-not (Test-Path -LiteralPath (Join-Path $SdkPath 'platforms\android-36\android.jar'))) {
    throw 'Instale Android SDK Platform 36 e Build Tools 35.0.0, ou informe -SdkPath.'
}
if ([string]::IsNullOrWhiteSpace($JdkPath)) {
    $knownJdk = Join-Path $env:USERPROFILE '.jdks\ms-21.0.10'
    if (Test-Path -LiteralPath (Join-Path $knownJdk 'bin\java.exe')) { $JdkPath = $knownJdk }
}
$javaPath = if ([string]::IsNullOrWhiteSpace($JdkPath)) { (Get-Command java -ErrorAction Stop).Source } else { Join-Path $JdkPath 'bin\java.exe' }
$priorPreference = $ErrorActionPreference
try { $ErrorActionPreference = 'Continue'; $versionOutput = & $javaPath -version 2>&1 | Out-String }
finally { $ErrorActionPreference = $priorPreference }
if ($versionOutput -notmatch 'version "(17|18|19|20|21|22|23)(\.|"|-)' ) { throw 'Use um JDK compatível com Gradle 8.13 (17 a 23), via -JdkPath.' }
$sdkLiteral = (Resolve-Path -LiteralPath $SdkPath).Path.Replace('\','/').Replace(':','\:')
[IO.File]::WriteAllText((Join-Path $projectPath 'local.properties'),('sdk.dir=' + $sdkLiteral))
$tasks = @(':app:assembleDebug')
if (-not $SkipChecks) { $tasks += @(':app:testDebugUnitTest',':app:lintDebug') }
Push-Location -LiteralPath $projectPath
try {
    & $javaPath -classpath (Join-Path $projectPath 'gradle\wrapper\gradle-wrapper.jar') org.gradle.wrapper.GradleWrapperMain @tasks --console=plain
    if ($LASTEXITCODE -ne 0) { throw 'Compilação ou verificações falharam.' }
    $outputDirectory = Join-Path $projectPath 'artifacts'
    New-Item -ItemType Directory -Path $outputDirectory -Force | Out-Null
    $apkPath = Join-Path $outputDirectory 'sistema51-a34-0.5.0-debug.apk'
    Copy-Item -LiteralPath (Join-Path $projectPath 'app\build\outputs\apk\debug\app-debug.apk') -Destination $apkPath -Force
    Get-FileHash -LiteralPath $apkPath -Algorithm SHA256
} finally { Pop-Location }
