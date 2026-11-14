@echo off
REM pc-sshd silent launcher for the Startup folder. No admin required.
REM node resolution: first node.exe on PATH, else C:\Program Files\nodejs\node.exe.
REM Logging is done by pc-sshd itself (%USERPROFILE%\.dsh\pc-sshd\pc-sshd.log).
REM ASCII only on purpose: cmd.exe parses .cmd in the OEM codepage, non-ASCII here breaks it.
setlocal
set "SELF=%~dp0pc-sshd.mjs"
set "NODE="
for %%i in (node.exe) do if not "%%~$PATH:i"=="" set "NODE=%%~$PATH:i"
if not defined NODE if exist "C:\Program Files\nodejs\node.exe" set "NODE=C:\Program Files\nodejs\node.exe"
if not defined NODE (
  echo node.exe not found. Install Node.js 18+ first.
  pause
  exit /b 1
)
start "" /min "%NODE%" "%SELF%"
exit /b 0
