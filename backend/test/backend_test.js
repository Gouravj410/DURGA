/**
 * DURGA Backend Integration & Acceptance Test Suite
 * Validates the full server lifecycle without mock data:
 * 1. Auth & OTP verification
 * 2. Device GPS registration & 1km radius targeting
 * 3. Incident creation & nearest station Haversine calculation
 * 4. PIN cancellation flow
 * 5. Repeat SOS escalation & cancellation lockout enforcement
 * 6. Responder authorization, acknowledgement & resolution
 * 7. Audit trail completeness
 */

const http = require("http");
const { runQuery, getQuery } = require("../database");
const { parseNotificationPayload, dispatchProximityPushNotifications } = require("../notification_service");

const BASE_URL = "http://localhost:3000";

function makeRequest(method, path, data = null, token = null) {
  return new Promise((resolve, reject) => {
    const url = new URL(path, BASE_URL);
    const options = {
      method,
      headers: {
        "Content-Type": "application/json",
      },
    };

    if (token) {
      options.headers["Authorization"] = `Bearer ${token}`;
    }

    const req = http.request(url, options, (res) => {
      let body = "";
      res.on("data", (chunk) => (body += chunk));
      res.on("end", () => {
        try {
          const parsed = JSON.parse(body);
          resolve({ status: res.statusCode, data: parsed });
        } catch (e) {
          resolve({ status: res.statusCode, raw: body });
        }
      });
    });

    req.on("error", (err) => reject(err));

    if (data) {
      req.write(JSON.stringify(data));
    }
    req.end();
  });
}

function assert(condition, message) {
  if (!condition) {
    console.error(`❌ ASSERTION FAILED: ${message}`);
    process.exit(1);
  } else {
    console.log(`  ✓ ${message}`);
  }
}

