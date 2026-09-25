@echo off
rem Storia Relay: hands terrain work from Storia servers to Storia Workers.
cd /d "%~dp0"
java -Xmx256M -jar storia-relay.jar relay.properties
pause
