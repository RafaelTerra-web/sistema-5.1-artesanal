param([string]$DiretorioConfiguracao = (Join-Path (Split-Path $PSScriptRoot) 'configuracao-pc'))
$ErrorActionPreference='Stop'
$examples=Join-Path (Split-Path $PSScriptRoot) 'examples'
$destination=[IO.Path]::GetFullPath($DiretorioConfiguracao)
if(-not [IO.Directory]::Exists($destination)){throw 'Escolha uma pasta de configuracao existente em uma copia nova do projeto.'}
$mapping=@{
    'equalizador-lfe.example.json'='equalizador-lfe.json'
    'mpv-sistema-dolby.example.conf'='mpv-sistema-dolby.conf'
    'mpv-sistema-dolby-baixa-latencia.example.conf'='mpv-sistema-dolby-baixa-latencia.conf'
}
foreach($source in $mapping.Keys){
    if(-not [IO.File]::Exists((Join-Path $examples $source))){throw ('Exemplo ausente: '+$source)}
    if([IO.File]::Exists((Join-Path $destination $mapping[$source]))){throw ('Arquivo ja existe; nenhuma copia foi feita: '+$mapping[$source])}
}
foreach($source in $mapping.Keys){[IO.File]::Copy((Join-Path $examples $source),(Join-Path $destination $mapping[$source]),$false)}
Write-Output 'Tres exemplos copiados. Adapte endpoints, executaveis, relogio e preset antes de iniciar o audio. Nenhum servico foi iniciado.'