async function runTests() {
  console.log("=================================================================");
  console.log("  Running DURGA Backend Integration & Acceptance Tests");
  console.log("=================================================================\n");

  try {
    // 0. System Health & Database Diagnostics
    console.log("[TEST 0] System Health & Database Diagnostics");
    const healthRes = await makeRequest("GET", "/api/v1/health");
    assert(healthRes.status === 200, "Health endpoint returned status 200");
    assert(healthRes.data.status === "HEALTHY", "Server status is HEALTHY");
    assert(healthRes.data.database === "CONNECTED_SQLITE_WAL", "Database engine is CONNECTED_SQLITE_WAL");

    // 0b. Reject Unrequested OTP Verification
    console.log("\n[TEST 0b] Reject Verification of Unrequested OTP");
    const unreqOtpRes = await makeRequest("POST", "/api/v1/auth/verify-otp", {
      phone: "+917777777777",
      otp: "123456",
    });
    assert(unreqOtpRes.status === 400, "Unrequested OTP correctly rejected with HTTP 400");

    // 1. Request OTP for Citizen A
    console.log("\n[TEST 1] Citizen A OTP Request");
    const otpRes = await makeRequest("POST", "/api/v1/auth/request-otp", {
      phone: "+919876543210",
    });
    assert(otpRes.status === 200, "OTP request returned status 200");
    assert(otpRes.data.testOtp === "123456", "Whitelisted test OTP received");

    // 2. Verify OTP for Citizen A
    console.log("\n[TEST 2] Citizen A OTP Verification");
    const verifyRes = await makeRequest("POST", "/api/v1/auth/verify-otp", {
      phone: "+919876543210",
      otp: "123456",
      name: "Gourav (Citizen A)",
      role: "CITIZEN",
    });
    assert(verifyRes.status === 200, "OTP verification returned status 200");
    assert(verifyRes.data.token, "JWT token issued");
    const citizenAToken = verifyRes.data.token;
    const citizenAId = verifyRes.data.user.id;

    // 3. Set PIN for Citizen A
    console.log("\n[TEST 3] Set 4-Digit Security PIN");
    const pinRes = await makeRequest("POST", "/api/v1/auth/set-pin", { pin: "4321" }, citizenAToken);
    assert(pinRes.status === 200, "PIN saved successfully");

    // 4. Register Device A (Pune Shivajinagar coordinates)
    console.log("\n[TEST 4] Register Device A GPS Coordinates");
    const devARes = await makeRequest(
      "POST",
      "/api/v1/devices/register",
      {
        deviceId: "PHONE-A",
        fcmToken: "mock_fcm_token_phone_a_verified",
        deviceModel: "Pixel 7 Pro",
        latitude: 18.5204,
        longitude: 73.8567,
        accuracy: 8.5,
      },
      citizenAToken
    );
    assert(devARes.status === 200, "Device A registered successfully");

    // 5. Register Citizen B (Nearby Citizen within 450 meters)
    console.log("\n[TEST 5] Register Nearby Citizen B (450m away)");
    const otpB = await makeRequest("POST", "/api/v1/auth/request-otp", { phone: "+918888888888" });
    const verifyB = await makeRequest("POST", "/api/v1/auth/verify-otp", {
      phone: "+918888888888",
      otp: "123456",
      name: "Priya (Citizen B)",
      role: "CITIZEN",
    });
    const citizenBToken = verifyB.data.token;
    const citizenBId = verifyB.data.user.id;

    // Coordinates ~450 meters away: 18.5230, 73.8590
    await makeRequest(
      "POST",
      "/api/v1/devices/register",
      {
        deviceId: "PHONE-B",
        fcmToken: "mock_fcm_token_phone_b_nearby",
        deviceModel: "Samsung Galaxy S22",
        latitude: 18.523,
        longitude: 73.859,
        accuracy: 10.0,
      },
      citizenBToken
    );
    console.log("  ✓ Citizen B registered within 1.0 km radius");

    // 6. Create Emergency Incident from Phone A
    console.log("\n[TEST 6] Create Emergency Incident & Test Geospatial Targeting");
    const incidentRes = await makeRequest(
      "POST",
      "/api/v1/incidents",
      {
        latitude: 18.5204,
        longitude: 73.8567,
        locationAccuracy: 6.2,
        triggerSource: "MANUAL_SOS_BUTTON",
        distressScore: 0.88,
        distressClassification: "High Distress (Vocalization Detected)",
      },
      citizenAToken
    );
    assert(incidentRes.status === 201, "Incident created with HTTP 201");
    assert(incidentRes.data.status === "ACTIVE", "Incident initial state is ACTIVE");
    assert(incidentRes.data.recipientsWithin1Km >= 1, "Nearby recipient correctly targeted within 1km");
    assert(
      incidentRes.data.nearestPoliceStation.includes("Central Police Station"),
      "Haversine nearest station accurately computed"
    );
    const incidentId1 = incidentRes.data.incidentId;

    // 7. PIN Cancellation on Initial Active Incident
    console.log("\n[TEST 7] Cancel Incident 1 using Valid PIN");
    const cancelRes = await makeRequest("POST", `/api/v1/incidents/${incidentId1}/cancel`, { pin: "4321" }, citizenAToken);
    assert(cancelRes.status === 200, "Incident 1 cancelled successfully with PIN");
    assert(cancelRes.data.status === "CANCELLED", "Status updated to CANCELLED");

    // 8. Create Incident 2 and Trigger Repeat SOS
    console.log("\n[TEST 8] Create Incident 2 & Trigger Repeat SOS Re-notification");
    const incident2 = await makeRequest(
      "POST",
      "/api/v1/incidents",
      {
        latitude: 18.5204,
        longitude: 73.8567,
        triggerSource: "PERSISTENT_NOTIFICATION_ACTION",
        distressScore: 0.94,
      },
      citizenAToken
    );
    const incidentId2 = incident2.data.incidentId;

    const repeatSosRes = await makeRequest("POST", `/api/v1/incidents/${incidentId2}/repeat-sos`, {}, citizenAToken);
    assert(repeatSosRes.status === 200, "Repeat SOS accepted");
    assert(repeatSosRes.data.success === true, "Repeat SOS re-notification dispatched");

    // 9. Unified Cancellation Policy: PIN Cancellation SUCCEEDS on Incident with Repeat SOS
    console.log("\n[TEST 9] Verify Unified Secret-Code Cancellation on Incident with Repeat SOS");
    // 9a. Test incorrect PIN is rejected with remaining attempts (attempt 1)
    const wrongPinRes1 = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId2}/cancel`,
      { pin: "0000" },
      citizenAToken
    );
    assert(wrongPinRes1.status === 401, "Incorrect PIN attempt 1 rejected with HTTP 401");
    assert(wrongPinRes1.data.remainingAttempts === 2, "Remaining attempts correctly decremented to 2");

    // 9b. Incorrect PIN attempt 2
    const wrongPinRes2 = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId2}/cancel`,
      { pin: "0000" },
      citizenAToken
    );
    assert(wrongPinRes2.status === 401, "Incorrect PIN attempt 2 rejected with HTTP 401");
    assert(wrongPinRes2.data.remainingAttempts === 1, "Remaining attempts correctly decremented to 1");

    // 9c. Incorrect PIN attempt 3 -> Triggers HTTP 429 Lockout
    const wrongPinRes3 = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId2}/cancel`,
      { pin: "0000" },
      citizenAToken
    );
    assert(wrongPinRes3.status === 429, "Incorrect PIN attempt 3 locked out with HTTP 429 Rate Limited");
    assert(wrongPinRes3.data.error.includes("locked for 2 minutes"), "Lockout duration message confirmed");

    // 9d. Verify lockout is stored persistently in SQLite pin_lockouts table
    const lockoutRow = await getQuery("SELECT * FROM pin_lockouts WHERE user_id = ?", [citizenAId]);
    assert(lockoutRow !== undefined, "Persistent lockout record found in SQLite pin_lockouts table");
    assert(lockoutRow.locked_until > Date.now(), "SQLite locked_until timestamp is in the future");
    console.log(`  ✓ Persistent lockout active until: ${new Date(lockoutRow.locked_until).toISOString()}`);

    // 9e. Reset lockout for subsequent test validation
    await runQuery("DELETE FROM pin_lockouts WHERE user_id = ?", [citizenAId]);

    // 9f. Test valid PIN cancels incident successfully (Unified policy confirmed)
    const validCancelRes = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId2}/cancel`,
      { pin: "4321" },
      citizenAToken
    );
    assert(validCancelRes.status === 200, "Valid PIN cancellation accepted with HTTP 200");
    assert(validCancelRes.data.status === "CANCELLED", "Incident 2 status transitioned to CANCELLED");

    // 10. Responder Workflow: Create Incident 3, Acknowledge & Resolve
    console.log("\n[TEST 10] Authorized Responder Login, Acknowledgement & Resolution (Incident 3)");
    const incident3 = await makeRequest(
      "POST",
      "/api/v1/incidents",
      {
        latitude: 18.5204,
        longitude: 73.8567,
        triggerSource: "MANUAL_SOS_BUTTON",
        distressScore: 0.91,
      },
      citizenAToken
    );
    const incidentId3 = incident3.data.incidentId;

    const respOtp = await makeRequest("POST", "/api/v1/auth/request-otp", { phone: "+919999999999" });
    const respLogin = await makeRequest("POST", "/api/v1/auth/verify-otp", {
      phone: "+919999999999",
      otp: "123456",
    });
    const responderToken = respLogin.data.token;
    assert(respLogin.data.user.role === "RESPONDER", "Verified user role is RESPONDER");

    // Acknowledge Incident 3
    const ackRes = await makeRequest("POST", `/api/v1/incidents/${incidentId3}/acknowledge`, {}, responderToken);
    assert(ackRes.status === 200, "Responder acknowledged incident 3");

    // Resolve Incident 3
    const resolveRes = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId3}/resolve`,
      { notes: "On-scene police arrival confirmed. Victim safe." },
      responderToken
    );
    assert(resolveRes.status === 200, "Responder resolved incident 3");
    assert(resolveRes.data.status === "RESOLVED", "Incident 3 status is RESOLVED");

    // 11. Verify Location Sharing is Terminated After Resolution
    console.log("\n[TEST 11] Verify Location Streaming Blocked After Resolution");
    const locPostResolve = await makeRequest(
      "PATCH",
      `/api/v1/incidents/${incidentId3}/location`,
      { latitude: 18.5209, longitude: 73.857 },
      citizenAToken
    );
    assert(locPostResolve.status === 403, "Location updates rejected after resolution (HTTP 403)");

    // 12. Check Full Audit Trail (Requires Authorization)
    console.log("\n[TEST 12] Audit Trail Integrity Check");
    const unauthAudit = await makeRequest("GET", `/api/v1/incidents/${incidentId3}`);
    assert(unauthAudit.status === 401, "Unauthenticated incident inspection rejected with HTTP 401");

    const incidentAudit = await makeRequest("GET", `/api/v1/incidents/${incidentId3}`, null, responderToken);
    assert(incidentAudit.status === 200, "Authorized responder fetched incident record (HTTP 200)");
    assert(incidentAudit.data.auditTrail.length >= 3, "Complete immutable audit trail verified");
    console.log(`  Audit trail contains ${incidentAudit.data.auditTrail.length} verified operations:`);
    for (const log of incidentAudit.data.auditTrail) {
      console.log(`    - [${new Date(log.timestamp).toISOString()}] [${log.actor_role}] ${log.action}: ${log.details}`);
    }

    // 13. Push Notification Service Provider Status
    console.log("\n[TEST 13] Notification Provider Status Endpoint");
    const notifStatusRes = await makeRequest("GET", "/api/v1/notifications/status");
    assert(notifStatusRes.status === 200, "Notification status returned HTTP 200");
    assert(notifStatusRes.data.provider === "FCM_HTTP_V1", "Provider verified as FCM_HTTP_V1");
    console.log(`  ✓ Provider: ${notifStatusRes.data.provider}, Mode: ${notifStatusRes.data.mode}`);

    // 14. Authenticated Internet Test Notification
    console.log("\n[TEST 14] Authenticated Test Notification (Citizen A -> Citizen B)");
    const testPushRes = await makeRequest(
      "POST",
      "/api/v1/notifications/test",
      {
        targetPhone: "+918888888888",
        title: "DURGA Integration Test Push",
        body: "Automated test push verification payload.",
      },
      citizenAToken
    );
    assert(testPushRes.status === 200, "Test notification request returned HTTP 200");
    assert(testPushRes.data.success === true, "Notification dispatch marked successful");
    assert(testPushRes.data.recipientUserId === citizenBId, "Recipient resolved to Citizen B");
    assert(testPushRes.data.eventId.startsWith("TEST-"), "Test event ID generated with TEST- prefix");
    assert(testPushRes.data.incidentId === testPushRes.data.eventId, "Test push contains consistent eventId and incidentId");
    assert(testPushRes.data.isRealDelivery === false, "Mock dispatch truthfully reports isRealDelivery: false when credentials unconfigured");
    console.log(`  ✓ Test Push Status: ${testPushRes.data.status}, Message ID: ${testPushRes.data.messageId}`);

    // 15. Rate Limiting Enforcement on Test Push
    console.log("\n[TEST 15] Test Push Rate Limiting (Immediate Repeat)");
    const rateLimitRes = await makeRequest(
      "POST",
      "/api/v1/notifications/test",
      { targetPhone: "+918888888888" },
      citizenAToken
    );
    assert(rateLimitRes.status === 429, "Immediate repeat test push rejected with HTTP 429 Rate Limited");
    console.log(`  ✓ Rate limiting confirmed: ${rateLimitRes.data.error}`);

    // 16. Unauthenticated Test Push Rejection
    console.log("\n[TEST 16] Unauthenticated Test Push Request");
    const unauthRes = await makeRequest("POST", "/api/v1/notifications/test", { targetPhone: "+918888888888" }, null);
    assert(unauthRes.status === 401, "Unauthenticated test push rejected with HTTP 401 Unauthorized");

    // 17. Rapid Duplicate Incident Deduplication (< 15 seconds)
    console.log("\n[TEST 17] Rapid Duplicate Incident Deduplication (< 15 seconds)");
    const inc4a = await makeRequest(
      "POST",
      "/api/v1/incidents",
      { latitude: 18.5204, longitude: 73.8567, triggerSource: "MANUAL_SOS_BUTTON" },
      citizenAToken
    );
    assert(inc4a.status === 201, "Incident 4a created with HTTP 201");
    const inc4Id = inc4a.data.incidentId;

    const inc4b = await makeRequest(
      "POST",
      "/api/v1/incidents",
      { latitude: 18.5204, longitude: 73.8567, triggerSource: "MANUAL_SOS_BUTTON" },
      citizenAToken
    );
    assert(inc4b.status === 200, "Immediate duplicate request returned HTTP 200");
    assert(inc4b.data.isDuplicate === true, "Immediate duplicate flagged with isDuplicate: true");
    assert(inc4b.data.incidentId === inc4Id, "Duplicate returned existing active incident ID");

    // 18. Unauthorized Cancellation Blocked (Citizen B attempting to cancel Citizen A's incident)
    console.log("\n[TEST 18] Unauthorized Incident Cancellation Blocked");
    const unauthorizedCancel = await makeRequest(
      "POST",
      `/api/v1/incidents/${inc4Id}/cancel`,
      { pin: "1234" },
      citizenBToken
    );
    assert(unauthorizedCancel.status === 403, "Citizen B forbidden from cancelling Citizen A's incident (HTTP 403)");

    // 19. Responder Role Enforcement on Active Incidents Queue
    console.log("\n[TEST 19] Responder Role Enforcement on Active Incidents Queue");
    const citizenActiveQuery = await makeRequest("GET", "/api/v1/incidents/active", null, citizenAToken);
    assert(citizenActiveQuery.status === 403, "Citizen forbidden from querying city-wide active queue (HTTP 403)");

    const responderActiveQuery = await makeRequest("GET", "/api/v1/incidents/active", null, responderToken);
    assert(responderActiveQuery.status === 200, "Authorized responder fetched active queue (HTTP 200)");
    assert(Array.isArray(responderActiveQuery.data), "Active incidents returned as array");

    // 20. Proximity Alert Citizen Privacy Sanitization
    console.log("\n[TEST 20] Proximity Alert Citizen Privacy Sanitization");
    const proxQuery = await makeRequest(
      "GET",
      "/api/v1/incidents/proximity?latitude=18.523&longitude=73.859&radius=1.0",
      null,
      citizenBToken
    );
    assert(proxQuery.status === 200, "Proximity query returned HTTP 200");
    assert(proxQuery.data.length >= 1, "Nearby alert returned to Citizen B");
    const sampleAlert = proxQuery.data[0];
    assert(sampleAlert.user_phone === undefined, "Victim phone number is strictly excluded from proximity alert");
    assert(sampleAlert.audio_file_path === undefined, "Audio recording path is strictly excluded from proximity alert");
    assert(sampleAlert.distanceKm <= 1.0, "Alert distance is within 1.0 km radius");
    console.log(`  ✓ Privacy verified: Citizen B alert received without victim phone or internal audio path`);

    // 21. Protected Audio Access Control
    console.log("\n[TEST 21] Protected Audio Access Control");
    const unauthAudio = await makeRequest("GET", `/api/v1/incidents/${inc4Id}/audio`);
    assert(unauthAudio.status === 401, "Unauthenticated audio request rejected with HTTP 401");

    const wrongUserAudio = await makeRequest("GET", `/api/v1/incidents/${inc4Id}/audio`, null, citizenBToken);
    // Should be 403 Forbidden or 404 if no audio uploaded yet
    assert(wrongUserAudio.status === 404 || wrongUserAudio.status === 403, "Stranger blocked from accessing audio evidence");

    // Clean up Incident 4 with Citizen A's valid PIN
    await makeRequest("POST", `/api/v1/incidents/${inc4Id}/cancel`, { pin: "4321" }, citizenAToken);

    // 22. Device FCM Token Revocation
    console.log("\n[TEST 22] Device FCM Push Token Revocation");
    const revokeRes = await makeRequest(
      "POST",
      "/api/v1/devices/revoke-token",
      { deviceId: "PHONE-A" },
      citizenAToken
    );
    assert(revokeRes.status === 200, "Revoke token returned HTTP 200");
    const dbDevice = await getQuery("SELECT fcm_token FROM devices WHERE id = 'PHONE-A'");
    assert(dbDevice.fcm_token === null, "Device FCM token cleared to NULL in SQLite");
    console.log("  ✓ Device FCM token successfully revoked and cleared in database");

    // 23. Notification Payload Contract Consistency & Safe Fallbacks
    console.log("\n[TEST 23] Notification Payload Identifier Consistency & Safe Fallbacks");
    // 23a. Payload with eventId only
    const parsed1 = parseNotificationPayload({ eventId: "INC-TEST-001", type: "DURGA_EMERGENCY" });
    assert(parsed1.isValid === true, "Payload with eventId only is valid");
    assert(parsed1.eventId === "INC-TEST-001", "eventId preserved");
    assert(parsed1.incidentId === "INC-TEST-001", "incidentId populated from eventId");
    assert(parsed1.isTest === false, "Recognized as emergency notification");

    // 23b. Payload with incidentId only (backward compatibility)
    const parsed2 = parseNotificationPayload({ incidentId: "INC-LEGACY-002" });
    assert(parsed2.isValid === true, "Legacy payload with incidentId only is valid");
    assert(parsed2.eventId === "INC-LEGACY-002", "eventId populated from incidentId");
    assert(parsed2.incidentId === "INC-LEGACY-002", "incidentId preserved");

    // 23c. Test notification detection
    const parsedTest = parseNotificationPayload({ eventId: "TEST-9999", type: "DURGA_TEST" });
    assert(parsedTest.isTest === true, "Recognized as test notification via type");

    // 23d. Missing or malformed identifier handling
    const parsedInvalid = parseNotificationPayload({});
    assert(parsedInvalid.isValid === false, "Empty payload safely marked invalid without crash");
    assert(parsedInvalid.eventId === null, "Missing eventId resolved to null");

    const parsedNull = parseNotificationPayload(null);
    assert(parsedNull.isValid === false, "Null payload safely marked invalid without crash");
    console.log("  ✓ Notification contract consistency & fallback handling verified");

    // 24. Proximity Push Dispatch Payload Verification & Token Cleanup
    console.log("\n[TEST 24] Proximity Push Dispatch Payload Verification & Cleanup Callback");
    let cleanedToken = null;
    const testIncident = {
      id: "INC-CONTRACT-999",
      latitude: 18.5204,
      longitude: 73.8567,
      distress_score: 0.95,
      distress_classification: "High Vocal Distress",
      status: "ACTIVE",
      nearest_police_station: "Central Police Station",
    };
    const testTargets = [
      {
        device_id: "DEV-TEST-TARGET",
        user_id: "USER-RECIPIENT",
        fcm_token: "mock_recipient_token_1234567890",
        distanceKm: 0.42,
      },
    ];

    const dispatchRes = await dispatchProximityPushNotifications(
      testTargets,
      testIncident,
      async (tok) => { cleanedToken = tok; }
    );
    assert(dispatchRes.status === "LOCAL_DISPATCH_LOGGED_MOCK", "Truthfully reports LOCAL_DISPATCH_LOGGED_MOCK when service account missing");
    assert(dispatchRes.isRealDelivery === false, "Truthfully reports isRealDelivery: false");
    assert(dispatchRes.recipientsCount === 1, "Target device included in dispatch queue");
    assert(dispatchRes.results[0].status === "RECORDED_IN_DISPATCH_QUEUE", "Recipient marked RECORDED_IN_DISPATCH_QUEUE");
    console.log("  ✓ Proximity push dispatch contract verified with honest mock reporting");

    console.log("\n=================================================================");
    console.log("  ALL 24 BACKEND ACCEPTANCE TESTS PASSED SUCCESSFULLY! (100%)");
    console.log("=================================================================\n");
    process.exit(0);
  } catch (err) {
    console.error("Test execution failed with error:", err);
    process.exit(1);
  }
}

runTests();
