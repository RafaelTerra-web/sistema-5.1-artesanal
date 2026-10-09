param([string]$OutputDirectory=(Join-Path $PSScriptRoot '..\..\artifacts\hardware-2026-10-08\hid'))
$ErrorActionPreference='Stop'
New-Item -ItemType Directory -Path $OutputDirectory -Force|Out-Null
Add-Type -Path (Join-Path $PSScriptRoot 'Cm6206HidProbe.cs')
$report=[ordered]@{scope='CM6206 HID read-register opcode 0x30 only; no register write, reset or INIT';time=[DateTimeOffset]::Now.ToString('o');devices=[Sistema51.Hardware.Cm6206HidProbe]::ReadOnlyRegisters()}
$json=$report|ConvertTo-Json -Depth 7
[IO.File]::WriteAllText((Join-Path (Resolve-Path -LiteralPath $OutputDirectory).Path 'registers.json'),$json)
Write-Output $json
