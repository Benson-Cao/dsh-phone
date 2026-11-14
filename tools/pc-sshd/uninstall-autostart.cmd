@echo off
REM Remove the per-user autostart entry for pc-sshd.
setlocal
reg delete "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v dsh-pc-sshd /f
if errorlevel 1 (
  echo Nothing to remove (or access denied).
  exit /b 1
)
echo Removed the dsh-pc-sshd Run entry.
exit /b 0
