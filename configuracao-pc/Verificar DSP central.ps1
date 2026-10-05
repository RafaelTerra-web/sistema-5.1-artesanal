# Sintese e medicao isoladas: o mpv escreve PCM em arquivo, sem abrir a saida HDMI.
param(
    [Parameter(Mandatory=$true)][string]$ConfigPath,
    [Parameter(Mandatory=$true)][string]$MpvPath,
    [Parameter(Mandatory=$true)][string]$WorkDirectory
)
$ErrorActionPreference = 'Stop'
$config = [IO.File]::ReadAllText($ConfigPath)
$match = [regex]::Match($config,'(?m)^af=(lavfi=\[[^\r\n]+\]),lavcac3enc=')
if (-not $match.Success) { throw 'Filtro PCM da configuracao nao encontrado.' }
$filter = $match.Groups[1].Value
if ($filter -notmatch 'asplit@cenBass=2' -or $filter -notmatch 'amix@cenBass=inputs=2:normalize=0') {
    throw 'O envio de graves da central nao esta no filtro.'
}
# O teste anterior deixa o volume mestre isolado em mudo para validar a persistencia.
# Remova apenas esse mudo da copia do filtro usada no arquivo de medicao.
$filter = [regex]::Replace($filter,'volume@master51=volume=[0-9.]+:precision=double','volume@master51=volume=1.00:precision=double')
$inputPath = Join-Path $WorkDirectory 'central-sintetica.wav'
$outputPath = Join-Path $WorkDirectory 'central-processada.wav'
$sampleRate = 48000; $channels = 6; $bits = 16; $frames = 2 * $sampleRate
$frameBytes = $channels * ($bits / 8); $dataBytes = $frames * $frameBytes
$pcm = [byte[]]::new($dataBytes)
for ($frame=0; $frame -lt $frames; $frame++) {
    $frequency = if ($frame -lt $sampleRate) { 80.0 } else { 300.0 }
    $time = ($frame % $sampleRate) / [double]$sampleRate
    $value = [int][math]::Round(0.15 * [math]::Sin(2 * [math]::PI * $frequency * $time) * 32767)
    $offset = $frame * $frameBytes + 2 * 2 # canal c2 (central)
    $pcm[$offset] = [byte]($value -band 255)
    $pcm[$offset+1] = [byte](($value -shr 8) -band 255)
}
$stream = [IO.File]::Create($inputPath)
$writer = [IO.BinaryWriter]::new($stream)
try {
    $ascii = [Text.Encoding]::ASCII
    $writer.Write($ascii.GetBytes('RIFF')); $writer.Write([uint32](36 + $dataBytes)); $writer.Write($ascii.GetBytes('WAVE'))
    $writer.Write($ascii.GetBytes('fmt ')); $writer.Write([uint32]16)
    $writer.Write([uint16]1); $writer.Write([uint16]$channels); $writer.Write([uint32]$sampleRate)
    $writer.Write([uint32]($sampleRate * $frameBytes)); $writer.Write([uint16]$frameBytes); $writer.Write([uint16]$bits)
    $writer.Write($ascii.GetBytes('data')); $writer.Write([uint32]$dataBytes); $writer.Write($pcm)
} finally { $writer.Dispose() }

$mpvOutput = & $MpvPath --no-config --no-video --really-quiet --audio-channels=5.1 --ao=pcm "--ao-pcm-file=$outputPath" "--af=$filter" $inputPath 2>&1 | Out-String
if ($LASTEXITCODE -ne 0 -or -not [IO.File]::Exists($outputPath)) {
    throw ('mpv nao processou o teste isolado: ' + $mpvOutput)
}

