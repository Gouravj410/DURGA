# DURGA Central Auth Server PM2 Launcher
$backendDir = Join-Path $PSScriptRoot "backend"
$env:PM2_HOME = Join-Path $backendDir ".pm2"
Set-Location $backendDir

Write-Host "Starting DURGA Central Auth Server with PM2..." -ForegroundColor Cyan
& ".\node_modules\.bin\pm2.cmd" start ecosystem.config.js
& ".\node_modules\.bin\pm2.cmd" save
& ".\node_modules\.bin\pm2.cmd" status

Write-Host "`nServer is actively running in the background at http://localhost:3000" -ForegroundColor Green
Write-Host "To view real-time logs: cd backend; npm run pm2:logs" -ForegroundColor Yellow
Write-Host "To stop the server:    cd backend; npm run pm2:stop" -ForegroundColor Yellow

