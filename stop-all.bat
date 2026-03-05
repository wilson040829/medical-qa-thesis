@echo off
chcp 65001 >nul
echo Stopping consultant Java service...
for /f "tokens=2" %%i in ('wmic process where "name='java.exe' and commandline like '%%consultant%%'" get ProcessId /value ^| find "="') do (
  taskkill /PID %%i /F >nul 2>nul
)
echo Stop command executed.
pause
