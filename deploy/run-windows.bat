@echo off
REM CafeGuard Scanner Gateway — Windows auto-start helper
REM 1) Copy this folder to C:\CafeGuard\scanner-gateway
REM 2) Edit config\gateway.properties
REM 3) Create a shortcut to this .bat in:
REM    shell:startup
REM    (Win+R → shell:startup)

cd /d "%~dp0\.."
if not exist "config\gateway.properties" (
  echo Missing config\gateway.properties
  echo Copy config\gateway.properties.example and edit credentials.
  pause
  exit /b 1
)

java -jar scanner-gateway.jar config\gateway.properties
pause
