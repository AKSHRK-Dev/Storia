@echo off
rem Stolia Relay: hands terrain work from Stolia servers to Stolia Workers.
cd /d "%~dp0"
java -Xmx256M -jar stolia-relay.jar relay.properties
pause
