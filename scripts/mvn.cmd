@echo off
rem ============================================================
rem Share Exchange - Maven build entry (auto uses JDK 17)
rem Usage:
rem   scripts\mvn.cmd clean install -DskipTests
rem   scripts\mvn.cmd -pl share-app -am spring-boot:run
rem ============================================================
setlocal

set "JDK_HOME=E:\C++\_toolchain\jdk\jdk-17.0.2"
if not exist "%JDK_HOME%\bin\java.exe" (
  if defined SHARE_JDK_HOME set "JDK_HOME=%SHARE_JDK_HOME%"
)

set "JAVA_HOME=%JDK_HOME%"
set "PATH=%JAVA_HOME%\bin;%PATH%"

set "MVN_BIN=D:\apache-maven-3.9.9-bin\apache-maven-3.9.9\bin\mvn.cmd"
if not exist "%MVN_BIN%" (
  set "MVN_BIN=mvn"
)

call "%MVN_BIN%" -s "%~dp0maven-settings.xml" %*
endlocal
