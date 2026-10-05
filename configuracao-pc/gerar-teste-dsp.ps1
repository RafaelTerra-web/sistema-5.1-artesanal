# Gera arquivos locais de verificacao. Nao reproduz sons.
param([string]$Destino = $PSScriptRoot)
$rate = 48000
$channels = 6
$frames = $rate * 2
$dataLength = $frames * $channels * 2
foreach ($kind in @('silencio', 'impulso', 'teste-caixas')) {
    $frames = if ($kind -eq 'teste-caixas') { $rate * 9 } else { $rate * 2 }
    $dataLength = $frames * $channels * 2
    $path = Join-Path $Destino ($kind + '-5.1.wav')
    $stream = [System.IO.File]::Open($path, [System.IO.FileMode]::Create)
    $writer = [System.IO.BinaryWriter]::new($stream)
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
        $writer.Write([uint32]0x60F)
        $writer.Write(([Guid]'00000001-0000-0010-8000-00aa00389b71').ToByteArray())
        $writer.Write([System.Text.Encoding]::ASCII.GetBytes('data'))
        $writer.Write([uint32]$dataLength)
        $samples = [byte[]]::new($dataLength)
        if ($kind -eq 'impulso') {
            for ($ch = 0; $ch -lt $channels; $ch++) {
                $offset = (24000 * $channels + $ch) * 2
                $samples[$offset] = 0
                $samples[$offset + 1] = 16
            }
        }
        if ($kind -eq 'teste-caixas') {
            # Ordem: FL, FR, central, subwoofer, surround L, surround R.
            # Nivel de pico aproximadamente -30 dBFS, com rampas suaves.
            for ($ch = 0; $ch -lt $channels; $ch++) {
                $startFrame = [int]((1 + $ch * 1.25) * $rate)
                $toneFrames = [int](0.5 * $rate)
                $frequency = if ($ch -eq 3) { 60 } else { 500 }
                for ($i = 0; $i -lt $toneFrames; $i++) {
                    $ramp = [Math]::Min(1, [Math]::Min($i, $toneFrames - 1 - $i) / 240.0)
                    $sample = [int16][Math]::Round(1036 * $ramp * [Math]::Sin(2 * [Math]::PI * $frequency * $i / $rate))
                    $sampleBytes = [BitConverter]::GetBytes($sample)
                    $offset = (($startFrame + $i) * $channels + $ch) * 2
                    $samples[$offset] = $sampleBytes[0]
                    $samples[$offset + 1] = $sampleBytes[1]
                }
            }
        }
        $writer.Write($samples)
    } finally {
        $writer.Dispose()
    }
}
