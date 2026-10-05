$ErrorActionPreference = 'Stop'
$statusPath = Join-Path $PSScriptRoot 'upmix-instalacao-status.json'
$fxPrefix = '{d04e05a6-594b-4fb6-a80d-01af5eed7d1d}'
$modesPrefix = '{d3993a3f-99c2-4402-b5ec-a92a0367664b}'
$preApo = '{EACD2258-FCAC-4FF4-B36D-419E924A6D79}'
$postApo = '{EC1CC9CE-FAED-4822-828A-82A81A6F018F}'
$mode = '{C18E2F7E-933D-4965-B7D1-1EEF228D2AF3}'
$deviceRoot = 'HKLM:\SOFTWARE\Microsoft\Windows\CurrentVersion\MMDevices\Audio\Render'
try {
    Add-Type -Path (Join-Path $PSScriptRoot 'RegistroAudioPrivileges.cs')
    [RegistroAudioPrivileges]::Enable('SeTakeOwnershipPrivilege')
    [RegistroAudioPrivileges]::Enable('SeRestorePrivilege')
    $configDir = 'C:\Program Files\EqualizerAPO\config'
    $configPath = Join-Path $configDir 'config.txt'
    $configBackup = Join-Path $PSScriptRoot 'apo-config-antes-upmix.txt'
    if (-not (Test-Path -LiteralPath $configBackup)) { Copy-Item -LiteralPath $configPath -Destination $configBackup }
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'delay-5.1-70ms.txt') -Destination (Join-Path $configDir 'delay-5.1-70ms.txt') -Force
    Copy-Item -LiteralPath (Join-Path $PSScriptRoot 'upmix-sistema-5.1.txt') -Destination (Join-Path $configDir 'upmix-sistema-5.1.txt') -Force
    "Include: upmix-sistema-5.1.txt`r`nInclude: delay-5.1-70ms.txt" | Set-Content -LiteralPath $configPath -Encoding ASCII
    foreach ($deviceGuid in @('{1480f3d6-872e-45ff-a839-c8b330d0127e}', '{e89cb1c3-e885-4df2-800f-ac950f115f89}')) {
        $fxPath = Join-Path (Join-Path $deviceRoot $deviceGuid) 'FxProperties'
        $childPath = 'HKLM:\SOFTWARE\EqualizerAPO\Child APOs\' + $deviceGuid
        $fxKey = Get-Item -LiteralPath $fxPath
        $backupFile = Join-Path $PSScriptRoot ('fx-antes-upmix-' + $deviceGuid.Trim('{}') + '.clixml')
        if (-not (Test-Path -LiteralPath $backupFile)) {
            $values = @()
            foreach ($name in $fxKey.GetValueNames()) {
                $values += [pscustomobject]@{Name=$name; Kind=$fxKey.GetValueKind($name).ToString(); Value=$fxKey.GetValue($name)}
            }
            $values | Export-Clixml -LiteralPath $backupFile
        }
        if (-not (Test-Path -LiteralPath $childPath)) {
            New-Item -Path $childPath -Force | Out-Null
            foreach ($index in @(1, 2, 5, 6, 7)) {
                $name = $fxPrefix + ',' + $index
                $oldValue = $fxKey.GetValue($name)
                if ($null -eq $oldValue) { $oldValue = '!VALUE' }
                New-ItemProperty -LiteralPath $childPath -Name $name -Value $oldValue -PropertyType String -Force | Out-Null
            }
            $preChild = $fxKey.GetValue(($fxPrefix + ',5'))
            if ($null -eq $preChild) { $preChild = '' }
            $postChild = $fxKey.GetValue(($fxPrefix + ',7'))
            if ($null -eq $postChild) { $postChild = '' }
            New-ItemProperty -LiteralPath $childPath -Name PreMixChild -Value $preChild -PropertyType String -Force | Out-Null
            New-ItemProperty -LiteralPath $childPath -Name PostMixChild -Value $postChild -PropertyType String -Force | Out-Null
            New-ItemProperty -LiteralPath $childPath -Name AllowSilentBufferModification -Value false -PropertyType String -Force | Out-Null
            New-ItemProperty -LiteralPath $childPath -Name Version -Value '2' -PropertyType String -Force | Out-Null
        }
        $aclBackup = Join-Path $PSScriptRoot ('fx-acl-antes-upmix-' + $deviceGuid.Trim('{}') + '.txt')
        $originalAcl = [System.Security.AccessControl.RegistrySecurity]::new()
        $originalAcl.SetSecurityDescriptorSddlForm((Get-Content -LiteralPath $aclBackup -Raw).Trim())
        $administrators = [System.Security.Principal.SecurityIdentifier]::new('S-1-5-32-544')
        $nativeSubkey = $fxPath.Substring(6)
        [RegistroAudioPrivileges]::SetAudioKeySecurity($nativeSubkey, 'O:BA', 1, 0x80000)
        $writableAcl = [System.Security.AccessControl.RegistrySecurity]::new()
        $writableAcl.SetSecurityDescriptorSddlForm($originalAcl.Sddl)
        $writableAcl.SetOwner($administrators)
        $writableAcl.AddAccessRule([System.Security.AccessControl.RegistryAccessRule]::new($administrators, [System.Security.AccessControl.RegistryRights]::FullControl, [System.Security.AccessControl.AccessControlType]::Allow))
        try {
        [RegistroAudioPrivileges]::SetAudioKeySecurity($nativeSubkey, $writableAcl.Sddl, 4, 0x40000)
        New-ItemProperty -LiteralPath $fxPath -Name ($fxPrefix + ',5') -Value $preApo -PropertyType String -Force | Out-Null
        New-ItemProperty -LiteralPath $fxPath -Name ($modesPrefix + ',5') -Value ([string[]]@($mode)) -PropertyType MultiString -Force | Out-Null
        New-ItemProperty -LiteralPath $fxPath -Name '{1da5d803-d492-4edd-8c23-e0c0ffee7f0e},5' -Value 0 -PropertyType DWord -Force | Out-Null
        if ($deviceGuid -eq '{e89cb1c3-e885-4df2-800f-ac950f115f89}') {
            New-ItemProperty -LiteralPath $fxPath -Name ($fxPrefix + ',7') -Value $postApo -PropertyType String -Force | Out-Null
            New-ItemProperty -LiteralPath $fxPath -Name ($modesPrefix + ',7') -Value ([string[]]@($mode)) -PropertyType MultiString -Force | Out-Null
        }
        } finally { [RegistroAudioPrivileges]::SetAudioKeySecurity($nativeSubkey, $originalAcl.Sddl, 7, 0xC0000) }
    }
    Set-ItemProperty -LiteralPath 'HKLM:\SOFTWARE\EqualizerAPO' -Name EnableTrace -Value 'true'
    Restart-Service -Name AudioSrv -Force
    [ordered]@{Success=$true; AudioService=(Get-Service AudioSrv).Status.ToString(); Config=$configPath} |
        ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
} catch {
    [ordered]@{Success=$false; Error=$_.Exception.Message; Line=$_.InvocationInfo.ScriptLineNumber} | ConvertTo-Json | Set-Content -LiteralPath $statusPath -Encoding UTF8
    exit 1
}
