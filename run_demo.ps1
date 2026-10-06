# DURGA - Automated Demonstration & Multi-Device Verification Script
# Dynamic Unified Response & Geo-Alert System (Semester 5 Mini Project)

Write-Host "==========================================================" -ForegroundColor Cyan
Write-Host "  DURGA - Emergency Response System Demonstration Script   " -ForegroundColor Cyan
Write-Host "==========================================================" -ForegroundColor Cyan

$adb = "$env:LOCALAPPDATA\Android\Sdk\platform-tools\adb.exe"

if (!(Test-Path $adb)) {
    Write-Host "[ERROR] ADB not found at $adb. Please check ANDROID_HOME." -ForegroundColor Red
    exit 1
}

# 1. Check connected devices
Write-Host "`n[1/6] Checking connected Android devices/emulators..." -ForegroundColor Yellow
& $adb devices

# 2. Install latest APK
$apkPath = ".\apk\DURGA_v1.0.0.apk"
if (Test-Path $apkPath) {
    Write-Host "`n[2/6] Installing latest DURGA APK ($apkPath)..." -ForegroundColor Yellow
    & $adb install -r $apkPath
} else {
    Write-Host "`n[2/6] APK not found in .\apk\, installing from build outputs..." -ForegroundColor Yellow
    & $adb install -r ".\app\build\outputs\apk\debug\app-debug.apk"
}

# 3. Grant runtime permissions
Write-Host "`n[3/6] Granting required hardware & location permissions..." -ForegroundColor Yellow
& $adb shell pm grant com.durga.emergency android.permission.RECORD_AUDIO
& $adb shell pm grant com.durga.emergency android.permission.ACCESS_FINE_LOCATION
& $adb shell pm grant com.durga.emergency android.permission.ACCESS_COARSE_LOCATION
& $adb shell pm grant com.durga.emergency android.permission.POST_NOTIFICATIONS

# 4. Wake up and unlock device
Write-Host "`n[4/6] Waking device screen & launching DURGA..." -ForegroundColor Yellow
& $adb shell input keyevent 224
& $adb shell wm dismiss-keyguard
& $adb shell am start -n com.durga.emergency/.ui.main.MainActivity

# 5. Run automated unit test suite
Write-Host "`n[5/6] Running core Unit Test Suite..." -ForegroundColor Yellow
$env:JAVA_HOME = "C:\Program Files\Android\Android Studio\jbr"
.\gradlew.bat testDebugUnitTest --quiet

if ($LASTEXITCODE -eq 0) {
    Write-Host "  -> ALL UNIT TESTS PASSED (Haversine, AI Acoustics, State Machine, Repository)" -ForegroundColor Green
} else {
    Write-Host "  -> UNIT TESTS FAILED" -ForegroundColor Red
}

# 6. Summary instructions for physical testing
Write-Host "`n[6/6] Demonstration Instructions:" -ForegroundColor Cyan
Write-Host "  - Phone 1 (Victim)   : Select USER role -> Tap SOS or shake device 3 times."
Write-Host "  - Verification (5s)  : Watch 5-second countdown window appear."
Write-Host "  - Audio Capture (5s) : System records 160kB PCM WAV audio and runs AI scoring."
Write-Host "  - Phone 2 (Guardian) : Switch to CONTACT role -> View live alert & tap PLAY AUDIO."
Write-Host "  - Phone 3 (Police)   : Switch to POLICE role -> Tap DISPATCH -> Tap RESOLVE."
Write-Host "`n==========================================================" -ForegroundColor Green
Write-Host "  DURGA Prototype is ACTIVE and ready for presentation!    " -ForegroundColor Green
Write-Host "==========================================================" -ForegroundColor Green
