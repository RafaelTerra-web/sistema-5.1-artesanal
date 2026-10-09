# TV optical -> byte-preserving PCM16 IEC61937 capture -> strict AC-3 decode -> USB analog.
# The controller owns source HDMI routing and any temporary HID guard. This runner
# never changes endpoint volumes, defaults, capture gains or CM6206 registers.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][ValidatePattern('^\{0\.0\.1\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')][string]$CaptureEndpointId,
    [Parameter(Mandatory=$true)][ValidatePattern('^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$')][string]$RenderEndpointId,
    [Parameter(Mandatory=$true)][string]$MpvPath,
    [ValidateRange(0,1)][double]$Gain=0.1,
    [ValidateSet(6,8)][int]$OutputChannels=6,
    [switch]$KeepDeviceAlive,
    [switch]$Shared,
    [switch]$Muted,
    [ValidatePattern('^[0-9]+(,[0-9]+){5}$')][string]$DelaySamplesCsv='3686,3686,278,278,3408,3408',
    [string]$StopPath,
    [string]$LogPath,
    [string]$StatusPath,
    [string]$ConfigPath,
    [string]$IpcPath,
    [ValidateRange(0,86400)][int]$MaximumSeconds=0,
    [ValidateRange(5,120)][int]$StartupTimeoutSeconds=30,
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
if([Threading.Thread]::CurrentThread.ApartmentState -ne 'STA'){throw 'Use powershell.exe -STA -NoProfile -File pc-cm6206-optical.ps1 ...'}
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$artifactRoot=Join-Path $projectRoot 'android-a34/artifacts'
$outputDir=Join-Path $artifactRoot ('pc-cm6206-optical/'+[DateTime]::UtcNow.ToString('yyyyMMdd-HHmmss-fff'))
if(-not $StopPath){$StopPath=Join-Path $outputDir 'optical.stop'}
if(-not $LogPath){$LogPath=Join-Path $outputDir 'optical.log'}
if(-not $StatusPath){$StatusPath=Join-Path $outputDir 'status.json'}
if(-not(Test-Path -LiteralPath $MpvPath -PathType Leaf)){throw 'mpv local ausente.'}
if($ConfigPath -and -not(Test-Path -LiteralPath $ConfigPath -PathType Leaf)){throw 'Configuração DSP local ausente.'}
foreach($path in @($StopPath,$LogPath,$StatusPath)) {
    $resolved=[IO.Path]::GetFullPath($path)
    $rootPrefix=[IO.Path]::GetFullPath($artifactRoot).TrimEnd([IO.Path]::DirectorySeparatorChar)+[IO.Path]::DirectorySeparatorChar
    if(-not $resolved.StartsWith($rootPrefix,[StringComparison]::OrdinalIgnoreCase)){throw 'Arquivos de controle devem permanecer em android-a34/artifacts.'}
    if(Test-Path -LiteralPath $resolved){throw 'Use caminhos novos para stop/log/status; nunca reaproveite evidência de outra execução.'}
}
Add-Type -Path @(
    (Join-Path $projectRoot 'android-a34/scripts/windows-cm6206/CoreAudioFormatProbe.cs'),
    (Join-Path $PSScriptRoot 'optical-tests/WindowsSpdifCapture.cs'),
    (Join-Path $PSScriptRoot 'optical-tests/WindowsSpdifRelay.cs'),
    (Join-Path $PSScriptRoot 'optical-tests/WindowsSpdifContinuousRelay.cs')
)
$arguments=[Sistema51.Cm6206.WindowsSpdifRelay]::BuildContinuousMpvArguments($RenderEndpointId,$LogPath,$Gain,[bool]$Shared,$ConfigPath,$IpcPath,[bool]$Muted,$DelaySamplesCsv,$OutputChannels,[bool]$KeepDeviceAlive)
if($ValidateOnly){
    [pscustomobject]@{PlaybackStarted=$false;Stop=$StopPath;Log=$LogPath;Status=$StatusPath;Arguments=$arguments}
    return
}
$mutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalCM6206Pcm')
$locked=$false
try {
    $locked=$mutex.WaitOne(0)
    if(-not $locked){throw 'Outra rota PCM/óptica já possui a CM6206. Pare a rota atual antes de trocar.'}
    $legacy=@(Get-CimInstance Win32_Process -Filter "Name='mpv.exe'" | Where-Object {$_.CommandLine -match '(?i)mpv-sistema-dolby(?:-baixa-latencia)?\.conf'})
    if($legacy.Count){throw 'Pare o gerenciador Dolby antigo antes de abrir a captura óptica.'}
    Write-Output ('AC-3 óptico nativo6 -> USB'+$OutputChannels+'; stop: '+$StopPath)
    $result=[Sistema51.Cm6206.WindowsSpdifRelay]::RunContinuous(
        $CaptureEndpointId,$RenderEndpointId,$MpvPath,$LogPath,$StopPath,$StatusPath,$artifactRoot,
        $Gain,[bool]$Shared,$ConfigPath,$IpcPath,$MaximumSeconds,$StartupTimeoutSeconds,[bool]$Muted,$DelaySamplesCsv,$OutputChannels,[bool]$KeepDeviceAlive)
    $result|ConvertTo-Json -Depth 6
    if($result.Error -or -not $result.CleanupComplete){exit 1}
} finally {
    if($locked){$mutex.ReleaseMutex()}
    $mutex.Dispose()
}
