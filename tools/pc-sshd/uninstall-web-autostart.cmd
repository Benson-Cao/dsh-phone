@echo off
REM Remove the per-user autostart entry for dsh-web-daemon.
setlocal
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v dsh-web-daemon /f
if errorlevel 1 (
  echo Nothing to remove (or access denied).
  exit /b 1
)
echo Removed the dsh-web-daemon Run entry.
exit /b 0
