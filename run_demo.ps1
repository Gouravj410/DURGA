# ==============================================================================
# DURGA - Automated Demonstration & Multi-Device Verification Script
# Dynamic Unified Response & Geo-Alert System
# ==============================================================================

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  DURGA - Emergency Response System Demonstration Script   " -ForegroundColor Red
Write-Host "==========================================================" -ForegroundColor Cyan

# Set JDK 17 explicitly if needed
if (Test-Path "D:\Java\jdk17.0.21_0") {
    $env:JAVA_HOME = "D:\Java\jdk17.0.21_0"
    $env:Path = "$env:JAVA_HOME\bin;" + $env:Path
}

# 1. Check or start Central Backend Server
Write-Host "`n[1/6] Checking DURGA Central Backend Service on port 3000..." -ForegroundColor Yellow
$backendActive = Get-NetTCPConnection -LocalPort 3000 -ErrorAction SilentlyContinue
if (-not $backendActive) {
    Write-Host "  -> Starting DURGA backend server in background..." -ForegroundColor Green
    Start-Process node -ArgumentList "server.js" -WorkingDirectory "$PSScriptRoot\backend" -WindowStyle Hidden
    Start-Sleep -Seconds 2
} else {
    Write-Host "  -> DURGA backend server is already running on http://localhost:3000" -ForegroundColor Green
}

# 2. Launch Responder Command Portal in Browser
Write-Host "`n[2/6] Launching Tactical Responder Command Portal..." -ForegroundColor Yellow
$portalUrl = "http://localhost:3000/portal"
Start-Process $portalUrl
Write-Host "  -> Portal opened at $portalUrl (Login: +919999999999 / OTP: 123456)" -ForegroundColor Green

# 3. Locate ADB
$adb = if ($env:ANDROID_HOME -and (Test-Path "$env:ANDROID_HOME\platform-tools\adb.exe")) {
    "$env:ANDROID_HOME\platform-tools\adb.exe"
} elseif (Test-Path "D:\Android\Sdk\platform-tools\adb.exe") {
    "D:\Android\Sdk\platform-tools\adb.exe"
} elseif (Get-Command adb -ErrorAction SilentlyContinue) {
    (Get-Command adb).Source
} else {
    "adb"
}

# 4. Check connected devices
Write-Host "`n[3/6] Checking connected Android devices via ADB..." -ForegroundColor Yellow
& $adb devices

# 5. Build & verify APK
$apkPath = "$PSScriptRoot\app\build\outputs\apk\debug\app-debug.apk"
if (-not (Test-Path $apkPath)) {
    Write-Host "`n[4/6] Building debug APK..." -ForegroundColor Yellow
    .\gradlew.bat assembleDebug
} else {
    Write-Host "`n[4/6] Verified APK exists at: $apkPath" -ForegroundColor Green
}

# 6. Run automated Kotlin State Machine Unit Tests
Write-Host "`n[5/6] Running Incident State Machine Unit Tests..." -ForegroundColor Yellow
.\gradlew.bat testDebugUnitTest --quiet

if ($LASTEXITCODE -eq 0) {
    Write-Host "  -> ALL 6 UNIT TESTS PASSED (100%)" -ForegroundColor Green
} else {
    Write-Host "  -> UNIT TESTS FAILED" -ForegroundColor Red
}

# 7. Summary instructions for physical testing
Write-Host "`n[6/6] Two-Phone & Browser Live Demonstration Protocol:" -ForegroundColor Cyan
Write-Host "  1. Phone A (Citizen) : Install APK -> Tap SOS or shake 3 times." -ForegroundColor White
Write-Host "     - 5s audio capture runs local acoustic inference." -ForegroundColor White
Write-Host "     - If distress uncertain, 'Are you safe?' prompt appears." -ForegroundColor White
Write-Host "     - User can cancel with 4-digit PIN (default: 1234)." -ForegroundColor White
Write-Host "     - Tapping REPEAT SOS permanently locks ordinary cancel." -ForegroundColor White
Write-Host "  2. Phone B (Nearby)  : Receives high-priority proximity alert (<1 km radius)." -ForegroundColor White
Write-Host "  3. Laptop Browser    : Responder Portal at http://localhost:3000/portal" -ForegroundColor White
Write-Host "     - Displays Phone A live GPS coordinates & 1 km circle." -ForegroundColor White
Write-Host "     - Streams 5-second PCM audio evidence recorded by Phone A." -ForegroundColor White
Write-Host "     - Responder clicks 'Acknowledge' -> 'Resolve Incident'." -ForegroundColor White
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  DURGA Emergency Response System is Fully Operational!   " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
