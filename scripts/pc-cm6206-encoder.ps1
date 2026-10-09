# PCM6 virtual loopback -> AC-3 IEC61937 HDMI. Routing is owned by the controller.
# This re-encodes decoded browser PCM; an original AC-3 packet is not preserved.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')][string]$CaptureEndpointId,
    [Parameter(Mandatory=$true)][ValidatePattern('^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')][string]$RenderEndpointId,
    [Parameter(Mandatory=$true)][string]$MpvPath,
    [ValidateSet(384,448,640)][int]$Bitrate=640,
    [ValidateSet('Auto','Stereo','Native')][string]$InputMode='Auto',
    [string]$StopPath,
    [string]$LogPath,
    [string]$StatusPath,
    [ValidateRange(5,120)][int]$StartupTimeoutSeconds=30,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactRoot=Join-Path $projectRoot 'android-a34/artifacts'
$outputDir=Join-Path $artifactRoot ('pc-cm6206-encoder/'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
if(-not $StopPath){$StopPath=Join-Path $outputDir 'encoder.stop'}
if(-not $LogPath){$LogPath=Join-Path $outputDir 'encoder.log'}
if(-not $StatusPath){$StatusPath=Join-Path $outputDir 'status.json'}
if(-not(Test-Path -LiteralPath $MpvPath -PathType Leaf)){throw 'mpv local ausente.'}
foreach($path in @($StopPath,$LogPath,($LogPath+'.mpv.log'),$StatusPath)) {
    $resolved=[IO.Path]::GetFullPath($path)
    $rootPrefix=[IO.Path]::GetFullPath($artifactRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Arquivos de controle devem permanecer em android-a34/artifacts.'}
    if(Test-Path -LiteralPath $resolved){throw 'Use caminhos novos para stop/log/status; não reutilize evidência.'}
}
Add-Type -Path @(
    (Join-Path $projectRoot 'configuracao-pc/StereoUpmix.cs'),
    (Join-Path $projectRoot 'configuracao-pc/RelayLoopback.cs'),
    (Join-Path $projectRoot 'configuracao-pc/RelayLoopbackLowLatency.cs'),
    (Join-Path $PSScriptRoot 'optical-tests/WindowsAc3Encoder.cs')
)
$config=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($RenderEndpointId,$Bitrate,$InputMode)
if($ValidateOnly){[pscustomobject]@{PlaybackStarted=$false;SourceMode=$InputMode;Stop=$StopPath;Log=$LogPath;Status=$StatusPath;Config=$config};return}
$mutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalCM6206Encoder')
$locked=$false
try {
    $locked=$mutex.WaitOne(0)
    if(-not $locked){throw 'Já há um encoder HDMI ativo.'}
    $legacy=@(Get-CimInstance Win32_Process -Filter "Name='mpv.exe'" | Where-Object {$_.CommandLine -match '(?i)mpv-sistema-dolby(?:-baixa-latencia)?\.conf'})
    if($legacy.Count){throw 'Pare o gerenciador Dolby antigo antes de abrir o encoder HDMI.'}
    foreach($path in @($StopPath,$LogPath,$StatusPath)){New-Item -ItemType Directory -Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($path))) -Force|Out-Null}
    $configPath=Join-Path ([IO.Path]::GetDirectoryName([IO.Path]::GetFullPath($LogPath))) 'encoder.conf'
    $result=[Sistema51.Cm6206.WindowsAc3Encoder]::Run($CaptureEndpointId,$RenderEndpointId,$MpvPath,$configPath,$LogPath,$StopPath,$StatusPath,$Bitrate,$StartupTimeoutSeconds,$InputMode)
    $result|ConvertTo-Json -Depth 6
    if($result.Error -or -not $result.CleanupComplete){exit 1}
} finally {
    if($locked){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
