# File-only encoder configuration/log checks. Never opens an endpoint or starts mpv.
$ErrorActionPreference='Stop'
$projectRoot=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '../..'))
Add-Type -Path @(
    (Join-Path $projectRoot 'configuracao-pc/StereoUpmix.cs'),
    (Join-Path $projectRoot 'configuracao-pc/RelayLoopback.cs'),
    (Join-Path $projectRoot 'configuracao-pc/RelayLoopbackLowLatency.cs'),
    (Join-Path $PSScriptRoot 'WindowsAc3Encoder.cs')
)
$sonyId='{0.0.0.00000000}.{d83adb3c-7863-4a73-bfd8-b01de1fb9842}'
$relayLog='running endpoint=VB mpvPid=12345 format=WAV/6ch/48000/float32/mask0x3F' + "`n" + 'capturedFrames=48000 sentFrames=47520 mode=native'
$playerLog=@"
[   0.067][v][ao/wasapi] Selecting device '{d83adb3c-7863-4a73-bfd8-b01de1fb9842}' (SONY TV *00 (NVIDIA High Definition Audio))
[   0.136][i][cplayer] AO: [wasapi] 48000Hz stereo 2ch spdif-ac3
"@
$cases=@(
    @{Name='native six PCM to HDMI AC-3';Relay=$relayLog;Player=$playerLog;Source=$true;Ready=$true;Throws=$false},
    @{Name='no preflight evidence';Relay=$relayLog;Player=$playerLog;Source=$false;Ready=$false;Throws=$false},
    @{Name='no PCM sent';Relay=$relayLog.Replace('sentFrames=47520','sentFrames=0');Player=$playerLog;Source=$true;Ready=$false;Throws=$false},
    @{Name='wrong HDMI endpoint';Relay=$relayLog;Player=$playerLog.Replace('d83adb3c-7863-4a73-bfd8-b01de1fb9842','01234567-89ab-cdef-0123-456789abcdef');Source=$true;Ready=$false;Throws=$false},
    @{Name='non-Sony device';Relay=$relayLog;Player=$playerLog.Replace('SONY TV','USB Sound Device');Source=$true;Ready=$false;Throws=$false},
    @{Name='PCM output is not passthrough carrier';Relay=$relayLog;Player=$playerLog.Replace('spdif-ac3','s16');Source=$true;Ready=$false;Throws=$true},
    @{Name='later PCM output fails';Relay=$relayLog;Player=$playerLog+"`n[   1.004][i][cplayer] AO: [wasapi] 48000Hz stereo 2ch s16";Source=$true;Ready=$false;Throws=$true},
    @{Name='relay worker error';Relay=$relayLog+"`n2026-10-09 ERROR worker failed";Player=$playerLog;Source=$true;Ready=$false;Throws=$true},
    @{Name='player output error';Relay=$relayLog;Player=$playerLog+"`n[   1.004][e][ao/wasapi] output failed";Source=$true;Ready=$false;Throws=$true}
)
foreach($case in $cases) {
    $status=[Sistema51.Cm6206.Ac3EncoderStatus]::new()
    $status.RenderEndpointId=$sonyId;$status.SixChannelPcmSourceValidated=$case.Source
    $threw=$false;try{[Sistema51.Cm6206.WindowsAc3Encoder]::ObserveLogs($status,$case.Relay,$case.Player)}catch{$threw=$true}
    if($threw -ne $case.Throws -or (-not $threw -and $status.Ready -ne $case.Ready)){throw ('Failed: '+$case.Name)}
    if($status.OriginalCompressedBitstreamPreserved -or $status.UpmixApplied){throw 'Encoder claimed compressed-bitstream preservation or upmix.'}
    Write-Output ('Passed: '+$case.Name)
}
foreach($bitrate in @(384,448,640)){
    $config=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($sonyId,$bitrate)
    foreach($required in @('audio-exclusive=yes','audio-fallback-to-null=no','ad-lavc-downmix=no',('af=lavcac3enc=tospdif=yes:bitrate='+$bitrate+':minch=6'),'volume=100')){
        if(-not $config.Contains($required)){throw ('Missing: '+$required)}
    }
    foreach($forbidden in @('pan=','equalizer=','adelay=','volume=0.')){if($config.Contains($forbidden)){throw ('Unexpected DSP: '+$forbidden)}}
}
Write-Output 'Passed: isolated encoder configuration at each supported bitrate'
foreach($mode in @('Auto','Native')){
    $config=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($sonyId,640,$mode)
    if($config.Contains('pan=') -or $config.Contains('adelay=') -or $config.Contains('highpass=') -or $config.Contains('lowpass=')){throw ($mode+' changed the native input channels.')}
}
$stereo=[Sistema51.Cm6206.WindowsAc3Encoder]::BuildConfig($sonyId,640,'Stereo')
if(-not $stereo.Contains('pan=5.1|c0=c0|c1=c1|c2=0.5*c0+0.5*c1|c3=0.25*c0+0.25*c1|c4=0.5*c0|c5=0.5*c1],lavcac3enc=')){throw 'Confirmed stereo must be expanded before encoding.'}
foreach($forbidden in @('adelay=','highpass=','lowpass=','equalizer=')){if($stereo.Contains($forbidden)){throw 'Receiver DSP was applied in the stereo encoder.'}}
Write-Output 'Passed: native/auto preserve and confirmed stereo expands without receiver DSP'
