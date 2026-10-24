# DURGA
### Dynamic Unified Response & Geo-Alert System
*Autonomous AI & Emergency Response Engineering Platform*

---

## 1. Project Overview & Prototype Boundary

**DURGA** is an autonomous safety and emergency-response platform built for physical multi-device testing and academic defense. Unlike passive speed-dialers or unverified mock applications, DURGA operates as an end-to-end coordinated emergency response engine:

$$\text{SENSE} \longrightarrow \text{VERIFY} \longrightarrow \text{RECORD} \longrightarrow \text{ASSESS} \longrightarrow \text{LOCATE} \longrightarrow \text{ALERT} \longrightarrow \text{RESPOND}$$

> **Important System Boundary**: DURGA is an authorized prototype emergency response platform. It is not an official municipal or state police dispatch integration. Police proximity is computed using verified physical coordinates. No emergency service or authority is claimed notified without active, verified integration evidence.

---

## 2. Architectural Architecture

```text
       PROTECTED CITIZEN (PHONE A)
                 │
   [1] Trigger: In-App SOS / 3-Shake Gesture / Persistent Lock-Screen Notification Action
                 │
   [2] 5-Second 16-bit PCM Audio Capture @ 16 kHz Mono
                 │
   [3] On-Device Acoustic Feature Extraction (RMS dBFS, Peak Amplitude, ZCR)
                 │
   [4] Safety Verification Gate
       - If Distress Uncertain: Display "Are you safe?" prompt (10s countdown)
         * Confirmed Policy: Prompt timeout does NOT escalate solely due to timeout!
       - If High Distress or User Taps "Need Help": Escalate to Central Backend
                 │
                 ▼
   [5] DURGA Central Backend Service (Node.js + SQLite3 on Port 3000)
       - Authenticates JWT session & enforces RBAC
       - Calculates Haversine distance to nearest verified police station
       - Queries all registered devices within 1.0 km radius
       - Dispatches High-Priority FCM push notifications to nearby citizens
                 │
        ┌────────┴───────────────────────────┐
        ▼                                    ▼
NEARBY CITIZEN (PHONE B)            TACTICAL RESPONDER COMMAND PORTAL
 - High-Priority Proximity Alert     - http://localhost:3000/portal
 - Telemetry & Walking Directions    - Live Leaflet GIS Tactical Map (1 km Geofence)
 - Direct Police (112) Deep-Link     - Protected 5-Second Audio Evidence Player
                                     - Incident Lifecycle: [Acknowledge] -> [Resolve]
                                     - Complete Immutable Audit Trail
```

---

## 3. Core Subsystems

### A. Native Android Application (`app/`)
- **Target OS**: Android 10 (API 29) through Android 14 (API 34).
- **Architecture**: Kotlin 1.9+, Clean Architecture (MVVM + Coroutines + StateFlow).
- **Incident State Machine (`IncidentStateMachine.kt`)**: Explicit 12-state FSM enforcing non-negotiable safety rules:
  - Safety prompt timeout returns safely to `IDLE` (no false alarms).
  - PIN cancellation with 3-attempt brute-force throttling and PBKDF2 hashing.
  - Repeat SOS locks ordinary cancellation and requires authorized responder resolution.
- **Multilingual Support**: Complete localized string resources in **English** (`values/`), **Hindi** (`values-hi/`), and **Marathi** (`values-mr/`).
- **Multi-Trigger Support**:
  - In-App Hero SOS Button.
  - In-App 3-Shake Gesture Listener (`ShakeDetector.kt`, >13 m/s²).
  - Persistent Lock-Screen Action (`DurgaSafetyService.kt`, Android 14 compliant foreground service).
- **Acoustic Intelligence (`AudioRecorder.kt`, `DistressAnalyzer.kt`)**: Captures 5s uncompressed PCM WAV and scores vocal distress features without transmitting raw audio prematurely.

