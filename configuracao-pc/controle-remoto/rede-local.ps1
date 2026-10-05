function Get-Remote51Network {
    $candidates = foreach ($defaultRoute in (Get-NetRoute -DestinationPrefix '0.0.0.0/0' -AddressFamily IPv4)) {
        $ipInterface = Get-NetIPInterface -InterfaceIndex $defaultRoute.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue
        if ($ipInterface.ConnectionState -ne 'Connected' -or $defaultRoute.InterfaceAlias -match 'Radmin') { continue }
        $address = Get-NetIPAddress -InterfaceIndex $defaultRoute.InterfaceIndex -AddressFamily IPv4 -ErrorAction SilentlyContinue |
            Where-Object { $_.AddressState -eq 'Preferred' -and $_.IPAddress -notmatch '^(127\.|169\.254\.|26\.)' } |
            Select-Object -First 1
        if (-not $address) { continue }
        [pscustomobject]@{
            IPAddress = $address.IPAddress
            InterfaceAlias = $defaultRoute.InterfaceAlias
            InterfaceIndex = $defaultRoute.InterfaceIndex
            EffectiveMetric = [int]$defaultRoute.RouteMetric + [int]$ipInterface.InterfaceMetric
        }
    }
    $network = $candidates | Sort-Object EffectiveMetric, InterfaceAlias | Select-Object -First 1
    if (-not $network) { throw 'Conecte o PC por Ethernet ou Wi-Fi a rede local.' }
    return $network
}
