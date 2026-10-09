# File-only command and log acceptance tests. Never opens an endpoint or starts mpv.
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Add-Type -Path @(
    (Join-Path $projectRoot 'android-a34/scripts/windows-cm6206/CoreAudioFormatProbe.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifCapture.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifRelay.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifContinuousRelay.cs')
)
$renderId='{0.0.0.00000000}.{f6b92e59-dea6-4e22-a20e-6f5ccd9023f6}'
$validLog=@"
[   0.004][v][cplayer] Setting option 'ad-lavc-o' = 'err_detect=crccheck+explode' (flags = 8)
[   0.174][i][cplayer] Audio  --aid=1  (ac3 6ch 48000 Hz 640 kbps)
[   0.174][v][ad] Selected decoder: ac3 - ATSC A/52A (AC-3)
[   0.194][v][ao/wasapi] Selecting device '{f6b92e59-dea6-4e22-a20e-6f5ccd9023f6}' (Alto-falantes (USB Sound Device))
[   0.275][i][cplayer] AO: [wasapi] 48000Hz 7.1 8ch s16
"@
$cases=@(
    @{Name='native6 decoded and USB8';Log=$validLog;Ready=$true;Throws=$false;Bytes=6144},
    @{Name='carrier not yet sent';Log=$validLog;Ready=$false;Throws=$false;Bytes=0},
    @{Name='CRC evidence absent';Log=$validLog.Replace('crccheck+explode','ignore_err');Ready=$false;Throws=$false;Bytes=6144},
    @{Name='decoder evidence absent';Log=$validLog.Replace('Selected decoder: ac3','Selected decoder: other');Ready=$false;Throws=$false;Bytes=6144},
    @{Name='wrong USB endpoint';Log=$validLog.Replace('f6b92e59-dea6-4e22-a20e-6f5ccd9023f6','01234567-89ab-cdef-0123-456789abcdef');Ready=$false;Throws=$false;Bytes=6144},
    @{Name='stereo source explicit rejection';Log=$validLog.Replace('(ac3 6ch','(ac3 2ch');Ready=$false;Throws=$true;Bytes=6144},
    @{Name='stereo source after native6';Log=$validLog+"`n[   1.004][i][cplayer] Audio  --aid=1  (ac3 2ch 48000 Hz 448 kbps)";Ready=$false;Throws=$true;Bytes=6144},
    @{Name='output downmix rejection';Log=$validLog.Replace('7.1 8ch','stereo 2ch');Ready=$false;Throws=$true;Bytes=6144},
    @{Name='late output downmix rejection';Log=$validLog+"`n[   1.004][i][cplayer] AO: [wasapi] 48000Hz stereo 2ch s16";Ready=$false;Throws=$true;Bytes=6144},
    @{Name='CRC mismatch rejected but next valid frame can recover';Log=$validLog+"`n[   1.004][e][ffmpeg/audio] ac3: frame CRC mismatch`n[   1.004][e][ad] Error decoding audio.";Ready=$true;Throws=$false;Bytes=6144},
    @{Name='fatal output stops route';Log=$validLog+"`n[   1.004][e][ao/wasapi] output failed";Ready=$false;Throws=$true;Bytes=6144},
    @{Name='empty log not ready';Log='';Ready=$false;Throws=$false;Bytes=6144}
)
foreach($case in $cases){
    $result=[Sistema51.Cm6206.SpdifContinuousResult]::new()
    $result.RenderEndpointId=$renderId;$result.CarrierBytesSent=$case.Bytes
    $threw=$false
    try{[Sistema51.Cm6206.WindowsSpdifRelay]::ObserveContinuousLog($result,$case.Log)}catch{$threw=$true}
    if($threw -ne $case.Throws -or (-not $threw -and $result.Ready -ne $case.Ready)){throw ('Failed: '+$case.Name)}
    Write-Output ('Passed: '+$case.Name)
}
$command=[Sistema51.Cm6206.WindowsSpdifRelay]::BuildContinuousMpvArguments($renderId,'private.log',0.1,$false,$null,'\\.\pipe\sistema51-optical-test')
foreach($required in @('--audio-spdif=','--ad-lavc-downmix=no','--demuxer-lavf-format=spdif','--ad-lavc-o=err_detect=crccheck+explode','--audio-channels=7.1','--volume=46.41588834','--input-ipc-server=\\.\pipe\sistema51-optical-test','c2=c2|c3=c3','c6=c4|c7=c5')) {
    if(-not $command.Contains($required)){throw ('Missing command option: '+$required)}
}
if($command.Contains('volume=0.1:')){throw 'Linear gain was applied twice.'}
Write-Output 'Passed: safe carrier decode, channel map and cubic volume command'
$sixCommand=[Sistema51.Cm6206.WindowsSpdifRelay]::BuildContinuousMpvArguments($renderId,'private.log',0.1,$false,$null,$null,$false,'3686,3686,278,278,3408,3408',6)
if(-not $sixCommand.Contains('--audio-channels=5.1(side)') -or $sixCommand.Contains('|c6=') -or -not $sixCommand.Contains('278S|278S')){throw 'Six-channel duplex output was not generated without duplicate rear channels.'}
$sixResult=[Sistema51.Cm6206.SpdifContinuousResult]::new()
$sixResult.RenderEndpointId=$renderId;$sixResult.CarrierBytesSent=6144;$sixResult.RequestedOutputChannels=6
[Sistema51.Cm6206.WindowsSpdifRelay]::ObserveContinuousLog($sixResult,$validLog.Replace('7.1 8ch','5.1(side) 6ch'))
if(-not $sixResult.Ready -or -not $sixResult.NativeUsbOutputNegotiated -or $sixResult.EightChannelUsbOutputNegotiated){throw 'Six-channel receiver readiness was not distinguished from USB8.'}
Write-Output 'Passed: six-channel duplex map and exact output readiness'
$muted=[Sistema51.Cm6206.WindowsSpdifRelay]::BuildContinuousMpvArguments($renderId,'private.log',0.1,$false,$null,$null,$true)
if(-not $muted.Contains('--mute=yes') -or $muted.Contains('--mute=no')){throw 'Initial mute was not present before mpv startup.'}
Write-Output 'Passed: initial mute applied before renderer startup'
$zeroGain=[Sistema51.Cm6206.WindowsSpdifRelay]::BuildContinuousMpvArguments($renderId,'private.log',0,$false,$null)
if(-not $zeroGain.Contains('--volume=0')){throw 'A zero linear gain must keep the decoder running with silent output.'}
Write-Output 'Passed: zero gain is valid without disabling carrier decoding'
$defaultFilter=[Sistema51.Cm6206.WindowsSpdifRelay]::DefaultNativeDspFilter()
if(-not $defaultFilter.Contains('adelay=3686S|3686S|278S|278S|3408S|3408S,pan=7.1')){throw 'Requested six-channel delays must precede USB mapping.'}
$customDelays=[Sistema51.Cm6206.WindowsSpdifRelay]::DefaultNativeDspFilter('0,1,2,3,4,96000')
if(-not $customDelays.Contains('adelay=0S|1S|2S|3S|4S|96000S,pan=7.1')){throw 'Validated CSV profile was not applied.'}
foreach($invalid in @('3686,3686,278,0,3408','3686,3686,-1,278,3408,3408','3686,3686,278,278,3408,96001','1,2,3,4,5,6,7')){
    $rejected=$false;try{[Sistema51.Cm6206.WindowsSpdifRelay]::ParseDelaySamplesCsv($invalid)|Out-Null}catch{$rejected=$true}
    if(-not $rejected){throw ('Invalid delay profile accepted: '+$invalid)}
}
Write-Output 'Passed: six exact requested delays, custom profile and range validation'
$testDir=Join-Path $projectRoot 'android-a34/artifacts/continuous-spdif-file-tests'
New-Item -ItemType Directory -Path $testDir -Force|Out-Null
$configPath=Join-Path $testDir 'native.conf'
[IO.File]::WriteAllText($configPath,"ao=null`nload-scripts=yes`naf=lavfi=[pan=7.1|c0=c0|c1=c1|c2=c2|c3=c3|c4=c4|c5=c5|c6=c4|c7=c5]`n")
$custom=[Sistema51.Cm6206.WindowsSpdifRelay]::BuildContinuousMpvArguments($renderId,'private.log',0.1,$true,$configPath)
if($custom.Contains('--include=') -or $custom.Contains('ao=null') -or $custom.Contains('load-scripts=yes') -or -not $custom.Contains('--audio-exclusive=no')){throw 'Config contributed options outside its DSP graph.'}
Write-Output 'Passed: custom configuration reads only the explicit DSP graph'
[IO.File]::WriteAllText($configPath,"af=lavfi=[anull]`naf=lavfi=[anull]`n")
$rejected=$false;try{[Sistema51.Cm6206.WindowsSpdifRelay]::ReadNativeDspFilterFromConfig($configPath)|Out-Null}catch{$rejected=$true}
if(-not $rejected){throw 'Ambiguous custom graph was accepted.'}
Write-Output 'Passed: ambiguous custom graphs rejected'
