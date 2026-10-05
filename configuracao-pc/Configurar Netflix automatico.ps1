param([ValidateSet('Ativar','Desfazer','Status')][string]$Acao = 'Ativar')
$ErrorActionPreference = 'Stop'
$base = $PSScriptRoot
$python = 'C:\Python314\python.exe'
$backend = Join-Path $base 'netflix-dolby51-automatico\cadmiumconfig.py'
$statePath = Join-Path $base 'netflix-dolby51-automatico\estado.json'
$edgePath = Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'
$cookieDb = Join-Path $env:LOCALAPPDATA 'Microsoft\Edge\User Data\Default\Network\Cookies'

function Get-EdgeProcesses {
    @(Get-CimInstance Win32_Process -Filter "Name='msedge.exe'")
}

if ($Acao -eq 'Status') {
    if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw }
    else { '{"Configurado":false}' }
    return
}
if ($Acao -eq 'Ativar') {
    $saved = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
    $state = [ordered]@{Configurado=$true;AjusteUrlHabilitado=$true;Perfil='Default';
        Metodo='Parametros de URL na abertura do app Netflix instalado';AtualizadoUtc=[DateTime]::UtcNow.ToString('o');
        Backup=if($saved){$saved.Backup}else{$null};
        Validacao='Usuario confirmou 5.1 ao abrir o episodio diretamente e ao abrir pela pagina inicial, sem favorito.'}
    $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
    $shell = New-Object -ComObject WScript.Shell
    foreach ($folder in @([Environment]::GetFolderPath('Desktop'), [Environment]::GetFolderPath('Programs'))) {
        $shortcut = $shell.CreateShortcut((Join-Path $folder 'Netflix - Dolby 5.1.lnk'))
        $shortcut.TargetPath = Join-Path $env:WINDIR 'System32\WindowsPowerShell\v1.0\powershell.exe'
        $shortcut.Arguments = '-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "' + (Join-Path $base 'Abrir Netflix 5.1.ps1') + '"'
        $shortcut.WorkingDirectory = $base
        $shortcut.IconLocation = $edgePath + ',0'
        $shortcut.Description = 'Netflix instalada com o ajuste 5.1 aplicado automaticamente ao abrir.'
        $shortcut.Save()
    }
    $state | ConvertTo-Json -Depth 5
    & (Join-Path $base 'Abrir Netflix 5.1.ps1')
    return
}
if (-not (Test-Path -LiteralPath $python)) { throw 'Python usado pela configuracao nao foi encontrado.' }
if (-not (Test-Path -LiteralPath $cookieDb)) { throw 'Perfil Default do Edge nao encontrado.' }
$previous = if (Test-Path -LiteralPath $statePath) { Get-Content -LiteralPath $statePath -Raw | ConvertFrom-Json } else { $null }
if ($Acao -eq 'Desfazer' -and (-not $previous -or -not $previous.Backup)) {
    throw 'Nenhum backup do ajuste automatico foi encontrado.'
}
$edgeWasRunning = (Get-EdgeProcesses).Count -gt 0
$edgeClosed = $false
try {
    # Close the browser process gracefully first, allowing session state to flush.
    $main = @(Get-EdgeProcesses | Where-Object { $_.CommandLine -notmatch '--type=' })
    foreach ($process in $main) {
        Start-Process -FilePath taskkill.exe -ArgumentList @('/PID', $process.ProcessId) -Wait -WindowStyle Hidden `
            -RedirectStandardOutput (Join-Path $base 'netflix-dolby51-automatico\encerramento-edge.log') `
            -RedirectStandardError (Join-Path $base 'netflix-dolby51-automatico\encerramento-edge-erros.log')
    }
    $timer = [Diagnostics.Stopwatch]::StartNew()
    while ((Get-EdgeProcesses).Count -gt 0 -and $timer.ElapsedMilliseconds -lt 8000) {
        Start-Sleep -Milliseconds 200
    }
    if ((Get-EdgeProcesses).Count -gt 0) {
        # Startup Boost can retain background processes after the windows close.
        # Finish that process tree before opening its exclusively locked database.
        foreach ($process in Get-EdgeProcesses) { Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue }
        $timer.Restart()
        while ((Get-EdgeProcesses).Count -gt 0 -and $timer.ElapsedMilliseconds -lt 5000) { Start-Sleep -Milliseconds 200 }
        if ((Get-EdgeProcesses).Count -gt 0) { throw 'O Edge ainda esta aberto. A preferencia nao foi modificada.' }
    }
    $edgeClosed = $true
    $backup = if ($Acao -eq 'Desfazer') { $previous.Backup }
        else { Join-Path $base ('netflix-dolby51-automatico\backup-cadmiumconfig-' + [Guid]::NewGuid().ToString('N') + '.json') }
    $arguments = @($backend, '--cookie-db', $cookieDb)
    if ($Acao -eq 'Desfazer') { $arguments += @('--restore', $backup) }
    else { $arguments += @('--apply', '--backup', $backup) }
    $raw = & $python @arguments
    $backendExit = $LASTEXITCODE
    if ($backendExit -ne 0) { throw ('Preferencia nao aplicada: ' + ($raw -join [Environment]::NewLine)) }
    $result = ($raw -join '') | ConvertFrom-Json
    if ($Acao -eq 'Desfazer') {
        $state = [ordered]@{Configurado=$false;AjusteUrlHabilitado=$false;Backup=$backup;AtualizadoUtc=[DateTime]::UtcNow.ToString('o')}
    } else {
        $state = [ordered]@{Configurado=$true;Perfil='Default';Cookie='cadmiumconfig';Backup=$backup;
            AtualizadoUtc=[DateTime]::UtcNow.ToString('o');ExpiraUtc=[DateTime]::UtcNow.AddDays(365).ToString('o');
            Metodo='Preferencia persistente nativa do player Netflix';Resultado=$result}
    }
    $state | ConvertTo-Json -Depth 5 | Set-Content -LiteralPath $statePath -Encoding UTF8
    $state | ConvertTo-Json -Depth 5
} finally {
    if ($edgeClosed) {
        if ($edgeWasRunning) { Start-Process -FilePath $edgePath -ArgumentList '--restore-last-session' }
        & (Join-Path $base 'Abrir Netflix 5.1.ps1')
    }
}
