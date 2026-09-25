@echo off
rem Stolia Worker: computes terrain for other Stolia servers. Opens no player port.
cd /d "%~dp0"
findstr /c:"eula=true" eula.txt >nul 2>&1
if errorlevel 1 (
  echo Read the Minecraft EULA ^(https://aka.ms/MinecraftEULA^) and, if you agree, put eula=true in eula.txt.
  pause
  exit /b 1
)
if "%WORKER_MEMORY%"=="" set WORKER_MEMORY=4G
java -Xms1G -Xmx%WORKER_MEMORY% -Dstolia.worker=true -jar stolia.jar nogui
pause
