$ErrorActionPreference = 'Stop'
$statusPath = Join-Path $PSScriptRoot 'atualizacao-delay-90ms-status.json'
$sourcePath = Join-Path $PSScriptRoot 'delay-5.1-70ms.txt'
$targetPath = 'C:\Program Files\EqualizerAPO\config\delay-5.1-70ms.txt'
$backupPath = Join-Path $PSScriptRoot 'delay-antes-90ms.txt'
try {
    if (-not (Test-Path -LiteralPath $backupPath)) {
        Copy-Item -LiteralPath $targetPath -Destination $backupPath
    }
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    $actual = Get-Content -LiteralPath $targetPath -Raw
    if ($actual -notmatch '(?m)^\s*Delay:\s*90 ms\s*$') {
        throw 'O arquivo instalado nao contem Delay: 90 ms.'
    }
    $mainConfig = Get-Content -LiteralPath 'C:\Program Files\EqualizerAPO\config\config.txt' -Raw
    if ($mainConfig -notmatch '(?m)^\s*Include:\s*delay-5\.1-70ms\.txt\s*$') {
        throw 'A configuracao principal nao inclui o perfil atualizado.'
    }
    [ordered]@{
        Success = $true
        DelayMs = 90
        Channels = @(1, 2, 5, 6)
        InstalledPath = $targetPath
        InstalledSHA256 = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
        BackupPath = $backupPath
        AudioServiceStatus = (Get-Service -Name AudioSrv).Status.ToString()
    } | ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
} catch {
    [ordered]@{Success = $false; Error = $_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
    exit 1
}
