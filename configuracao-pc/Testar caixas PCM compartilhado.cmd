@echo off
"%~dp0mpv-portatil\mpv.exe" --no-config "--include=%~dp0mpv-pcm-direto.conf" --force-window=yes --keep-open=yes "%~dp0teste-caixas-5.1.wav"
