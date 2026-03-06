@echo off
chcp 65001 >nul
setlocal EnableExtensions DisableDelayedExpansion

rem scripts\start-all.bat -> project root
set "ROOT=%~dp0.."
cd /d "%ROOT%"

echo ========================================
echo medical-qa-thesis one-click start
echo ========================================

echo [INFO] Project root: %CD%

if "%API_KEY%"=="" (
  set /p API_KEY=Please input DeepSeek API Key: 
)

if "%API_KEY%"=="" (
  echo [ERROR] API_KEY is empty, startup aborted.
  pause
  exit /b 1
)

set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"

echo [1/3] Starting Spring Boot backend...
start "medical-qa-backend" cmd /k "chcp 65001>nul && cd /d "%CD%" && set "API_KEY=%API_KEY%" && .\mvnw.cmd spring-boot:run"

echo [2/3] Waiting for backend on :8080 ...
set /a retries=0
:wait_port
set /a retries+=1
netstat -ano | findstr /r /c:":8080 .*LISTENING" >nul
if %errorlevel%==0 goto open_browser
if %retries% geq 45 goto timeout_open
>nul timeout /t 1 /nobreak
goto wait_port

:open_browser
echo [3/3] Opening pages...
start "" "http://localhost:8080/index.html"
start "" "http://localhost:8080/admin.html"
echo.
echo Started:
echo - Backend window title: medical-qa-backend
echo - Chat UI:   http://localhost:8080/index.html
echo - Admin UI:  http://localhost:8080/admin.html
echo.
echo To stop service: run stop.bat
endlocal
exit /b 0

:timeout_open
echo [WARN] Backend did not report LISTENING on :8080 within 45s.
echo Please check the "medical-qa-backend" window logs.
start "" "http://localhost:8080/index.html"
endlocal
exit /b 0
