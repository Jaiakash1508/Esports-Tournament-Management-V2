@echo off
setlocal
cd /d "%~dp0"

where mvn >nul 2>nul
if errorlevel 1 (
  echo Maven 3.9+ is required. Install Maven, then run this script again.
  exit /b 1
)

call mvn compile exec:java
exit /b %errorlevel%
