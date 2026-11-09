@echo off
rem ============================================================
rem Build & start the share-exchange platform (H2 embedded mode)
rem Usage: scripts\start.cmd            (build if needed, then run)
rem        scripts\start.cmd --rebuild  (force rebuild)
rem ============================================================
setlocal

set "ROOT=%~dp0.."
cd /d "%ROOT%"

set "JAR=%ROOT%\share-app\target\share-exchange.jar"

if /I "%~1"=="--rebuild" (
  echo [build] mvn package
  call "%ROOT%\scripts\mvn.cmd" -B -o -DskipTests package
  if errorlevel 1 exit /b 1
) else (
  if not exist "%JAR%" (
    echo [build] jar not found, mvn package
    call "%ROOT%\scripts\mvn.cmd" -B -o -DskipTests package
    if errorlevel 1 exit /b 1
  )
)

echo [run] %JAR%
echo   Portal:  http://127.0.0.1:8080/
echo   API doc: http://127.0.0.1:8080/swagger-ui.html
echo   H2 console: http://127.0.0.1:8080/h2-console
"%ROOT%\_toolchain\jdk\jdk-17.0.2\bin\java.exe" -jar "%JAR%" --spring.profiles.active=default
endlocal
