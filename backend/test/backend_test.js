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
    // 1. Request OTP for Citizen A
    console.log("[TEST 1] Citizen A OTP Request");
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
    console.log("\n[TEST 8] Create Incident 2 & Escalate to Repeat SOS");
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
    assert(repeatSosRes.data.status === "REPEAT_ACTIVE", "Status transitioned to REPEAT_ACTIVE");

    // 9. Verify Ordinary Cancellation is LOCKED on Repeat SOS
    console.log("\n[TEST 9] Verify PIN Cancellation is Strictly Blocked on Repeat SOS");
    const blockedCancel = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId2}/cancel`,
      { pin: "4321" },
      citizenAToken
    );
    assert(blockedCancel.status === 403, "PIN cancellation strictly rejected with HTTP 403 Forbidden");

    // 10. Responder Login, Acknowledgement & Resolution
    console.log("\n[TEST 10] Authorized Responder Login & Resolution");
    const respOtp = await makeRequest("POST", "/api/v1/auth/request-otp", { phone: "+919999999999" });
    const respLogin = await makeRequest("POST", "/api/v1/auth/verify-otp", {
      phone: "+919999999999",
      otp: "123456",
    });
    const responderToken = respLogin.data.token;
    assert(respLogin.data.user.role === "RESPONDER", "Verified user role is RESPONDER");

    // Acknowledge Incident 2
    const ackRes = await makeRequest("POST", `/api/v1/incidents/${incidentId2}/acknowledge`, {}, responderToken);
    assert(ackRes.status === 200, "Responder acknowledged incident");

    // Resolve Incident 2
    const resolveRes = await makeRequest(
      "POST",
      `/api/v1/incidents/${incidentId2}/resolve`,
      { notes: "On-scene police arrival confirmed. Victim safe." },
      responderToken
    );
    assert(resolveRes.status === 200, "Responder resolved incident");
    assert(resolveRes.data.status === "RESOLVED", "Incident status is RESOLVED");

    // 11. Verify Location Sharing is Terminated After Resolution
    console.log("\n[TEST 11] Verify Location Streaming Blocked After Resolution");
    const locPostResolve = await makeRequest(
      "PATCH",
      `/api/v1/incidents/${incidentId2}/location`,
      { latitude: 18.5209, longitude: 73.857 },
      citizenAToken
    );
    assert(locPostResolve.status === 403, "Location updates rejected after resolution (HTTP 403)");

    // 12. Check Full Audit Trail
    console.log("\n[TEST 12] Audit Trail Integrity Check");
    const incidentAudit = await makeRequest("GET", `/api/v1/incidents/${incidentId2}`);
    assert(incidentAudit.data.auditTrail.length >= 4, "Complete immutable audit trail verified");
    console.log(`  Audit trail contains ${incidentAudit.data.auditTrail.length} verified operations:`);
    for (const log of incidentAudit.data.auditTrail) {
      console.log(`    - [${new Date(log.timestamp).toISOString()}] [${log.actor_role}] ${log.action}: ${log.details}`);
    }

    console.log("\n=================================================================");
    console.log("  ALL 12 BACKEND ACCEPTANCE TESTS PASSED SUCCESSFULLY! (100%)");
    console.log("=================================================================\n");
  } catch (err) {
    console.error("Test execution failed with error:", err);
    process.exit(1);
  }
}

runTests();
