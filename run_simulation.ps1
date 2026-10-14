# ==============================================================================
# DURGA - Dynamic Unified Response and Geo-Alert System
# Laptop Demonstration and Architecture Showcase Launcher
# ==============================================================================

Write-Host "=================================================================" -ForegroundColor Cyan
Write-Host "  DURGA - Dynamic Unified Response and Geo-Alert System" -ForegroundColor Red
Write-Host "  Laptop Interactive Demonstration and System Showcase" -ForegroundColor White
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
Write-Host "    1. Click 'Run Full Emergency Demo' for automated 4-node showcase" -ForegroundColor White
Write-Host "    2. Click 'Shake Device' or 'SOS' on Victim Phone (Node 1)" -ForegroundColor White
Write-Host "    3. Watch 5-sec countdown and Cancel verification feature" -ForegroundColor White
Write-Host "    4. Observe 16-bit PCM Audio Waveform and AI Acoustic Engine" -ForegroundColor White
Write-Host "    5. See Android OS Heads-Up Notification and Popup on Nearby Citizen (Node 2)" -ForegroundColor White
Write-Host "    6. Test play ambient audio evidence on Guardian Phone (Node 3)" -ForegroundColor White
Write-Host "    7. Acknowledge, Dispatch, and Resolve from Police CAD (Node 4)" -ForegroundColor White
Write-Host "    8. Watch moving police cruiser along vector on Leaflet Map" -ForegroundColor White
Write-Host "    9. Use Laptop View Switcher to inspect any single device or all 4 nodes" -ForegroundColor White
Write-Host "=================================================================" -ForegroundColor Cyan
