# Gera apenas um arquivo local. Nao reproduz audio.
$ErrorActionPreference = 'Stop'
$rate = 48000
$channels = 6
$seconds = 27
$frames = $rate * $seconds
$dataLength = $frames * $channels * 2
$testPath = Join-Path $PSScriptRoot 'teste-longo-5.1.wav'
$writer = [System.IO.BinaryWriter]::new([System.IO.File]::Open($testPath, [System.IO.FileMode]::Create))
try {
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('RIFF'))
    $writer.Write([uint32](60 + $dataLength))
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('WAVEfmt '))
    $writer.Write([uint32]40)
    $writer.Write([uint16]65534)
    $writer.Write([uint16]$channels)
    $writer.Write([uint32]$rate)
    $writer.Write([uint32]($rate * $channels * 2))
    $writer.Write([uint16]($channels * 2))
    $writer.Write([uint16]16)
    $writer.Write([uint16]22)
    $writer.Write([uint16]16)
    $writer.Write([uint32]0x3F)
    $writer.Write(([Guid]'00000001-0000-0010-8000-00aa00389b71').ToByteArray())
    $writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
    $writer.Write([uint32]$dataLength)
    $samples = [byte[]]::new($dataLength)
    # CEN, SL, SR, FL, FR, LFE, FL, FR. Tom 1,5 s, pico -24 dBFS.
    $sequence = @(2, 4, 5, 0, 1, 3, 0, 1)
    for ($eventIndex = 0; $eventIndex -lt $sequence.Count; $eventIndex++) {
        $channel = $sequence[$eventIndex]
        $startFrame = [int]((6 + $eventIndex * 2.5) * $rate)
        $toneFrames = [int](1.5 * $rate)
        $frequency = if ($channel -eq 3) { 60 } else { 500 }
        for ($i = 0; $i -lt $toneFrames; $i++) {
            $ramp = [Math]::Min(1, [Math]::Min($i, $toneFrames - 1 - $i) / 960.0)
            $sample = [int16][Math]::Round(2068 * $ramp * [Math]::Sin(2 * [Math]::PI * $frequency * $i / $rate))
            $bytes = [BitConverter]::GetBytes($sample)
            $offset = (($startFrame + $i) * $channels + $channel) * 2
            $samples[$offset] = $bytes[0]
            $samples[$offset + 1] = $bytes[1]
        }
    }
    $writer.Write($samples)
} finally {
    $writer.Dispose()
}
