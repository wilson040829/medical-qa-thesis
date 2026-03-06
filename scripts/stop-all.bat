@echo off
chcp 65001 >nul
setlocal
set "ROOT=%~dp0.."
cd /d "%ROOT%"

echo Stopping medical-qa Java service in: %CD%
for /f "tokens=2 delims==" %%i in ('wmic process where "name='java.exe' and commandline like '%%IdeaProjects\\consultant%%'" get ProcessId /value ^| find "="') do (
  taskkill /PID %%i /F >nul 2>nul
)
echo Stop command executed.
endlocal
pause
