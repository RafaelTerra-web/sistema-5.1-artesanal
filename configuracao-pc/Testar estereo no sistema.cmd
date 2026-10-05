@echo off
"%~dp0mpv-portatil\mpv.exe" --no-config "--include=%~dp0mpv-sistema-player.conf" --force-window=yes --keep-open=yes "--log-file=%~dp0teste-estereo-sistema-upmix.log" "%~dp0teste-estereo-upmix.wav"
