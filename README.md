# DURGA
### Dynamic Unified Response & Geo-Alert System
*Semester 5 Mini Project — Autonomous AI & Emergency Response Engineering*

---

## 1. Project Overview

**DURGA** is an AI-assisted emergency response Android platform designed primarily for women's personal safety and multi-hazard distress situations. Unlike conventional emergency apps that merely act as decorative speed dialers or SMS triggers, DURGA operates as an end-to-end coordinated emergency response engine:

$$\text{SENSE} \longrightarrow \text{VERIFY} \longrightarrow \text{RECORD} \longrightarrow \text{ASSESS} \longrightarrow \text{LOCATE} \longrightarrow \text{ALERT} \longrightarrow \text{RESPOND}$$

---

## 2. Core Technological Architecture

```text
       VICTIM PHONE (ROLE: USER)
                 │
  [1] SOS Button / 3-Shake Gesture
                 │
  [2] 5-Second Cancellation Window (Cancel -> Safe)
                 │
  [3] Microphone Capture (16-bit PCM WAV @ 16 kHz)
                 │
  [4] AI Acoustic Feature Extraction (RMS, dBFS, Peak, ZCR)
                 │
  [5] GPS Acquisition (FusedLocationProviderClient)
                 │
  [6] Nearest Police Station Search (Haversine Geo-calc)
                 │
                 ▼
  [7] Cloud Publication (Firestore & Firebase Storage)
                 │
        ┌────────┴────────┐
        ▼                 ▼
EMERGENCY CONTACT      POLICE RESPONDER
 (Guardian Console)    (Incident Command)
  - Live Alert Badge    - Station Banner
  - Victim Telemetry    - Telemetry & GPS Route
  - Deep-link Route     - Audio Playback
  - Audio Playback      - Unit Dispatch & Resolve
```

- **Operating System**: Android (minSdk 26, targetSdk 34, compileSdk 34)
- **Language**: Kotlin 1.9.24 + Coroutines / Flow
- **Design System**: Material 3 Dark Slate (`#0B1120`) & Alert Red (`#E11D48`)
- **Location Engine**: Google Play Services `FusedLocationProviderClient` + High Accuracy Satellite fix
- **Acoustic Engine**: Android `AudioRecord` (16-bit PCM, 16000 Hz Mono) + RIFF WAV Header Writer
- **Acoustic AI Scoring**: `DurgaScoringDistressAnalyzer` computing RMS energy, dBFS, peak transients, and zero-crossing frequency
- **Cloud Backend**: Google Firebase BoM 33.2.0 (`Firestore`, `Storage`, `Messaging`, `Auth`) + Resilient Local-First Fallback
- **Push Alerts**: `DurgaFirebaseMessagingService` with Heads-Up High Priority notification channel

---

## 3. Real Multi-Role Architecture

A single installed application supports three distinct operational roles, switchable via the Role selector or Tab Bar:

1. **USER (Protected Citizen)**:
   - Live sensor monitoring (Accelerometer 3-shake gesture listener).
   - Prominent pulsating SOS activation trigger.
   - 5-second verification countdown overlay with explicit user cancellation.
   - 5-second acoustic recording status indicator.
   - Real-time GPS and AI telemetry feedback.
   - Guardian contact configuration.

2. **EMERGENCY CONTACT (Guardian Monitor)**:
   - Real-time incoming distress badge (`ACTIVE`, `RESPONDING`, `RESOLVED`).
   - Victim details, AI distress level, and calculated GPS accuracy.
   - Nearest known police station distance.
   - **`PLAY AUDIO`**: Listens directly to the recorded distress audio sample.
   - **`MAP ROUTE`**: Deep-links into Google Maps navigation coordinates (`geo:lat,lon`).
   - **`CALL USER`**: Launches the phone dialer (`tel:...`).

3. **POLICE / RESPONDER (Incident Command Console)**:
   - Connected station command banner.
   - Incident queue with real-time status updates (`ACTIVE`, `ACKNOWLEDGED`, `RESPONDING`, `RESOLVED`).
   - Victim coordinates with accuracy radius.
   - **`PLAY AUDIO`**: Evaluates audio sample for acoustic threats.
   - **`GPS ROUTE`**: Displays shortest emergency route to victim.
   - **Lifecycle Transition Buttons**:
     - `[ACK]` -> Sets status to `ACKNOWLEDGED`
     - `[DISPATCH]` -> Dispatches unit, transitions incident to `RESPONDING`
     - `[RESOLVE]` -> Resolves incident across all devices and returns system to protected standby.

---

## 4. Physical Acoustic AI Scoring Model

The `DurgaScoringDistressAnalyzer` operates on actual physical properties of the recorded audio:

