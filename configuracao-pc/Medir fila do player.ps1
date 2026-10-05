# Consulta somente leitura: mpv IPC e logs da rota global 5.1.
# QueueMs estima audio entregue ao pipe menos audio reproduzido pelo mpv.
# Nao mede latencia fisica do HDMI, da TV, do UD851B ou dos amplificadores.
param(
    [string]$PipeName = 'SistemaArtesanalAudio51',
    [int]$ConnectTimeoutMs = 1500,
    [int]$ReplyTimeoutMs = 1200,
    [int]$NextLogTimeoutMs = 1800
)

$ErrorActionPreference = 'Stop'
$relayLog = Join-Path $PSScriptRoot 'audio-sistema.log'
$mpvLog = $relayLog + '.mpv.log'
$culture = [Globalization.CultureInfo]::InvariantCulture
$linePattern = '^(?<date>\d{4}-\d\d-\d\d \d\d:\d\d:\d\d\.\d{3}) (?<fields>.+)$'

function Get-RecentRelayMetrics {
    if (-not (Test-Path -LiteralPath $relayLog)) { return @() }
    $items = @()
    foreach ($line in (Get-Content -LiteralPath $relayLog -Tail 60 -ErrorAction SilentlyContinue)) {
        if ($line -notmatch $linePattern) { continue }
        $stamp = [datetime]::ParseExact($Matches.date, 'yyyy-MM-dd HH:mm:ss.fff', $culture)
        $fields = $Matches.fields
        $values = @{}
        foreach ($match in [regex]::Matches($fields, '(?<key>[A-Za-z][A-Za-z0-9]*)=(?<value>\S+)')) {
            $values[$match.Groups['key'].Value] = $match.Groups['value'].Value
        }
        if ($values.ContainsKey('sentFrames')) {
            $items += [pscustomobject]@{
                Time = $stamp
                Format = 'frames'
                SentFrames = [long]::Parse($values.sentFrames, $culture)
                CapturedFrames = [long]::Parse($values.capturedFrames, $culture)
                PaddingSilenceFrames = [long]::Parse($values.paddingSilenceFrames, $culture)
                DroppedFrames = [long]::Parse($values.droppedFrames, $culture)
                Discontinuities = [long]::Parse($values.discontinuities, $culture)
                TimestampErrors = [long]::Parse($values.timestampErrors, $culture)
                QueueMs = [double]::Parse(($values.queueMs -replace ',', '.'), $culture)
                QueueHighMs = [double]::Parse(($values.queueHighMs -replace ',', '.'), $culture)
                MaxWriteMs = [double]::Parse(($values.maxWriteMs -replace ',', '.'), $culture)
                MaxCaptureGapMs = [double]::Parse(($values.maxCaptureGapMs -replace ',', '.'), $culture)
                InFlightFrames = if ($values.ContainsKey('inFlightFrames')) { [long]::Parse($values.inFlightFrames, $culture) } else { $null }
                PoolAllocations = if ($values.ContainsKey('poolAllocations')) { [long]::Parse($values.poolAllocations, $culture) } else { $null }
                PeakAbs = $values.peakAbs
                OverOneSamples = $values.overOneSamples
                Ticks = $null
                Underflows = $null
                Drops = $null
            }
        } elseif ($values.ContainsKey('ticks')) {
            $items += [pscustomobject]@{
                Time = $stamp
                Format = 'ticks-legacy'
                SentFrames = [long]::Parse($values.ticks, $culture) * 480L
                CapturedFrames = $null
                PaddingSilenceFrames = $null
                DroppedFrames = $null
                Discontinuities = $null
                TimestampErrors = $null
                QueueMs = [double]::Parse(($values.queueMs -replace ',', '.'), $culture)
                QueueHighMs = $null
                MaxWriteMs = $null
                MaxCaptureGapMs = $null
                Ticks = [long]::Parse($values.ticks, $culture)
                Underflows = [long]::Parse($values.underflows, $culture)
                Drops = [long]::Parse($values.drops, $culture)
            }
        }
    }
    return @($items)
}

