@echo off
title DURGA Central Auth Server - Stop PM2
cd /d "%~dp0backend"
set "PM2_HOME=%CD%\.pm2"
call .\node_modules\.bin\pm2.cmd stop ecosystem.config.js
call .\node_modules\.bin\pm2.cmd status
pause

