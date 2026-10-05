param([switch]$Navegador, [switch]$Teste51, [string]$VideoUrl)
$ErrorActionPreference = 'Stop'
$controller = Join-Path $PSScriptRoot 'Controle do sistema 5.1.ps1'
$state = (& $controller -Acao Ligar | ConvertFrom-Json)
if (-not $state.Ligado) { throw 'A rota Dolby Digital nao iniciou. Confira o HDMI e o painel do sistema.' }
$config = Get-Content -LiteralPath (Join-Path $PSScriptRoot 'mpv-sistema-dolby.conf') -Raw
if ($config -notmatch 'bitrate=640(?:\D|$)') {
    & (Join-Path $PSScriptRoot 'Ajustar qualidade do audio.ps1') -Perfil Fidelidade | Out-Null
}
$edgePath = Join-Path ${env:ProgramFiles(x86)} 'Microsoft\Edge\Application\msedge.exe'
if (-not (Test-Path -LiteralPath $edgePath)) { throw 'Microsoft Edge nao encontrado.' }
$url = 'https://www.netflix.com/browse'
if ($VideoUrl) {
    $requested = [uri]$VideoUrl
    if ($requested.Scheme -ne 'https' -or $requested.Host -ne 'www.netflix.com' -or $requested.AbsolutePath -notmatch '^/watch/(\d+)/?$') {
        throw 'Use um link https://www.netflix.com/watch/ seguido do numero do filme ou episodio.'
    }
    $url = 'https://www.netflix.com/watch/' + $Matches[1]
}
$netflixSettings = [ordered]@{
    enableDDPlus51='true'; enableDDPlusAtmos='false'; audioCapabilityDetectorType='0';
    spatialRenderingForDolbyAudio='false'; enableMediaCapabilities='true';
    audioProfiles='heaac-2-dash|heaac-2hq-dash|xheaac-dash|ddplus-5.1-dash|ddplus-5.1hq-dash'
}
$automaticStatePath = Join-Path $PSScriptRoot 'netflix-dolby51-automatico\estado.json'
$enableUrlAdjustment = $true
if (Test-Path -LiteralPath $automaticStatePath) {
    $automaticState = Get-Content -LiteralPath $automaticStatePath -Raw | ConvertFrom-Json
    if ($automaticState.PSObject.Properties.Name -contains 'AjusteUrlHabilitado') {
        $enableUrlAdjustment = [bool]$automaticState.AjusteUrlHabilitado
    }
}
if ($enableUrlAdjustment) {
    $query = @($netflixSettings.GetEnumerator() | ForEach-Object { [uri]::EscapeDataString($_.Key) + '=' + [uri]::EscapeDataString($_.Value) }) -join '&'
    $url += '?' + $query
}
if ($Navegador -or $Teste51) {
    if ($Teste51) {
        $url = ([uri](Join-Path $PSScriptRoot 'Netflix 5.1 - teste.html')).AbsoluteUri
    }
    Start-Process -FilePath $edgePath -ArgumentList $url
} else {
    $package = Get-AppxPackage '4DF9E0F8.Netflix' | Select-Object -First 1
    if (-not $package) { throw 'Netflix da Microsoft Store nao encontrada.' }
    [xml]$manifest = Get-AppxPackageManifest -Package $package.PackageFullName
    $application = @($manifest.Package.Applications.Application) | Select-Object -First 1
    $parameters = $application.GetAttribute('Parameters', 'http://schemas.microsoft.com/appx/manifest/uap/windows10/10')
    if ($parameters -notmatch '--app-id=([a-p]{32})') { throw 'Identificador do app Netflix nao encontrado.' }
    $appId = $Matches[1]
    # Pass the already validated URL overrides to the installed Netflix app.
    Start-Process -FilePath $edgePath -ArgumentList @('--profile-directory=Default', ('--app-id=' + $appId),
        ('--app-launch-url-for-shortcuts-menu-item="' + $url + '"'))
}
