@echo off
"%~dp0mpv-portatil\mpv.exe" --no-config "--include=%~dp0mpv-sistema-player.conf" --idle=yes --force-window=yes %*
