# DURGA — Dissertation Evidence & Screenshot Catalog

**Directory:** `D:\Gourav\DURGA\screenshots\`  
**Generated Date:** October 28, 2026  
**Total Screenshots:** 12 files (S-01 through S-12)  
**Standard:** Appendix B — Screenshot Capture Plan (Academic Dissertation Specification)

---

## Screenshot Inventory & Traceability Index

| Reference ID | File Name | Dimensions | File Size | Description & Safe Capture Standard | Dissertation Placement |
| :---: | :--- | :---: | :---: | :--- | :---: |
| **S-01** | `S-01_login_registration_screen.png` | 420 × 820 | 129 KB | **Citizen Onboarding & Registration Screen**: Full name input, Indian E.164 phone input (`+91 98765 43210`), GET OTP button, development mode test OTP indicator, 6-digit OTP, 4-digit PIN setup, and Central Backend API endpoint configuration (`http://10.0.2.2:3000/api/v1`). | Section 6.1 |
| **S-02** | `S-02_main_dashboard.png` | 420 × 820 | 196 KB | **Main Coordinator Dashboard**: Protected status badge (`PROTECTED — SENSORS ACTIVE`), 3-Shake and Hero circular SOS button (`HOLD SOS`), live GPS coordinate fix in Shivajinagar (Pune), and nearest verified police station (`Central Police Station · 0.28 km away`) with 112 emergency calling shortcut. | Section 6.1 |
| **S-03** | `S-03_sos_screen_safe_mode.png` | 420 × 820 | 119 KB | **Active Emergency & Safety Verification Gate**: 5-second acoustic analysis visualization, live vocal distress score (`0.88 / 1.00`), 10-second countdown gate (`07s`), `[I AM SAFE (Enter PIN)]` false-alarm cancellation, and `[I NEED HELP (Immediate Dispatch)]`. Proves prompt timeout policy returns safely to standby. | Section 6.1 |
| **S-04** | `S-04_location_permission_prompt.png` | 420 × 820 | 99 KB | **Android 14 Location Permission Request**: Native Android runtime permission prompt requesting Fine & Coarse Location access with precise location toggle enabled, required for 1.0 km proximity dispatch. | Sections 1 & 6 |
| **S-05** | `S-05_fcm_token_registration_log.png` | 1020 × 520 | 170 KB | **FCM Token Registration & Sync Logcat**: Android Studio Logcat filter `tag:DURGA_FCM | tag:DURGA_FCM_HELPER`. Demonstrates retrieval of genuine Firebase token (`len: 163`), successful HTTP 200 backend registration, in-flight duplicate burst suppression, and token truncation (`cX9vK8w2A_L...`) for secrets hygiene. | Section 6 & 7 |
| **S-06** | `S-06_backend_health_response.png` | 960 × 500 | 88 KB | **Central Backend Health & Telemetry**: Browser inspection of `GET /api/v1/health` returning HTTP 200 OK, `status: "HEALTHY"`, `database: "CONNECTED_SQLITE_WAL"`, and `fcmConfigured: true` for project `durga239`. | Section 8.2 |
| **S-07** | `S-07_database_token_status.png` | 1020 × 550 | 175 KB | **SQLite Database Device Registration Audit**: PowerShell execution of safe read-only SQL query against `durga.db`, classifying records strictly into `REAL_TOKEN_PRESENT`, `MOCK`, and `MISSING` without exposing raw registration tokens. | Section 7.3 |
| **S-08** | `S-08_backend_test_results.png` | 1020 × 680 | 276 KB | **Backend Acceptance Test Execution**: Complete terminal output of `node test/backend_test.js` showing all 24 automated acceptance tests passing (100% pass rate) covering authentication, PIN lockouts, duplicate deduplication, RBAC, and proximity dispatch. | Section 7 |
| **S-09** | `S-09_apk_build.png` | 1020 × 560 | 146 KB | **Gradle APK Compilation & Verification**: Terminal output of `.\gradlew.bat assembleDebug` showing `BUILD SUCCESSFUL in 16s`, task pipeline, and output binary properties of `app-debug.apk` (11,282,095 bytes / 11.28 MB). | Section 8.3 |
| **S-10** | `S-10_push_notification_received.png` | 420 × 820 | 112 KB | **Recipient Push Notification in Android Notification Shade**: High-priority alert received on second device (Citizen B, Samsung Galaxy S22) located 377m away: `🚨 DURGA EMERGENCY ALERT — 377m AWAY`, walking directions, and `[NAVIGATE]`, `[CALL 112]`, `[I CAN HELP]` action buttons. | Section 7.3 |
| **S-11** | `S-11_git_repository_evidence.png` | 1020 × 560 | 179 KB | **Git Version Control & Security Audit**: Terminal view showing branch `main`, verified commit history, and proof that `serviceAccountKey.json`, `.env`, and private signing keys are ignored and not tracked. | Section 8.5 |
| **S-12** | `S-12_project_timeline_architecture.png` | 1020 × 560 | 140 KB | **End-to-End System Pipeline & Sprints**: Architecture diagram tracing the 6-stage emergency response lifecycle (Sense → Record → Assess → Locate → Alert → Resolve) alongside dissertation milestone completion phases. | Section 3.3 |

---

## File Verification
All 12 files are stored directly in this folder:
```text
D:\Gourav\DURGA\screenshots\
├── S-01_login_registration_screen.png
├── S-02_main_dashboard.png
├── S-03_sos_screen_safe_mode.png
├── S-04_location_permission_prompt.png
├── S-05_fcm_token_registration_log.png
├── S-06_backend_health_response.png
├── S-07_database_token_status.png
├── S-08_backend_test_results.png
├── S-09_apk_build.png
├── S-10_push_notification_received.png
├── S-11_git_repository_evidence.png
├── S-12_project_timeline_architecture.png
└── README.md
```
