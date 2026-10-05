$ErrorActionPreference='Stop'
$node='C:\Program Files\nodejs\node.exe'
. (Join-Path $PSScriptRoot 'rede-local.ps1')
$route=Get-Remote51Network
if(-not $route){throw 'Rede local nao encontrada.'}
$ruleName='SistemaArtesanalRemote51'
$existing=Get-NetFirewallRule -Name $ruleName -ErrorAction SilentlyContinue
if($existing){
    # Update only our rule: changing Wi-Fi adapters must not leave an old binding.
    Set-NetFirewallRule -Name $ruleName -Enabled True -Direction Inbound -Action Allow -Profile Any | Out-Null
    $existing | Get-NetFirewallPortFilter | Set-NetFirewallPortFilter -Protocol TCP -LocalPort 8787 | Out-Null
    $existing | Get-NetFirewallApplicationFilter | Set-NetFirewallApplicationFilter -Program $node | Out-Null
    $existing | Get-NetFirewallAddressFilter | Set-NetFirewallAddressFilter -RemoteAddress LocalSubnet | Out-Null
    $existing | Get-NetFirewallInterfaceFilter | Set-NetFirewallInterfaceFilter -InterfaceAlias $route.InterfaceAlias | Out-Null
}else{
    New-NetFirewallRule -Name $ruleName -DisplayName 'Controle remoto 5.1 - rede local' -Direction Inbound -Action Allow -Protocol TCP -LocalPort 8787 -Program $node -RemoteAddress LocalSubnet -InterfaceAlias $route.InterfaceAlias -Profile Any | Out-Null
}
'Regra da porta 8787 criada para a rede local.' | Set-Content -LiteralPath (Join-Path $PSScriptRoot 'firewall-status.txt') -Encoding UTF8
