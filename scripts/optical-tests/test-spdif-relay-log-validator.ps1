# Pure completed-log acceptance tests; no endpoints, streams or mpv processes are opened.
$ErrorActionPreference = 'Stop'
$workspacePath = [IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..\..'))
Add-Type -Path @(
    (Join-Path $workspacePath 'android-a34\scripts\windows-cm6206\CoreAudioFormatProbe.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifCapture.cs'),
    (Join-Path $PSScriptRoot 'WindowsSpdifRelay.cs')
)
$relayRenderId = '{0.0.0.00000000}.{87276929-efec-4166-b6b5-7fdde08a6a6e}'
$relayGoodLog = @"
[   0.004][v][cplayer] Setting option 'ad-lavc-o' = 'err_detect=crccheck+explode' (flags = 8)
[   0.174][i][cplayer] Audio  --aid=1  (ac3 6ch 48000 Hz 640 kbps)
[   0.174][v][ad] Selected decoder: ac3 - ATSC A/52A (AC-3)
[   0.194][v][ao/wasapi] Selecting device '{87276929-efec-4166-b6b5-7fdde08a6a6e}' (Alto-falantes (3- USB Sound Device))
[   0.275][i][cplayer] AO: [wasapi] 48000Hz 5.1(side) 6ch s16
"@
$relayCases = @(
    @{Name='correct USB six-channel trace'; Log=$relayGoodLog; Expected=$true},
    @{Name='stereo negotiation rejected'; Log=$relayGoodLog.Replace('5.1(side) 6ch', 'stereo 2ch'); Expected=$false},
    @{Name='another endpoint rejected'; Log=$relayGoodLog.Replace('87276929-efec-4166-b6b5-7fdde08a6a6e', '01234567-89ab-cdef-0123-456789abcdef'); Expected=$false},
    @{Name='AC-3 stereo source rejected'; Log=$relayGoodLog.Replace('(ac3 6ch', '(ac3 2ch'); Expected=$false},
    @{Name='CRC option missing rejected'; Log=$relayGoodLog.Replace('err_detect=crccheck+explode', 'err_detect=ignore_err'); Expected=$false},
    @{Name='decoder CRC failure rejected'; Log=($relayGoodLog + "`n[   1.004][w][ffmpeg/audio] ac3: frame CRC mismatch"); Expected=$false},
    @{Name='output error rejected'; Log=($relayGoodLog + "`n[   1.004][e][ao/wasapi] output failed"); Expected=$false},
    @{Name='mixed later stereo output rejected'; Log=($relayGoodLog + "`n[   1.004][i][cplayer] AO: [wasapi] 48000Hz stereo 2ch s16"); Expected=$false},
    @{Name='empty log rejected'; Log=''; Expected=$false}
)
foreach ($relayCase in $relayCases) {
    $relayValidation = [Sistema51.Cm6206.WindowsSpdifRelay]::ValidateDecoderLog($relayCase.Log, $relayRenderId)
    if ($relayValidation.Complete -ne $relayCase.Expected) { throw ('Log validator failed: ' + $relayCase.Name) }
    Write-Output ('Passed: ' + $relayCase.Name)
}
