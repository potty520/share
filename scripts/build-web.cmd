@echo off
rem ============================================================
rem Build frontend (share-web) and copy dist into share-app static
rem Usage: scripts\build-web.cmd
rem ============================================================
setlocal

set "ROOT=%~dp0.."
set "TEMP=%ROOT%\_toolchain\tmp"
set "TMP=%TEMP%"
if not exist "%TEMP%" mkdir "%TEMP%"

cd /d "%ROOT%\share-web"
echo [1/3] npm run build
call npm run build
if errorlevel 1 (
  echo Build FAILED
  exit /b 1
)

set "STATIC=%ROOT%\share-app\src\main\resources\static"
echo [2/3] clean %STATIC%
if exist "%STATIC%" rmdir /s /q "%STATIC%"
mkdir "%STATIC%"

echo [3/3] copy dist -^> static
xcopy /e /i /y "%ROOT%\share-web\dist\*" "%STATIC%\" >nul
if errorlevel 1 (
  echo Copy FAILED
  exit /b 1
)
echo Frontend build OK: %STATIC%
endlocal
