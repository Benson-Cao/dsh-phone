@echo off
REM Per-user autostart for dsh-web-daemon via HKCU\...\Run. No admin required.
REM Undo: run uninstall-web-autostart.cmd (or delete the "dsh-web-daemon" value).
REM ASCII only on purpose: cmd.exe parses .cmd in the OEM codepage.
setlocal
set "LAUNCHER=%~dp0start-web-silent.cmd"
if not exist "%LAUNCHER%" (
  echo Missing %LAUNCHER%
  exit /b 1
)
reg add "HKCU\Software\Microsoft\Windows\CurrentVersion\Run" /v dsh-web-daemon /t REG_SZ /d "\"%LAUNCHER%\"" /f
if errorlevel 1 (
  echo Failed to write the Run key.
  exit /b 1
)
echo Installed: HKCU\Software\Microsoft\Windows\CurrentVersion\Run -> "%LAUNCHER%"
echo It starts at next logon. To start now, run start-web-silent.cmd.
exit /b 0