### B. Central Backend API (`backend/`)
- **Runtime**: Node.js v24 + Express + SQLite3 (`durga.db`).
- **Geospatial Engine (`geo_utils.js`)**: Haversine distance calculation and spatial filtering for registered devices within a **1.0 km radius**.
- **Role-Based Access Control (`auth.js`)**: Server-enforced role permissions (`CITIZEN`, `RESPONDER`).
- **Push Notifications (`notification_service.js`)**: Firebase Cloud Messaging (FCM HTTP v1) integration with truthful status reporting.
- **Immutable Audit Trail (`database.js`)**: Logs all sensitive transitions and operations chronologically.

### C. Tactical Responder Command Portal (`backend/public/`)
- Accessible at `http://localhost:3000/portal`.
- Real-time Server-Sent Events (SSE) telemetry feed.
- Leaflet GIS map with victim marker, 1 km geofence circle, and curated Pune police stations.
- Protected audio player streaming 5-second PCM audio evidence.
- Authenticated resolution modal requiring mandatory audit notes.

---

## 4. Test Verification & Acceptance Matrix

### Automated Unit Tests (`IncidentStateMachineTest.kt`)
Run via `.\gradlew.bat testDebugUnitTest`:
```text
✓ testInitialStateIsIdle
✓ testNormalTriggerToEscalationWhenDistressDetected
✓ testUncertainDistressShowsAreYouSafeAndDoesNotEscalateOnTimeout
✓ testInitialIncidentCancelledWithCorrectPin
✓ testRepeatSosDisablesOrdinaryCancellationAndRequiresResponder
✓ testPinThrottlingLocksAfterThreeIncorrectAttempts
Status: 100% PASSED (6/6 tests)
```

### Automated Backend Integration Tests (`backend/test/run_tests.js`)
Run via `node test/run_tests.js`:
```text
✓ Citizen A OTP & JWT Authentication (HTTP 200)
✓ 4-Digit Security PIN Saved with PBKDF2 Hash
✓ Device A GPS Registered (Shivajinagar)
✓ Citizen B Registered within 1.0 km Radius (377m away)
✓ Incident Created & Nearest Police Station Calculated (HTTP 201)
✓ Initial Incident Cancelled via Valid PIN
✓ Repeat SOS Escalated (Status: REPEAT_ACTIVE)
✓ PIN Cancellation Strictly Rejected on Repeat SOS (HTTP 403 Forbidden)
✓ Authorized Responder Acknowledged & Resolved Incident
✓ Location Streaming Blocked After Resolution (HTTP 403 Forbidden)
✓ Complete Immutable Audit Trail Verified (5 chronological records)
Status: 100% PASSED (12/12 tests)
```

---

## 5. Two-Phone Demonstration Protocol

### Step 1: Start Backend & Launch Responder Portal
Run the automated launcher script in PowerShell:
```powershell
.\run_demo.ps1
```
Or start manually:
```powershell
cd backend
npm start
```
Open your browser at `http://localhost:3000/portal` and log in with responder credentials:
- **Phone**: `+919999999999`
- **Test OTP**: `123456`

### Step 2: Install APK on Android Devices
```powershell
adb install -r app\build\outputs\apk\debug\app-debug.apk
```

### Step 3: Demonstration Scenarios
1. **Scenario 1 (PIN Cancellation)**: Trigger SOS on Phone A → Enter PIN `1234` → Incident cancels cleanly.
2. **Scenario 2 (1 km Proximity Dispatch)**: Trigger SOS on Phone A → Phone B (<1 km) receives proximity push notification → Responder portal beeps and displays victim on map.
3. **Scenario 3 (Repeat SOS Lockdown)**: Tap `REPEAT SOS` on Phone A → Ordinary PIN cancellation button disappears permanently.
4. **Scenario 4 (Responder Resolution)**: Dispatcher on portal listens to 5s audio evidence, clicks `Acknowledge`, then `Resolve Incident` with notes → Both Phone A and Phone B update instantly to `RESOLVED`.