function Get-MpvProperty([string]$name) {
    $script:requestId++
    $request = @{ command = @('get_property', $name); request_id = $script:requestId } | ConvertTo-Json -Compress
    $script:writer.WriteLine($request)
    $deadline = [Diagnostics.Stopwatch]::StartNew()
    while ($deadline.ElapsedMilliseconds -lt $ReplyTimeoutMs) {
        $remaining = [Math]::Max(1, $ReplyTimeoutMs - [int]$deadline.ElapsedMilliseconds)
        $read = $script:reader.ReadLineAsync()
        if (-not $read.Wait($remaining)) { throw "Sem resposta IPC para $name em ${ReplyTimeoutMs} ms" }
        if ($null -eq $read.Result) { throw 'Conexao IPC encerrada pelo mpv' }
        try { $reply = $read.Result | ConvertFrom-Json } catch { continue }
        if ($reply.request_id -ne $script:requestId) { continue }
        if ($reply.error -ne 'success') { return $null }
        return $reply.data
    }
    return $null
}

$pipe = $null
$reader = $null
$writer = $null
try {
    $pipe = [IO.Pipes.NamedPipeClientStream]::new('.', $PipeName, [IO.Pipes.PipeDirection]::InOut, [IO.Pipes.PipeOptions]::Asynchronous)
    $pipe.Connect($ConnectTimeoutMs)
    $utf8 = [Text.UTF8Encoding]::new($false)
    $reader = [IO.StreamReader]::new($pipe, $utf8, $false, 4096, $true)
    $writer = [IO.StreamWriter]::new($pipe, $utf8, 4096, $true)
    $writer.AutoFlush = $true
    $script:reader = $reader
    $script:writer = $writer
    $script:requestId = 0

    $before = @(Get-RecentRelayMetrics)
    $sampleTime = Get-Date
    $audioPts = Get-MpvProperty 'audio-pts'
    $sampleTime = $sampleTime.AddTicks([long](((Get-Date) - $sampleTime).Ticks / 2))
    $timePos = Get-MpvProperty 'time-pos'
    $playbackTime = Get-MpvProperty 'playback-time'
    $aoDelay = Get-MpvProperty 'ao-delay' # Geralmente indisponivel; audio-pts ja inclui o atraso do driver.
    $cacheDuration = Get-MpvProperty 'demuxer-cache-duration'
    $cacheState = Get-MpvProperty 'demuxer-cache-state'
    $activeFilters = Get-MpvProperty 'af'
    $inputRateHz = 48000.0
    $filterDescription = $activeFilters | ConvertTo-Json -Depth 10 -Compress
    if ($filterDescription -match 'asetrate=(?:r=|sample_rate=)?(?<rate>\d+)') {
        $inputRateHz = [double]::Parse($Matches.rate, $culture)
    }

    $after = @(Get-RecentRelayMetrics)
    $waitClock = [Diagnostics.Stopwatch]::StartNew()
    while (($after.Count -eq 0 -or $after[-1].Time -le $sampleTime) -and $waitClock.ElapsedMilliseconds -lt $NextLogTimeoutMs) {
        Start-Sleep -Milliseconds 60
        $after = @(Get-RecentRelayMetrics)
    }
    $all = @($before + $after | Sort-Object Time -Unique)
    $previous = @($all | Where-Object { $_.Time -le $sampleTime } | Select-Object -Last 1)
    $next = @($all | Where-Object { $_.Time -gt $sampleTime } | Select-Object -First 1)
    $latest = if ($all.Count -gt 0) { $all[-1] } else { $null }
    $sentFramesAtSample = $null
    $method = 'unavailable'
    if ($previous.Count -gt 0 -and $next.Count -gt 0) {
        $spanMs = ($next[0].Time - $previous[0].Time).TotalMilliseconds
        if ($spanMs -gt 0) {
            $fraction = ($sampleTime - $previous[0].Time).TotalMilliseconds / $spanMs
            if ($previous[0].Format -eq $next[0].Format) {
                $sentFramesAtSample = $previous[0].SentFrames + $fraction * ($next[0].SentFrames - $previous[0].SentFrames)
            }
            $method = 'interpolated-between-relay-log-lines'
        }
    }
    $playedPts = if ($null -ne $audioPts) { [double]$audioPts } elseif ($null -ne $timePos) { [double]$timePos } else { $null }
    $queueMs = if ($null -ne $sentFramesAtSample -and $null -ne $playedPts) {
        [Math]::Round((($sentFramesAtSample / $inputRateHz) - $playedPts) * 1000.0, 1)
    } else { $null }

    $mpvUnderruns = $null
    if (Test-Path -LiteralPath $mpvLog) {
        $mpvUnderruns = @(Select-String -LiteralPath $mpvLog -SimpleMatch 'Audio device underrun detected.' -ErrorAction SilentlyContinue).Count
    }
    $cacheFwBytes = $null
    if ($cacheState -and $cacheState.PSObject.Properties['fw-bytes']) { $cacheFwBytes = $cacheState.'fw-bytes' }

    [pscustomobject]@{
        Success = $true
        SampleTime = $sampleTime.ToString('yyyy-MM-dd HH:mm:ss.fff')
        QueueMs = $queueMs
        QueueMethod = if ($null -ne $sentFramesAtSample) { $method } else { 'unavailable' }
        CalibratedInputRateHz = $inputRateHz
        NewestInputPtsSeconds = if ($null -ne $sentFramesAtSample) { [Math]::Round($sentFramesAtSample / $inputRateHz, 6) } else { $null }
        AudioPtsSeconds = $audioPts
        TimePosSeconds = $timePos
        PlaybackTimeSeconds = $playbackTime
        AoDelaySeconds = $aoDelay
        DemuxerCacheDurationSeconds = $cacheDuration
        DemuxerForwardBytes = $cacheFwBytes
        SentFramesAtSample = if ($null -ne $sentFramesAtSample) { [Math]::Round($sentFramesAtSample) } else { $null }
        RelayMetricsFormat = if ($latest) { $latest.Format } else { $null }
        RelayCapturedFrames = if ($latest) { $latest.CapturedFrames } else { $null }
        RelaySentFrames = if ($latest) { $latest.SentFrames } else { $null }
        RelayPaddingSilenceFrames = if ($latest) { $latest.PaddingSilenceFrames } else { $null }
        RelayDroppedFrames = if ($latest) { $latest.DroppedFrames } else { $null }
        RelayDiscontinuities = if ($latest) { $latest.Discontinuities } else { $null }
        RelayTimestampErrors = if ($latest) { $latest.TimestampErrors } else { $null }
        RelayQueueHighMs = if ($latest) { $latest.QueueHighMs } else { $null }
        RelayMaxWriteMs = if ($latest) { $latest.MaxWriteMs } else { $null }
        RelayMaxCaptureGapMs = if ($latest) { $latest.MaxCaptureGapMs } else { $null }
        RelayInFlightFrames = if ($latest) { $latest.InFlightFrames } else { $null }
        RelayPoolAllocations = if ($latest) { $latest.PoolAllocations } else { $null }
        RelayPeakAbs = if ($latest) { $latest.PeakAbs } else { $null }
        RelayOverOneSamples = if ($latest) { $latest.OverOneSamples } else { $null }
        RelayTicks = if ($latest) { $latest.Ticks } else { $null }
        RelayUnderflows = if ($latest) { $latest.Underflows } else { $null }
        RelayDrops = if ($latest) { $latest.Drops } else { $null }
        RelayQueueMs = if ($latest) { $latest.QueueMs } else { $null }
        MpvDeviceUnderruns = $mpvUnderruns
        Note = 'Estimativa da fila de software ate a posicao mpv; nao e latencia acustica. Interpolacao entre logs de 1 s pode perder travamentos dentro do intervalo.'
    } | ConvertTo-Json -Depth 5 -Compress
} catch {
    [pscustomobject]@{
        Success = $false
        Error = $_.Exception.Message
        Hint = 'Execute depois de iniciar o relay mpv com --input-ipc-server=\\.\pipe\SistemaArtesanalAudio51.'
    } | ConvertTo-Json -Compress
} finally {
    if ($writer) { $writer.Dispose() }
    if ($reader) { $reader.Dispose() }
    if ($pipe) { $pipe.Dispose() }
}
