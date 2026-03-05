@echo off
chcp 65001 >nul
echo Stopping medical-qa Java service...
for /f "tokens=2" %%i in ('wmic process where "name='java.exe' and commandline like '%%IdeaProjects\\consultant%%'" get ProcessId /value ^| find "="') do (
  taskkill /PID %%i /F >nul 2>nul
)
echo Stop command executed.
pause
