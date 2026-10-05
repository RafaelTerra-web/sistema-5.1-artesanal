param([ValidateSet('Abrir','Parar','Status')][string]$Acao='Abrir')
$ErrorActionPreference='Stop'
$remoteDir=Join-Path $PSScriptRoot 'controle-remoto'
. (Join-Path $remoteDir 'rede-local.ps1')
$serverPath=Join-Path $remoteDir 'server.mjs'
$pidFile=Join-Path $remoteDir 'server.pid'
$connectionFile=Join-Path $remoteDir 'connection-private.json'
$port=8787
function Get-RemoteProcess {
    if(-not(Test-Path -LiteralPath $pidFile)){return $null}
    $text=(Get-Content -LiteralPath $pidFile -Raw).Trim()
    if($text -notmatch '^\d+$'){return $null}
    $proc=Get-CimInstance Win32_Process -Filter "ProcessId=$text" -ErrorAction SilentlyContinue
    $argumentPattern='(?:^|\s)"'+[regex]::Escape($serverPath)+'"(?=\s|$)'
    if($proc.Name -eq 'node.exe' -and $proc.CommandLine -match $argumentPattern){return $proc}
    return $null
}
function Test-RemoteFirewall([string]$InterfaceAlias){
    try{
        $rule=Get-NetFirewallRule -Name 'SistemaArtesanalRemote51' -ErrorAction Stop
        $portFilter=$rule | Get-NetFirewallPortFilter
        $appFilter=$rule | Get-NetFirewallApplicationFilter
        $addressFilter=$rule | Get-NetFirewallAddressFilter
        $interfaceFilter=$rule | Get-NetFirewallInterfaceFilter
        return ($rule.Enabled -eq 'True' -and $rule.Action -eq 'Allow' -and $rule.Direction -eq 'Inbound' -and
            $portFilter.LocalPort -eq '8787' -and $appFilter.Program -eq 'C:\Program Files\nodejs\node.exe' -and
            $addressFilter.RemoteAddress -contains 'LocalSubnet' -and $interfaceFilter.InterfaceAlias -contains $InterfaceAlias)
    }catch{return $false}
}
if($Acao -eq 'Status'){
    $process=Get-RemoteProcess
    if($process){$state=Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json; [pscustomobject]@{Ligado=$true;Endereco=('http://'+$state.ip+':'+$state.port);ProcessId=$process.ProcessId} | ConvertTo-Json -Compress}
    else{'{"Ligado":false}'}
    exit
}
$launcherMutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalRemote51Launcher')
$locked=$false
try{
    try{$locked=$launcherMutex.WaitOne(10000)}catch [Threading.AbandonedMutexException]{$locked=$true}
    if(-not $locked){throw 'Outro comando do controle remoto esta em andamento. Aguarde alguns segundos.'}
    if($Acao -eq 'Parar'){
        $process=Get-RemoteProcess
        if($process){$owned=Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue;Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue;if($owned){$owned.WaitForExit(5000) | Out-Null}}
        Remove-Item -LiteralPath $pidFile -ErrorAction SilentlyContinue
        exit
    }
    $route=Get-Remote51Network
    $ip=$route.IPAddress
    if(-not $ip){throw 'Conecte o PC ao Wi-Fi ou a rede local.'}
    $process=Get-RemoteProcess
    if($process -and (Test-Path -LiteralPath $connectionFile)){
        $saved=Get-Content -LiteralPath $connectionFile -Raw | ConvertFrom-Json
        try{$health=Invoke-RestMethod ('http://127.0.0.1:'+$port+'/health') -TimeoutSec 2}catch{$health=$null}
        if($saved.ip -ne $ip -or -not $health.ok -or $health.pid -ne $process.ProcessId){
            $owned=Get-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
            Stop-Process -Id $process.ProcessId -ErrorAction SilentlyContinue
            if($owned -and -not $owned.WaitForExit(5000)){throw 'O controle anterior ainda esta encerrando. Tente novamente.'}
            $process=$null
        }
    }
    if(-not $process){
        $principal=[Security.Principal.WindowsPrincipal]::new([Security.Principal.WindowsIdentity]::GetCurrent())
        $firewallPath=Join-Path $remoteDir 'firewall.ps1'
        if($principal.IsInRole([Security.Principal.WindowsBuiltInRole]::Administrator)){& $firewallPath}
        elseif(-not (Test-RemoteFirewall $route.InterfaceAlias)){
            # The user's shortcut asks Windows to allow this single LAN port.
            $elevated=Start-Process powershell.exe -Verb RunAs -WindowStyle Hidden -Wait -PassThru -ArgumentList ('-NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "'+$firewallPath+'"')
            if($elevated.ExitCode -ne 0){throw 'A permissao de rede nao foi aplicada. Abra novamente o atalho e aceite a solicitacao do Windows.'}
        }
        $env:REMOTE51_IP=$ip
        $env:REMOTE51_PORT=[string]$port
        Remove-Item Env:REMOTE51_TEST -ErrorAction SilentlyContinue
        Remove-Item Env:REMOTE51_DATA_DIR -ErrorAction SilentlyContinue
        $started=Start-Process 'C:\Program Files\nodejs\node.exe' -WindowStyle Hidden -PassThru -ArgumentList ('"'+$serverPath+'"') -RedirectStandardOutput (Join-Path $remoteDir 'server.log') -RedirectStandardError (Join-Path $remoteDir 'server-error.log')
        $watch=[Diagnostics.Stopwatch]::StartNew(); $ready=$false
        do{
            try{$health=Invoke-RestMethod ('http://127.0.0.1:'+$port+'/health') -TimeoutSec 1;$ready=$health.ok -and $health.pid -eq $started.Id -and $health.ip -eq $ip}catch{}
            $started.Refresh();if($started.HasExited){throw 'O controle encerrou durante a inicializacao. Consulte controle-remoto/server-error.log.'}
            if(-not $ready){Start-Sleep -Milliseconds 200}
        }while(-not $ready -and $watch.ElapsedMilliseconds -lt 8000)
        if(-not $ready){throw 'O controle nao iniciou. Consulte controle-remoto/server-error.log.'}
    }
    # The user explicitly opened this interactive controller.
    Start-Process 'http://127.0.0.1:8787/setup' | Out-Null
}catch{
    Add-Type -AssemblyName System.Windows.Forms
    [Windows.Forms.MessageBox]::Show($_.Exception.Message,'Controle remoto 5.1',[Windows.Forms.MessageBoxButtons]::OK,[Windows.Forms.MessageBoxIcon]::Warning) | Out-Null
    exit 1
}finally{
    if($locked){$launcherMutex.ReleaseMutex()}
    $launcherMutex.Dispose()
}
