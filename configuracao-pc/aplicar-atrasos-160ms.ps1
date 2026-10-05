$ErrorActionPreference = 'Stop'
$sourcePath = Join-Path $PSScriptRoot 'delay-5.1-70ms.txt'
$targetPath = 'C:\Program Files\EqualizerAPO\config\delay-5.1-70ms.txt'
$statusPath = Join-Path $PSScriptRoot 'atrasos-160ms-status.json'
try {
    $source = Get-Content -LiteralPath $sourcePath -Raw
    if ($source -notmatch '(?m)^\s*Channel: 1 2 5 6\r?\n\s*Delay: 160 ms\s*$' -or
        ([regex]::Matches($source, '(?m)^\s*Delay:').Count -ne 1)) {
        throw 'A origem nao contem somente 160 ms nos canais 1/2/5/6.'
    }
    $mainConfig = Get-Content -LiteralPath 'C:\Program Files\EqualizerAPO\config\config.txt' -Raw
    if ($mainConfig -notmatch '(?m)^\s*Include:\s*delay-5\.1-70ms\.txt\s*$') {
        throw 'A configuracao principal nao inclui o perfil atualizado.'
    }
    Copy-Item -LiteralPath $sourcePath -Destination $targetPath -Force
    $sourceHash = (Get-FileHash -LiteralPath $sourcePath -Algorithm SHA256).Hash
    $installedHash = (Get-FileHash -LiteralPath $targetPath -Algorithm SHA256).Hash
    if ($installedHash -ne $sourceHash) { throw 'A copia instalada difere da origem.' }
    [ordered]@{
        Success = $true
        DelaysMs = [ordered]@{FL = 160; FR = 160; CEN = 0; LFE = 0; SL = 160; SR = 160}
        InstalledPath = $targetPath
        InstalledSHA256 = $installedHash
        AudioServiceStatus = (Get-Service -Name AudioSrv).Status.ToString()
    } | ConvertTo-Json -Depth 4 | Set-Content -LiteralPath $statusPath -Encoding UTF8
} catch {
    [ordered]@{Success = $false; Error = $_.Exception.Message} |
        ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
    exit 1
}


