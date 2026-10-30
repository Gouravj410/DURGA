@echo off
title DURGA Central Auth Server - PM2 Status
cd /d "%~dp0backend"
set "PM2_HOME=%CD%\.pm2"
call .\node_modules\.bin\pm2.cmd status
pause

