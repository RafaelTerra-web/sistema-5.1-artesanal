@echo off
"%~dp0mpv-portatil\mpv.exe" --no-config "--include=%~dp0mpv-teste-pcm-exclusivo-90ms.conf" --force-window=yes --keep-open=yes "--log-file=%~dp0teste-caixas-pcm-exclusivo.log" "%~dp0teste-caixas-5.1.wav"
