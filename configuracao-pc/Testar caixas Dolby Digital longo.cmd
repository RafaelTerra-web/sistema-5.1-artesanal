@echo off
powershell.exe -NoProfile -ExecutionPolicy Bypass -WindowStyle Hidden -File "%~dp0testar-canais-nativos.ps1" -AudioFile teste-longo-5.1.wav -LogName teste-longo-sistema-global.log
