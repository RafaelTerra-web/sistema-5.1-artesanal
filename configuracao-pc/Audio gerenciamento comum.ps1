# Lifecycle/configuration helpers. These functions do not change audio buffers.
function Wait-AudioMutex([Threading.Mutex]$Mutex, [int]$TimeoutMs) {
    try { return $Mutex.WaitOne($TimeoutMs) }
    catch [Threading.AbandonedMutexException] {
        # WaitOne transferred ownership when the previous process crashed.
        return $true
    }
}

function Write-AudioBytesAtomic([string]$Path, [byte[]]$Bytes) {
    $fullPath = [IO.Path]::GetFullPath($Path)
    $temporary = $fullPath + '.tmp.' + [Guid]::NewGuid().ToString('N')
    $stream = $null
    try {
        $stream = [IO.FileStream]::new($temporary,[IO.FileMode]::CreateNew,[IO.FileAccess]::Write,[IO.FileShare]::None,4096,[IO.FileOptions]::WriteThrough)
        $stream.Write($Bytes,0,$Bytes.Length)
        $stream.Flush($true)
        $stream.Dispose(); $stream = $null
        if ([IO.File]::Exists($fullPath)) { [IO.File]::Replace($temporary,$fullPath,[NullString]::Value) }
        else { [IO.File]::Move($temporary,$fullPath) }
    } finally {
        if ($stream) { $stream.Dispose() }
        if ([IO.File]::Exists($temporary)) { [IO.File]::Delete($temporary) }
    }
}

function New-AudioTextFile([string]$Path, [string]$Text, [ValidateSet('ASCII','UTF8')][string]$Encoding = 'UTF8') {
    $encoder = if ($Encoding -eq 'ASCII') { [Text.Encoding]::ASCII } else { [Text.UTF8Encoding]::new($false) }
    [pscustomobject]@{Path=[IO.Path]::GetFullPath($Path);Bytes=$encoder.GetBytes($Text)}
}

function Set-AudioFileTransaction([object[]]$Files) {
    # Callers hold the shared configuration mutex. Each replacement is atomic;
    # rollback retains each file's actual previous bytes, including its template.
    $snapshots = @(); $committed = @()
    foreach ($file in $Files) {
        if ($snapshots.Path -contains $file.Path) { throw 'Arquivo repetido na transacao de audio.' }
        $exists = [IO.File]::Exists($file.Path)
        $snapshots += [pscustomobject]@{Path=$file.Path;Exists=$exists;Bytes=if($exists){[IO.File]::ReadAllBytes($file.Path)}else{$null}}
    }
    try {
        foreach ($file in $Files) {
            Write-AudioBytesAtomic -Path $file.Path -Bytes $file.Bytes
            $committed += $file.Path
        }
    } catch {
        $writeError = $_; $rollbackErrors = @()
        [array]::Reverse($committed)
        foreach ($path in $committed) {
            $snapshot = $snapshots | Where-Object Path -EQ $path | Select-Object -First 1
            try {
                if ($snapshot.Exists) { Write-AudioBytesAtomic -Path $path -Bytes $snapshot.Bytes }
                elseif ([IO.File]::Exists($path)) { [IO.File]::Delete($path) }
            } catch { $rollbackErrors += ($path + ': ' + $_.Exception.Message) }
        }
        if ($rollbackErrors.Count) { throw ('Falha ao salvar e restaurar configuracao: ' + $writeError.Exception.Message + ' | ' + ($rollbackErrors -join ' | ')) }
        throw $writeError
    }
}

function Test-AudioProcessIdentity($Process, [string]$ScriptPath) {
    if (-not $Process -or $Process.Name -notin @('powershell.exe','pwsh.exe')) { return $false }
    $escaped = [regex]::Escape([IO.Path]::GetFullPath($ScriptPath))
    return [bool]($Process.CommandLine -match ('(?i)(?:^|\s)-File\s+(?:"' + $escaped + '"|' + $escaped + '(?=\s|$))'))
}

function Get-AudioRunnerProcess([string]$BaseDir) {
    $pidPath = Join-Path $BaseDir 'audio-sistema.pid'
    if (-not [IO.File]::Exists($pidPath)) { return $null }
    $runnerId = 0
    try { $pidText = [IO.File]::ReadAllText($pidPath).Trim() } catch { return $null }
    if (-not [int]::TryParse($pidText,[ref]$runnerId) -or $runnerId -le 0) { return $null }
    $process = Get-CimInstance Win32_Process -Filter "ProcessId=$runnerId" -ErrorAction SilentlyContinue
    if (Test-AudioProcessIdentity $process (Join-Path $BaseDir 'rodar-audio-sistema.ps1')) { return $process }
    return $null
}

function Get-AudioPlayerProcesses($Runner, [string]$BaseDir) {
    if (-not $Runner) { return @() }
    $expectedExe = [IO.Path]::GetFullPath((Join-Path $BaseDir 'mpv-portatil\mpv.exe'))
    $expectedConfig = [regex]::Escape([IO.Path]::GetFullPath((Join-Path $BaseDir 'mpv-sistema-dolby.conf')))
    @(Get-CimInstance Win32_Process -Filter "Name='mpv.exe' AND ParentProcessId=$($Runner.ProcessId)" -ErrorAction SilentlyContinue |
        Where-Object { $_.ExecutablePath -eq $expectedExe -and $_.CreationDate -ge $Runner.CreationDate -and
            $_.CommandLine -match ('(?i)(?:^|\s)--include=(?:"' + $expectedConfig + '"|' + $expectedConfig + '(?=\s|$))') })
}

function Invoke-AudioSoundTool([string]$BaseDir, [string[]]$Arguments) {
    $soundTool = Join-Path $BaseDir 'ferramentas\soundvolumeview\SoundVolumeView.exe'
    if (-not [IO.File]::Exists($soundTool)) { throw 'SoundVolumeView nao encontrado na pasta ferramentas.' }
    $toolProcess = Start-Process -FilePath $soundTool -WindowStyle Hidden -ArgumentList $Arguments -PassThru
    try {
        if (-not $toolProcess.WaitForExit(10000)) {
            try { $toolProcess.Kill() } catch { }
            throw 'SoundVolumeView nao respondeu em 10 segundos. Verifique a saida HDMI.'
        }
        if ($toolProcess.ExitCode -ne 0) { throw ('SoundVolumeView encerrou com codigo ' + $toolProcess.ExitCode + '.') }
    } finally { $toolProcess.Dispose() }
}
