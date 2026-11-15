@echo off
REM Silent launcher for dsh-web-daemon (per-user autostart). No admin required.
REM Runs the daemon in FOREGROUND mode inside a minimized window: "start" already
REM detaches it from the logon shell, so --detach is NOT used here (detached
REM children get reaped inside WorkBuddy sessions).
REM Logging: %USERPROFILE%\.dsh\pc-sshd\dsh-web-daemon.log
REM ASCII only: cmd.exe parses .cmd in the OEM codepage.
setlocal
set "SELF=%~dp0dsh-web-daemon.mjs"
set "NODE="
for %%i in (node.exe) do if not "%%~$PATH:i"=="" set "NODE=%%~$PATH:i"
if not defined NODE if exist "C:\Program Files\nodejs\node.exe" set "NODE=C:\Program Files\nodejs\node.exe"
if not defined NODE (
  echo node.exe not found. Install Node.js 18+ first.
  exit /b 1
)
start "" /min "%NODE%" "%SELF%"
exit /b 0
