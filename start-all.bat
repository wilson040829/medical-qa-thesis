@echo off
chcp 65001 >nul
setlocal
cd /d %~dp0

echo ========================================
echo consultant one-click start (backend + frontend)
echo ========================================

if "%API_KEY%"=="" (
  set /p API_KEY=Please input DeepSeek API Key: 
)

if "%API_KEY%"=="" (
  echo [ERROR] API_KEY is empty, startup aborted.
  pause
  exit /b 1
)

set "JAVA_TOOL_OPTIONS=-Dfile.encoding=UTF-8"

echo [1/2] Starting Spring Boot backend...
start "consultant-backend" cmd /k "chcp 65001>nul && cd /d %~dp0 && set API_KEY=%API_KEY% && .\mvnw.cmd spring-boot:run"

timeout /t 6 /nobreak >nul

echo [2/2] Opening frontend page...
start "" http://localhost:8080/index.html

echo.
echo Started:
echo - Backend window title: consultant-backend
echo - Frontend URL: http://localhost:8080/index.html
echo.
echo To stop service: close consultant-backend window or run stop-all.bat

endlocal
