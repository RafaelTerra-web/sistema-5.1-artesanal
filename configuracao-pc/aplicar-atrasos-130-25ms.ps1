$ErrorActionPreference = 'Stop'
$sourcePath = Join-Path $PSScriptRoot 'delay-5.1-70ms.txt'
$targetPath = 'C:\Program Files\EqualizerAPO\config\delay-5.1-70ms.txt'
$statusPath = Join-Path $PSScriptRoot 'atrasos-130-25ms-status.json'
$backupPath = Join-Path $PSScriptRoot 'delay-antes-130-25ms.txt'
try {
    $source = Get-Content -LiteralPath $sourcePath -Raw
    if ($source -notmatch '(?m)^\s*Channel: 1 2 5 6\r?\n\s*Delay: 130 ms\s*$' -or
        $source -notmatch '(?m)^\s*Channel: 3 4\r?\n\s*Delay: 25 ms\s*$') {
        throw 'O perfil de origem nao contem os atrasos solicitados por grupo.'
    }
    $mainConfig = Get-Content -LiteralPath 'C:\Program Files\EqualizerAPO\config\config.txt' -Raw
    if ($mainConfig -notmatch '(?m)^\s*Include:\s*delay-5\.1-70ms\.txt\s*$') {
        throw 'A configuracao principal nao inclui o perfil atualizado.'
    }
    if (-not (Test-Path -LiteralPath $backupPath)) {
        Copy-Item -LiteralPath $targetPath -Destination $backupPath
    }
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
    $installedHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'A copia instalada difere da origem.' }
    [ordered]@{
        Success = $true
        DelaysMs = [ordered]@{FL = 130; FR = 130; CEN = 25; LFE = 25; SL = 130; SR = 130}
        InstalledPath = $targetPath
        InstalledSHA256 = $installedHash
        BackupPath = $backupPath
        AudioServiceStatus = (Get-Service -Name AudioSrv).Status.ToString()
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statusPath -Encoding UTF8
} catch {
    [ordered]@{Success = $false; Error = $_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
    exit 1
}
