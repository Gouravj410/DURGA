# ==============================================================================
# DURGA — Dynamic Unified Response & Geo-Alert System
# Laptop Demonstration & Architecture Showcase Launcher
# ==============================================================================

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  DURGA — Dynamic Unified Response & Geo-Alert System" -ForegroundColor Red
Write-Host "  Laptop Interactive Demonstration & System Showcase" -ForegroundColor White
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host ""

$simulationDir = Join-Path $PSScriptRoot "simulation"
$indexPath = Join-Path $simulationDir "index.html"

if (-not (Test-Path $indexPath)) {
    Write-Host "[ERROR] simulation/index.html not found!" -ForegroundColor Red
    exit 1
}

$port = 8085
$url = "http://localhost:$port"

# Check if Python is available to host a clean local server
$pythonCmd = Get-Command python -ErrorAction SilentlyContinue

if ($pythonCmd) {
    Write-Host "[INFO] Starting local lightweight web server on port $port..." -ForegroundColor Green
    
    # Check if port is already in use
    $portActive = Get-NetTCPConnection -LocalPort $port -ErrorAction SilentlyContinue
    if (-not $portActive) {
        Start-Process python -ArgumentList "-m http.server $port --directory `"$simulationDir`"" -WindowStyle Hidden
        Start-Sleep -Seconds 1
    }
    
    Write-Host "[SUCCESS] Simulation running at: $url" -ForegroundColor Cyan
    Write-Host "[INFO] Launching browser..." -ForegroundColor Yellow
    Start-Process $url
} else {
    Write-Host "[INFO] Python not found. Opening direct HTML showcase in browser..." -ForegroundColor Yellow
    Start-Process $indexPath
}

Write-Host ""
Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  DURGA Laptop Showcase is now LIVE in your browser!" -ForegroundColor Green
Write-Host "  Features to demonstrate:" -ForegroundColor White
Write-Host "    1. Click '⚡ Run Full Emergency Demo' for automated showcase" -ForegroundColor White
Write-Host "    2. Click '👋 Shake Device' or '🚨 SOS' on Victim Phone" -ForegroundColor White
Write-Host "    3. Watch 5-sec countdown & Cancel verification feature" -ForegroundColor White
Write-Host "    4. Observe 16-bit PCM Audio Waveform & AI Acoustic Engine" -ForegroundColor White
Write-Host "    5. Test play ambient audio evidence on Contact Phone" -ForegroundColor White
Write-Host "    6. Acknowledge, Dispatch, and Resolve from Police CAD" -ForegroundColor White
Write-Host "    7. Watch moving police cruiser along vector on Leaflet Map" -ForegroundColor White
Write-Host "=================================================================" -ForegroundColor Cyan
