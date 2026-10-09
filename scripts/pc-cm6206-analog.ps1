# Hold only DRIVERON for an owned session. Audio processes are supervised elsewhere.
[CmdletBinding()]
param(
    [Parameter(Mandatory=$true)][string]$StopPath,
    [Parameter(Mandatory=$true)][string]$StatusPath,
    [Parameter(Mandatory=$true)][string]$JournalPath
)
$ErrorActionPreference='Stop'
$root=[IO.Path]::GetFullPath((Join-Path $PSScriptRoot '..'))
$prefix=(Join-Path $root 'android-a34/artifacts').TrimEnd('\','/')+[IO.Path]::DirectorySeparatorChar
foreach($path in @($StopPath,$StatusPath,$JournalPath)){
    if(-not([IO.Path]::GetFullPath($path).StartsWith($prefix,[StringComparison]::OrdinalIgnoreCase))){throw 'Controle deve ficar em artifacts.'}
}
Add-Type -Path (Join-Path $PSScriptRoot 'optical-tests/Cm6206OpticalGuard.cs')
$guard=$null
$locked=$false
$mutex=[Threading.Mutex]::new($false,'Local\SistemaArtesanalCM6206AnalogControl')
$state=[ordered]@{Status='starting';Ready=$false;RestorationVerified=$false;Error='';Pid=$PID;UpdatedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')}
function Save-AnalogState {
    $state.UpdatedAtUtc=[DateTimeOffset]::UtcNow.ToString('o')
    $tmp=$StatusPath+'.tmp'
    [IO.File]::WriteAllText($tmp,($state|ConvertTo-Json -Depth 5),[Text.UTF8Encoding]::new($false))
    Move-Item -LiteralPath $tmp -Destination $StatusPath -Force
}
try {
    $locked=$mutex.WaitOne(0);if(-not $locked){throw 'Outro controle analógico está em execução.'}
    $guard=[Sistema51.Hardware.Cm6206OpticalGuard]::new($JournalPath)
    $guard.EnsureAnalogDriver()
    $state.Status='held';$state.Ready=$true;Save-AnalogState
    while(-not(Test-Path -LiteralPath $StopPath)){Start-Sleep -Milliseconds 200}
} catch {$state.Status='failed';$state.Error=$_.Exception.GetBaseException().Message}
finally {
    if($guard){
        $guard.Dispose();$state.RestorationVerified=$guard.RestorationVerified
        if(-not $guard.RestorationVerified){$state.Error=($state.Error+'; '+($guard.RestorationErrors -join '; ')).Trim('; ');$state.Status='failed'}
    }
    if($state.Status -ne 'failed'){$state.Status='stopped'}
    $state.Ready=$false;Save-AnalogState
    if($locked){$mutex.ReleaseMutex()};$mutex.Dispose()
}
if($state.Error){exit 1}
