# Browser/player -> per-stream APO on virtual cable -> bounded relay -> PCM USB.
# No HDMI/AC-3 transport, HID write, service restart, registry or global APO install.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$MpvPath,
    [Parameter(Mandatory=$true)][string]$SoundVolumeViewPath,
    [Parameter(Mandatory=$true)][string]$CaptureEndpointId,
    [Parameter(Mandatory=$true)][string]$RenderEndpointId,
    [ValidateRange(60,120)][int]$CrossoverHz=90,
    [ValidateRange(0,1)][double]$Gain=0.5,
    [ValidateSet('Auto','Stereo','Native')][string]$InputMode='Auto',
    [ValidateRange(-36,0)][double]$CenterTrimDb=-12,
    [switch]$SwapCenterLfe,
    [switch]$Shared,
    [string]$Application='opera.exe',
    [switch]$ValidateOnly
)
$ErrorActionPreference='Stop'
$projectRoot=(Resolve-Path -LiteralPath (Join-Path $PSScriptRoot '..')).Path
foreach($endpoint in @($CaptureEndpointId,$RenderEndpointId)) {
    if($endpoint -notmatch '^\{0\.0\.0\.00000000\}\.\{[0-9a-fA-F-]{36}\}$'){throw 'Use um ID de endpoint render WASAPI completo.'}
}
if($CaptureEndpointId -eq $RenderEndpointId){throw 'Captura e saída iguais causariam realimentação.'}
foreach($executable in @($MpvPath,$SoundVolumeViewPath)){if(-not(Test-Path -LiteralPath $executable -PathType Leaf)){throw 'Executável local ausente.'}}
if($Application -notmatch '^[A-Za-z0-9_.-]+\.exe$'){throw 'Informe somente o nome de processo do player/navegador.'}
$mutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalCM6206Pcm')
$locked=$false
$routed=$false
$outputDir=Join-Path $projectRoot 'android-a34/artifacts/pc-cm6206-pcm'
New-Item -ItemType Directory -Path $outputDir -Force|Out-Null
$stopPath=Join-Path $outputDir 'pcm.stop'
$configPath=Join-Path $outputDir 'pcm.conf'
$logPath=Join-Path $outputDir 'pcm.log'
function Invoke-VolumeTool([string[]]$Arguments) {
    # GUIDs, numeric roles, executable names or fully quoted local output paths.
    $process=Start-Process -FilePath $SoundVolumeViewPath -ArgumentList $Arguments -WindowStyle Hidden -Wait -PassThru
    if($process.ExitCode -ne 0){throw ('SoundVolumeView falhou: '+$process.ExitCode)}
}
try {
    $locked=$mutex.WaitOne(0)
    if(-not $locked){throw 'A rota PCM já está aberta.'}
    Add-Type -Path @((Join-Path $projectRoot 'configuracao-pc/StereoUpmix.cs'),(Join-Path $projectRoot 'configuracao-pc/RelayLoopback.cs'),(Join-Path $projectRoot 'configuracao-pc/RelayLoopbackLowLatency.cs'))
    [RelayLoopbackLowLatency]::VerifySourceFormat($CaptureEndpointId)
    $snapshotPath=Join-Path $outputDir 'routes-before.json'
    Invoke-VolumeTool @('/sjson',('"'+$snapshotPath+'"'))
    $items=Get-Content -LiteralPath $snapshotPath -Raw|ConvertFrom-Json
    $render=@($items|Where-Object {$_.'Item ID' -eq $RenderEndpointId -and $_.Type -eq 'Device' -and $_.Direction -eq 'Render'})
    $capture=@($items|Where-Object {$_.'Item ID' -eq $CaptureEndpointId -and $_.Type -eq 'Device' -and $_.Direction -eq 'Render'})
    if($render.Count -ne 1 -or $render[0].'Device Name' -notmatch 'USB Sound Device'){throw 'Saída USB não confirmada.'}
    if($capture.Count -ne 1 -or $capture[0].'Device Name' -notmatch 'Virtual Cable'){throw 'Fonte virtual não confirmada.'}
    $oldConsole=@($items|Where-Object { $_.Type -eq 'Device' -and $_.Default -eq 'Render' })
    $oldMultimedia=@($items|Where-Object { $_.Type -eq 'Device' -and $_.'Default Multimedia' -eq 'Render' })
    if($oldConsole.Count -ne 1 -or $oldMultimedia.Count -ne 1){throw 'Dispositivos padrão ambíguos.'}
    # Auto preserves this six-slot capture. Upmix can run before the system mix
    # only when the APO sees an actual 1/2-channel stream. Browsers may already
    # publish six slots for stereo; silence in four slots is never proof of stereo.
    # Stereo is an explicit override for a source independently known as stereo.
    [IO.File]::WriteAllText((Join-Path $outputDir 'audio-sistema.nativo'),'native')
    $gainLiteral=$Gain.ToString('0.########',[Globalization.CultureInfo]::InvariantCulture)
    $centerGain=[Math]::Pow(10,$CenterTrimDb/20).ToString('0.########',[Globalization.CultureInfo]::InvariantCulture)
    # LR4 of five satellites. Sum their lows into LFE with a six-source bound;
    # no positive EQ. Center trim protects the YS module at initial playback.
    $graph='asplit=2[main][sats];[main]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c2|c3=c3|c4=0*c4|c5=0*c5[lfe];'+
        '[sats]pan=5c|c0=c0|c1=c1|c2=c2|c3=c4|c4=c5,acrossover=split='+$CrossoverHz+':order=4th:precision=double[low][high];'+
        '[low]pan=5.1|c0=0*c0|c1=0*c1|c2=0*c2|c3=c0+c1+c2+c3+c4|c4=0*c3|c5=0*c4[bass];'+
        '[high]pan=5.1|c0=c0|c1=c1|c2=c2|c3=0*c0|c4=c3|c5=c4[top];'+
        '[lfe][bass][top]amix=inputs=3:normalize=0:dropout_transition=0,'+
        'pan=5.1|c0=c0|c1=c1|c2='+$centerGain+'*c2|c3=0.1666666666667*c3|c4=c4|c5=c5,'+
        'highpass=f=20:p=2:c=LFE,volume='+$gainLiteral+':precision=double,'+
        'pan=7.1|c0=c0|c1=c1|c2=c2|c3=c3|c4=c4|c5=c5|c6=c4|c7=c5'
    if($InputMode -eq 'Stereo') {
        $graph='pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1|c3=0.25*c0+0.25*c1|c4=0.5*c0|c5=0.5*c1,'+
            'lowpass=f=120:p=2:t=q:w=0.7071067811865476:c=LFE,'+
            'lowpass=f=120:p=2:t=q:w=0.7071067811865476:c=LFE,'+$graph
    }
    $graph=$graph.Replace('highpass=f=20:p=2:c=LFE','highpass=f=20:p=2:t=q:w=0.7071067811865476:c=LFE')
    if($SwapCenterLfe){$graph=$graph.Replace('pan=7.1|c0=c0|c1=c1|c2=c2|c3=c3','pan=7.1|c0=c0|c1=c1|c2=c3|c3=c2')}
    $device='wasapi/'+$RenderEndpointId.Substring('{0.0.0.00000000}.'.Length)
    $exclusive=if($Shared){'no'}else{'yes'}
    $config=@"
audio-device=$device
audio-exclusive=$exclusive
audio-fallback-to-null=no
audio-channels=7.1
audio-format=s16
audio-samplerate=48000
audio-spdif=
af=lavfi=[$graph]
audio-buffer=0.040
volume=100
volume-max=100
cache=no
demuxer=lavf
demuxer-lavf-format=wav
demuxer-lavf-probe-info=no
demuxer-lavf-o=ignore_length=1,max_size=11520
demuxer-lavf-buffersize=4096
stream-buffer-size=8192
demuxer-readahead-secs=0
demuxer-max-bytes=64KiB
load-scripts=no
terminal=no
input-default-bindings=no
osc=no
media-controls=no
input-media-keys=no
"@
    [IO.File]::WriteAllText($configPath,$config,[Text.Encoding]::ASCII)
    if($ValidateOnly){[pscustomobject]@{Preflight='ok';Config=$configPath;PlaybackStarted=$false;InputMode=$InputMode;CrossoverHz=$CrossoverHz;Stop=$stopPath};return}
    $legacy=@(Get-CimInstance Win32_Process -Filter "Name='powershell.exe' OR Name='pwsh.exe'" | Where-Object {
        $_.ProcessId -ne $PID -and $_.CommandLine -match '(?i)(?:[\\/]|\s)rodar-audio-sistema\.ps1(?:["\s]|$)'
    })
    if($legacy.Count){throw 'Pare o gerenciador Dolby antigo antes de abrir a rota PCM.'}
    if(Test-Path -LiteralPath $stopPath){Remove-Item -LiteralPath $stopPath}
    $routed=$true
    Invoke-VolumeTool @('/SetDefault',$CaptureEndpointId,'0')
    Invoke-VolumeTool @('/SetDefault',$CaptureEndpointId,'1')
    Invoke-VolumeTool @('/SetAppDefault',$CaptureEndpointId,'0',$Application)
    Invoke-VolumeTool @('/SetAppDefault',$CaptureEndpointId,'1',$Application)
    [RelayLoopbackLowLatency]::Run($CaptureEndpointId,$MpvPath,$configPath,$logPath,$stopPath)
} finally {
    if($routed){
        try {
            $currentPath=Join-Path $outputDir 'routes-stop.json'
            Invoke-VolumeTool @('/sjson',('"'+$currentPath+'"'))
            $current=Get-Content -LiteralPath $currentPath -Raw|ConvertFrom-Json
            if(@($current|Where-Object { $_.Type -eq 'Device' -and $_.Default -eq 'Render' -and $_.'Item ID' -eq $CaptureEndpointId }).Count -eq 1){Invoke-VolumeTool @('/SetDefault',$oldConsole[0].'Item ID','0')}
            if(@($current|Where-Object { $_.Type -eq 'Device' -and $_.'Default Multimedia' -eq 'Render' -and $_.'Item ID' -eq $CaptureEndpointId }).Count -eq 1){Invoke-VolumeTool @('/SetDefault',$oldMultimedia[0].'Item ID','1')}
            Invoke-VolumeTool @('/SetAppDefault',$oldMultimedia[0].'Item ID','0',$Application)
            Invoke-VolumeTool @('/SetAppDefault',$oldMultimedia[0].'Item ID','1',$Application)
        } catch {Write-Warning ('Confira a rota do navegador após parar: '+$_.Exception.Message)}
    }
    if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()
}