$reader = [IO.BinaryReader]::new([IO.File]::OpenRead($outputPath))
try {
    $ascii = [Text.Encoding]::ASCII
    if ($ascii.GetString($reader.ReadBytes(4)) -ne 'RIFF') { throw 'Saida PCM sem cabecalho RIFF.' }
    $reader.ReadUInt32() | Out-Null
    if ($ascii.GetString($reader.ReadBytes(4)) -ne 'WAVE') { throw 'Saida PCM sem cabecalho WAVE.' }
    $format = 0; $outChannels = 0; $rate = 0; $outBits = 0; $blockAlign = 0; $dataStart = -1; $dataLength = 0
    while ($reader.BaseStream.Position + 8 -le $reader.BaseStream.Length) {
        $id = $ascii.GetString($reader.ReadBytes(4)); $size = [long]$reader.ReadUInt32(); $start = $reader.BaseStream.Position
        if ($id -eq 'fmt ') {
            $format = [int]$reader.ReadUInt16(); $outChannels = [int]$reader.ReadUInt16(); $rate = [int]$reader.ReadUInt32()
            $reader.ReadUInt32() | Out-Null; $blockAlign = [int]$reader.ReadUInt16(); $outBits = [int]$reader.ReadUInt16()
            if ($format -eq 65534 -and $size -ge 40) {
                $reader.BaseStream.Position = $start + 24
                $format = [int]$reader.ReadUInt16() # subtipo PCM(1) ou IEEE float(3)
            }
        } elseif ($id -eq 'data') { $dataStart = $start; $dataLength = $size }
        $reader.BaseStream.Position = $start + $size + ($size % 2)
    }
    if ($outChannels -ne 6 -or $rate -ne 48000 -or $dataStart -lt 0 -or $dataLength -le 0 -or $dataLength -gt 32MB) {
        throw 'Formato PCM 5.1 de saida inesperado.'
    }
    $bytesPerSample = [int]($outBits / 8)
    if ($blockAlign -ne 6 * $bytesPerSample -or -not (($format -eq 1 -and $outBits -eq 16) -or ($format -eq 3 -and $outBits -eq 32))) {
        throw ('Formato de amostra nao suportado no teste: formato ' + $format + ', bits ' + $outBits)
    }
    $reader.BaseStream.Position = $dataStart
    $outPcm = $reader.ReadBytes([int]$dataLength)
} finally { $reader.Dispose() }

function Get-ChannelRms([byte[]]$Data,[int]$SampleRate,[int]$Stride,[int]$SampleBytes,[int]$SampleFormat,[int]$Channel,[double]$Begin,[double]$End) {
    $first = [int][math]::Floor($Begin * $SampleRate); $last = [int][math]::Floor($End * $SampleRate)
    if ($last * $Stride -gt $Data.Length) { throw 'A saida PCM terminou antes da janela de medicao.' }
    $power = 0.0
    for ($i=$first; $i -lt $last; $i++) {
        $position = $i * $Stride + $Channel * $SampleBytes
        $value = if ($SampleFormat -eq 1) { [BitConverter]::ToInt16($Data,$position) / 32768.0 } else { [double][BitConverter]::ToSingle($Data,$position) }
        $power += $value * $value
    }
    return [math]::Sqrt($power / ($last - $first))
}
$rms = @{}
foreach ($channel in 0..5) {
    $rms["low$channel"] = Get-ChannelRms $outPcm $rate $blockAlign $bytesPerSample $format $channel 0.3 0.8
    $rms["high$channel"] = Get-ChannelRms $outPcm $rate $blockAlign $bytesPerSample $format $channel 1.3 1.8
}
$nominal = 0.15 / [math]::Sqrt(2)
if ([math]::Abs($rms.low2 - $nominal) -gt $nominal * 0.08 -or [math]::Abs($rms.high2 - $nominal) -gt $nominal * 0.08) {
    throw ('A central foi alterada: RMS 80 Hz ' + $rms.low2 + ', 300 Hz ' + $rms.high2)
}
if ($rms.low3 -lt 0.005 -or $rms.high3 -gt $rms.low3 * 0.25) {
    throw ('Envio de graves inesperado: LFE 80 Hz ' + $rms.low3 + ', 300 Hz ' + $rms.high3)
}
foreach ($channel in @(0,1,4,5)) {
    if ($rms["low$channel"] -gt 0.0001 -or $rms["high$channel"] -gt 0.0001) {
        throw ('Vazamento para o canal ' + $channel + ' no teste de graves da central.')
    }
}
[pscustomobject]@{Central80=$rms.low2;Central300=$rms.high2;Lfe80=$rms.low3;Lfe300=$rms.high3;SampleRate=$rate;ChannelCount=$outChannels}
