@echo off
title DURGA Central Auth Server - PM2 Runner
cd /d "%~dp0backend"
set "PM2_HOME=%CD%\.pm2"
echo Starting DURGA Central Authentication Server with PM2...
call .\node_modules\.bin\pm2.cmd start ecosystem.config.js
call .\node_modules\.bin\pm2.cmd save
echo.
call .\node_modules\.bin\pm2.cmd status
echo.
echo Server is actively running in the background at http://localhost:3000
pause