| Metric | Calculation | Physical Significance |
| :--- | :--- | :--- |
| **RMS Energy** | $\sqrt{\frac{1}{N}\sum x_i^2}$ | Average sound pressure and volume |
| **dBFS** | $20 \log_{10}(\text{RMS} / 32767)$ | Decibels Full-Scale ($0 \text{ dBFS}$ max, $-96 \text{ dBFS}$ silence) |
| **Peak Transient** | $\max \|x_i\|$ | Sudden loud screams, impacts, or panic spikes |
| **Zero Crossing Rate (ZCR)** | $\frac{1}{N}\sum \|\text{sgn}(x_i) - \text{sgn}(x_{i-1})\|$ | High frequency vocal turbulence / shrieks |

### Scoring Output:
- **High Distress ($\ge 0.70$)**: High-intensity vocalization or screaming detected ($dBFS > -22 \text{ dBFS}$).
- **Moderate Distress ($0.40 - 0.69$)**: Elevated speech or shouting detected ($dBFS > -34 \text{ dBFS}$).
- **Low Distress ($< 0.40$)**: Low ambient room noise or whisper.

---

## 5. Demonstration Script (Step-by-Step)

To demonstrate DURGA across physical devices or on an emulator:

1. **Launch App**: Open DURGA. Choose **USER** role.
2. **Trigger Emergency**: Tap the **SOS** button or shake the phone deliberately 3 times.
3. **Safety Window**: The 5-second countdown appears. Do not press cancel.
4. **Audio Capture**: The system transitions to `RECORDING AUDIO DISTRESS` for 5 seconds. Speak or shout into the microphone.
5. **AI & Geospatial Pipeline**: The app analyzes acoustic features, acquires GPS coordinates, calculates distance to nearest police station, and uploads event to Firestore.
6. **Switch to CONTACT Role**: Tap `CONTACT` tab. Observe the live incident badge, victim name, coordinates, and tap **PLAY AUDIO** to listen to the recorded audio.
7. **Switch to POLICE Role**: Tap `POLICE` tab. Observe the incident command console.
8. **Dispatch**: Tap `[DISPATCH]`. Observe status transition to `RESPONDING` on both Responder and Contact screens.
9. **Resolve**: Tap `[RESOLVE]`. The incident is closed, and the system resets cleanly to `PROTECTED — SENSORS ACTIVE`.

---

## 6. Build and Test Verification

### Running Unit Tests:
```bash
./gradlew testDebugUnitTest
```
**Results**:
- `GeoUtilsTest`: 3/3 passed (Haversine formula validation, coordinate shifts).
- `DistressAnalyzerTest`: 3/3 passed (Empty file fallback, High-amplitude scream classification, Ambient noise classification).
- `EmergencyRepositoryTest`: 3/3 passed (Event registration, deduplication, status transitions).
- `EmergencyStateTest`: 2/2 passed (Complete 13-state emergency finite state machine).

### Building and Installing APK:
```bash
./gradlew assembleDebug
adb install -r app/build/outputs/apk/debug/app-debug.apk
```
Output: `app/build/outputs/apk/debug/app-debug.apk` (11.0 MB)

---

## 7. Security and Privacy

- **Firestore Rules** (`firestore.rules`): Validates schema, protects log deletion, enforces role permissions.
- **Storage Rules** (`storage.rules`): Restricts uploads to audio MIME types under 5 MB.
- **Privacy by Design**: Microphone is **never** recorded continuously in the background. It is activated exclusively after the 5-second verification window expires during an explicit emergency.

---

## 8. Interactive Laptop Showcase & System Simulation

For project evaluations, classroom defense, and viva demonstrations where physical phone projection might be inconvenient, a comprehensive **Laptop Demonstration Simulator** is included in the `simulation/` directory.

### Quick Start (Single Click / Terminal):
```powershell
.\run_simulation.ps1
```
*Or double-click `simulation/index.html` in any modern web browser.*

### Showcase Features:
- **Triple-Role Mesh**: Side-by-side interactive mockups of **Victim Smartphone (`USER`)**, **Trusted Contact Phone (`EMERGENCY_CONTACT`)**, and **Police Dispatch Terminal (`POLICE_RESPONDER`)**.
- **Interactive Triggers**: 3-axis accelerometer shake canvas (>2.7G spike simulation) and pulsating SOS button.
- **Verification Window**: 5-second SVG countdown with cancel/false-alarm button.
- **Acoustic AI Engine**: Real-time 16-bit PCM waveform oscilloscope computing RMS, dBFS, and Zero-Crossing Rate.
- **Audio Evidence Player**: Working in-browser player on the contact device to listen to ambient audio recordings.
- **Tactical Map (Leaflet.js)**: Displays victim coordinates, all 5 Pune police stations, nearest station calculation via Haversine, and animated police cruiser movement along the vector route.
- **Police CAD Controls**: Full interactive lifecycle buttons (`[ACKNOWLEDGE]`, `[DISPATCH PATROL]`, `[RESOLVE & ARCHIVE]`).
- **Forensic AI Acoustic Lab**: Interactive modal to test and benchmark scream detection, elevated shouting, and ambient noise against mathematical formulas.

