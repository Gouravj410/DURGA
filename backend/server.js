/**
 * DURGA Central Emergency Response Backend Server
 * Production-minded API service with Geospatial Targeting, RBAC,
 * Server-Sent Events (SSE) telemetry, and Real Incident Command Portal.
 */

const express = require("express");
const cors = require("cors");
const path = require("path");
const fs = require("fs");
const crypto = require("crypto");
const multer = require("multer");

const {
  db,
  UPLOADS_DIR,
  runQuery,
  getQuery,
  allQuery,
} = require("./database");
const {
  calculateHaversineDistanceKm,
  findNearestPoliceStation,
  filterDevicesWithinRadius,
} = require("./geo_utils");
const {
  jwt,
  JWT_SECRET,
  requestOtp,
  verifyOtp,
  setUserPin,
  verifyUserPin,
  authenticateToken,
  requireRole,
} = require("./auth");
const {
  dispatchProximityPushNotifications,
  sendTestPushNotification,
  isFcmConfigured,
  getServiceAccountPath,
} = require("./notification_service");

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());

// Normalize non-standard Content-Type (e.g., application/json; utf-8 -> application/json; charset=utf-8)
app.use((req, res, next) => {
  const ct = req.headers["content-type"];
  if (ct && ct.includes("application/json") && ct.includes("utf-8") && !ct.includes("charset=")) {
    req.headers["content-type"] = "application/json; charset=utf-8";
  }
  next();
});

app.use(express.json());
app.use(express.urlencoded({ extended: true }));

// Serve Responder Command Portal static assets
app.use("/portal", express.static(path.join(__dirname, "public")));
app.use(express.static(path.join(__dirname, "public")));

// Multer storage for 5-second PCM audio evidence
const storage = multer.diskStorage({
  destination: (req, file, cb) => cb(null, UPLOADS_DIR),
  filename: (req, file, cb) => {
    const ext = path.extname(file.originalname) || ".wav";
    cb(null, `incident_${req.params.id}_${Date.now()}${ext}`);
  },
});
const upload = multer({
  storage,
  limits: { fileSize: 10 * 1024 * 1024 }, // 10MB limit
});

// SSE Telemetry Clients for real-time dashboard updates
const sseClients = new Set();

function broadcastEvent(eventType, data) {
  const message = `event: ${eventType}\ndata: ${JSON.stringify(data)}\n\n`;
  for (const client of sseClients) {
    client.write(message);
  }
}

async function writeAuditLog(incidentId, actorId, actorRole, action, details) {
  try {
    await runQuery(
      `INSERT INTO audit_logs (incident_id, actor_id, actor_role, action, details, timestamp)
       VALUES (?, ?, ?, ?, ?, ?)`,
      [incidentId, actorId, actorRole, action, details, Date.now()]
    );
  } catch (e) {
    console.error("[AUDIT LOG ERROR]", e.message);
  }
}

// ==============================================================================
// 1. Authentication & Identity Endpoints
// ==============================================================================

app.post("/api/v1/auth/request-otp", (req, res) => {
  const phone = (req.body?.phone || req.body?.phoneNumber || "").toString().trim();
  if (!phone) {
    return res.status(400).json({ error: "Phone number is required." });
  }
  try {
    const result = requestOtp(phone);
    res.json(result);
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/v1/auth/verify-otp", async (req, res) => {
  const phone = (req.body?.phone || req.body?.phoneNumber || "").toString().trim();
  const otp = (req.body?.otp || req.body?.code || req.body?.verificationCode || "").toString().trim();
  const name = (req.body?.name || req.body?.userName || "Protected Citizen").toString().trim();
  const role = (req.body?.role || "CITIZEN").toString().trim();

  if (!phone || !otp) {
    console.warn(`[DURGA AUTH WARN] /verify-otp missing phone or otp: phone="${phone}", otp="${otp}"`);
    return res.status(400).json({ error: "Phone number and OTP are required." });
  }
  try {
    const result = await verifyOtp(phone, otp, name, role);
    await writeAuditLog(null, result.user.id, result.user.role, "USER_LOGIN", `Phone: ${phone}`);
    res.json(result);
  } catch (err) {
    console.warn(`[DURGA AUTH WARN] verifyOtp error for ${phone}: ${err.message}`);
    res.status(400).json({ error: err.message });
  }
});

app.post("/api/v1/auth/set-pin", authenticateToken, async (req, res) => {
  const { pin } = req.body;
  try {
    const result = await setUserPin(req.user.userId, pin);
    await writeAuditLog(null, req.user.userId, req.user.role, "SET_SECURITY_PIN", "PIN updated with PBKDF2 hash");
    res.json(result);
  } catch (err) {
    res.status(400).json({ error: err.message });
  }
});

app.get("/api/v1/users/profile", authenticateToken, async (req, res) => {
  try {
    const user = await getQuery(
      "SELECT id, phone, name, role, emergency_contact_name, emergency_contact_phone, blood_group, home_address, created_at FROM users WHERE id = ?",
      [req.user.userId]
    );
    if (!user) {
      return res.status(404).json({ error: "User not found" });
    }
    res.json({ success: true, user });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

const handleProfileUpdate = async (req, res) => {
  const { name, emergencyContactName, emergencyContactPhone, bloodGroup, homeAddress } = req.body;
  try {
    const user = await getQuery("SELECT * FROM users WHERE id = ?", [req.user.userId]);
    if (!user) {
      return res.status(404).json({ error: "User not found" });
    }
    const updatedName = name !== undefined ? name.toString().trim() : user.name;
    const updatedEcName = emergencyContactName !== undefined ? emergencyContactName.toString().trim() : user.emergency_contact_name;
    const updatedEcPhone = emergencyContactPhone !== undefined ? emergencyContactPhone.toString().trim() : user.emergency_contact_phone;
    const updatedBlood = bloodGroup !== undefined ? bloodGroup.toString().trim() : user.blood_group;
    const updatedAddress = homeAddress !== undefined ? homeAddress.toString().trim() : user.home_address;

    await runQuery(
      `UPDATE users 
       SET name = ?, emergency_contact_name = ?, emergency_contact_phone = ?, blood_group = ?, home_address = ? 
       WHERE id = ?`,
      [updatedName, updatedEcName, updatedEcPhone, updatedBlood, updatedAddress, req.user.userId]
    );

    await writeAuditLog(null, req.user.userId, req.user.role, "UPDATE_PROFILE", `Name: ${updatedName}`);
    res.json({
      success: true,
      message: "Citizen profile updated successfully in DURGA database.",
      user: {
        id: req.user.userId,
        phone: user.phone,
        name: updatedName,
        emergencyContactName: updatedEcName,
        emergencyContactPhone: updatedEcPhone,
        bloodGroup: updatedBlood,
        homeAddress: updatedAddress,
      },
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
};

app.put("/api/v1/users/profile", authenticateToken, handleProfileUpdate);
app.post("/api/v1/users/profile", authenticateToken, handleProfileUpdate);

// ==============================================================================
// 2. Device Registration & Location Tracking
// ==============================================================================

app.post("/api/v1/devices/register", authenticateToken, async (req, res) => {
  const { fcmToken, deviceModel, latitude, longitude, accuracy } = req.body;
  const userId = req.user.userId;
  const deviceId = req.body.deviceId || `DEV-${userId.substring(5)}`;
  const now = Date.now();

  const cleanFcmToken =
    typeof fcmToken === "string" && fcmToken.trim().length > 0 && fcmToken.trim().toLowerCase() !== "null"
      ? fcmToken.trim()
      : null;
  const cleanDeviceModel =
    typeof deviceModel === "string" && deviceModel.trim().length > 0
      ? deviceModel.trim()
      : null;
  const cleanLat =
    latitude !== undefined && latitude !== null && !isNaN(Number(latitude))
      ? Number(latitude)
      : null;
  const cleanLon =
    longitude !== undefined && longitude !== null && !isNaN(Number(longitude))
      ? Number(longitude)
      : null;
  const cleanAcc =
    accuracy !== undefined && accuracy !== null && !isNaN(Number(accuracy))
      ? Number(accuracy)
      : null;
  const locationTime = cleanLat !== null ? now : null;
  const cleanOptedIn =
    req.body.isOptedIn !== undefined
      ? (req.body.isOptedIn === false || req.body.isOptedIn === 0 ? 0 : 1)
      : 1;

  try {
    await runQuery(
      `INSERT INTO devices (id, user_id, fcm_token, device_model, last_lat, last_lon, last_accuracy, last_location_time, is_opted_in, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         user_id = ?,
         fcm_token = COALESCE(?, fcm_token),
         device_model = COALESCE(?, device_model),
         last_lat = COALESCE(?, last_lat),
         last_lon = COALESCE(?, last_lon),
         last_accuracy = COALESCE(?, last_accuracy),
         last_location_time = COALESCE(?, last_location_time),
         is_opted_in = COALESCE(?, is_opted_in),
         updated_at = ?`,
      [
        deviceId,
        userId,
        cleanFcmToken,
        cleanDeviceModel,
        cleanLat,
        cleanLon,
        cleanAcc,
        locationTime,
        cleanOptedIn,
        now,
        // On conflict update parameters:
        userId,
        cleanFcmToken,
        cleanDeviceModel,
        cleanLat,
        cleanLon,
        cleanAcc,
        locationTime,
        cleanOptedIn,
        now,
      ]
    );

    res.json({
      success: true,
      deviceId,
      hasToken: Boolean(cleanFcmToken),
      message: "Device registered and location updated.",
    });
  } catch (err) {
    console.error("[DEVICE REG ERROR]", err.message);
    res.status(500).json({ error: err.message });
  }
});

app.post("/api/v1/devices/revoke-token", authenticateToken, async (req, res) => {
  const userId = req.user.userId;
  const { deviceId, fcmToken } = req.body;
  const now = Date.now();
  try {
    if (deviceId) {
      await runQuery(
        "UPDATE devices SET fcm_token = NULL, updated_at = ? WHERE id = ? AND user_id = ?",
        [now, deviceId, userId]
      );
    } else if (fcmToken) {
      await runQuery(
        "UPDATE devices SET fcm_token = NULL, updated_at = ? WHERE fcm_token = ? AND user_id = ?",
        [now, fcmToken, userId]
      );
    } else {
      await runQuery(
        "UPDATE devices SET fcm_token = NULL, updated_at = ? WHERE user_id = ?",
        [now, userId]
      );
    }
    await writeAuditLog(null, userId, req.user.role, "DEVICE_TOKEN_REVOKED", "FCM token revoked");
    res.json({ success: true, message: "FCM push token revoked successfully." });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ==============================================================================
// 3. Incident Lifecycle Endpoints
// ==============================================================================

/**
 * Creates a new emergency incident.
 * Performs Haversine nearest station search, 1km radius recipient queries, and push notification dispatch.
 */
app.post("/api/v1/incidents", authenticateToken, async (req, res) => {
  const {
    latitude,
    longitude,
    locationAccuracy,
    triggerSource = "MANUAL_SOS_BUTTON",
    distressScore = 0.0,
    distressClassification = "Pending",
  } = req.body;

  const incidentId = "INC-" + crypto.randomUUID().substring(0, 8).toUpperCase();
  const userId = req.user.userId;
  const userName = req.user.name || "Citizen";
  const userPhone = req.user.phone || "";
  const now = Date.now();

  try {
    // 0. Deduplicate rapid duplicate incidents (< 15 seconds) from the same user
    const recentIncident = await getQuery(
      `SELECT * FROM incidents
       WHERE user_id = ?
         AND status IN ('ACTIVE', 'REPEAT_ACTIVE', 'ACKNOWLEDGED', 'RESPONDING')
         AND created_at > ?
       ORDER BY created_at DESC LIMIT 1`,
      [userId, now - 15000]
    );

    if (recentIncident) {
      return res.status(200).json({
        success: true,
        incidentId: recentIncident.id,
        status: recentIncident.status,
        nearestPoliceStation: recentIncident.nearest_police_station,
        nearestDistanceKm: recentIncident.nearest_police_station_distance,
        isDuplicate: true,
        message: "Duplicate incident request deduplicated within 15s window.",
      });
    }

    // 1. Calculate nearest verified police station
    const allStations = await allQuery("SELECT * FROM police_stations");
    const { station: nearestStation, distanceKm: nearestDist } = findNearestPoliceStation(
      latitude,
      longitude,
      allStations
    );

    const stationName = nearestStation ? nearestStation.name : "Central Police Station";

    const senderDeviceId = req.body.deviceId || req.headers["x-device-id"] || null;

    // 2. Insert Incident into SQLite
    await runQuery(
      `INSERT INTO incidents (
         id, user_id, user_name, user_phone, status, trigger_source,
         latitude, longitude, location_accuracy, distress_score, distress_classification,
         nearest_police_station, nearest_police_station_distance, trigger_device_id, created_at, updated_at
       ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
      [
        incidentId,
        userId,
        userName,
        userPhone,
        triggerSource,
        latitude || null,
        longitude || null,
        locationAccuracy || null,
        distressScore,
        distressClassification,
        stationName,
        nearestDist,
        senderDeviceId,
        now,
        now,
      ]
    );

    // Record initial location in history
    if (latitude !== undefined && longitude !== undefined) {
      await runQuery(
        `INSERT INTO incident_location_history (incident_id, latitude, longitude, accuracy, timestamp)
         VALUES (?, ?, ?, ?, ?)`,
        [incidentId, latitude, longitude, locationAccuracy || 0, now]
      );
    }

    // 3. Query all registered devices within 1.0 km radius (excluding the victim's triggering device)
    let allDevices;
    if (senderDeviceId) {
      allDevices = await allQuery("SELECT * FROM devices WHERE id != ?", [senderDeviceId]);
    } else {
      allDevices = await allQuery("SELECT * FROM devices WHERE user_id != ?", [userId]);
    }

    let eligibleRecipients = [];
    if (
      latitude !== undefined &&
      latitude !== null &&
      longitude !== undefined &&
      longitude !== null &&
      !isNaN(Number(latitude)) &&
      !isNaN(Number(longitude))
    ) {
      eligibleRecipients = filterDevicesWithinRadius(
        latitude,
        longitude,
        allDevices,
        1.0,
        userId,
        24 * 60 * 60 * 1000,
        senderDeviceId
      );
    }

    // Emergency Proximity Fallback: If devices haven't locked GPS yet or coordinates are acquiring,
    // dispatch push alert to active registered citizen devices with valid tokens so help is never dropped.
    if (eligibleRecipients.length === 0 && allDevices.length > 0) {
      const activeCandidates = allDevices.filter((d) => {
        if (!d.fcm_token || typeof d.fcm_token !== "string" || d.fcm_token.trim().length === 0) return false;
        if (d.is_opted_in === 0 || d.is_opted_in === false) return false;
        if (senderDeviceId) return d.id !== senderDeviceId;
        return String(d.user_id) !== String(userId);
      });

      if (activeCandidates.length > 0) {
        console.log(`[DURGA DISPATCH] GPS-acquiring emergency broadcast: alerting ${activeCandidates.length} registered candidate device(s)`);
        const seenTokens = new Set();
        const seenUsers = new Set();
        for (const cand of activeCandidates) {
          if (seenTokens.has(cand.fcm_token)) continue;
          if (cand.user_id && seenUsers.has(String(cand.user_id))) continue;
          seenTokens.add(cand.fcm_token);
          if (cand.user_id) seenUsers.add(String(cand.user_id));
          eligibleRecipients.push({
            ...cand,
            distanceKm: 0.1,
            locationPending: true,
          });
        }
      }
    }

    // 4. Dispatch Push Notifications
    const incidentObj = {
      id: incidentId,
      user_name: userName,
      latitude,
      longitude,
      distress_score: distressScore,
      status: "ACTIVE",
      nearest_police_station: stationName,
    };
    const pushResult = await dispatchProximityPushNotifications(
      eligibleRecipients,
      incidentObj,
      async (invalidToken) => {
        await runQuery("UPDATE devices SET fcm_token = NULL WHERE fcm_token = ?", [invalidToken]);
      }
    );

    // 5. Audit Log Entry
    await writeAuditLog(
      incidentId,
      userId,
      req.user.role,
      "INCIDENT_CREATED",
      `Trigger: ${triggerSource}, Coords: [${latitude}, ${longitude}], Radius targets: ${eligibleRecipients.length}`
    );

    // 6. Broadcast to Responder Web Portal via SSE
    broadcastEvent("incident_created", {
      incidentId,
      userId,
      userName,
      userPhone,
      latitude,
      longitude,
      locationAccuracy,
      distressScore,
      distressClassification,
      status: "ACTIVE",
      nearestStation: stationName,
      nearestDist,
      recipientsNotified: eligibleRecipients.length,
      createdAt: now,
    });

    res.status(201).json({
      success: true,
      incidentId,
      status: "ACTIVE",
      nearestPoliceStation: stationName,
      nearestDistanceKm: nearestDist,
      recipientsWithin1Km: eligibleRecipients.length,
      pushDispatchStatus: pushResult.status,
      pushResults: pushResult.results,
    });
  } catch (err) {
    console.error("[CREATE INCIDENT ERROR]", err);
    res.status(500).json({ error: err.message });
  }
});

// Persistent rate limiting parameters for PIN cancellation attempts (persisted in SQLite pin_lockouts table)
const MAX_PIN_FAILURES = 3;
const PIN_LOCKOUT_MS = 2 * 60 * 1000; // 2 minutes lockout

/**
 * Repeat SOS Trigger:
 * Deduplicates or escalates priority during an ongoing incident without blocking citizen cancellation.
 * Preserves the unified secret-code cancellation policy.
 */
app.post("/api/v1/incidents/:id/repeat-sos", authenticateToken, async (req, res) => {
  const incidentId = req.params.id;
  const now = Date.now();

  try {
    const incident = await getQuery("SELECT * FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) {
      return res.status(404).json({ error: "Incident not found." });
    }

    if (incident.user_id !== req.user.userId && req.user.role !== "RESPONDER") {
      return res.status(403).json({ error: "Forbidden: You are not authorized to trigger repeat SOS for this incident." });
    }

    if (incident.status === "RESOLVED" || incident.status === "CANCELLED") {
      return res.status(400).json({ error: `Cannot trigger repeat SOS on a ${incident.status} incident.` });
    }

    // Refresh updated timestamp and record priority re-notification in audit trail
    await runQuery(
      `UPDATE incidents SET updated_at = ? WHERE id = ?`,
      [now, incidentId]
    );

    await writeAuditLog(
      incidentId,
      req.user.userId,
      req.user.role,
      "REPEAT_SOS_SIGNAL",
      "Repeat SOS triggered during active incident. Priority escalated; re-notification broadcast to responders."
    );

    // Broadcast escalation to Responder Portal
    broadcastEvent("incident_escalated", {
      incidentId,
      status: incident.status,
      notes: "Repeat SOS signal received. Responders re-notified.",
      updatedAt: now,
    });

    res.json({
      success: true,
      incidentId,
      status: incident.status,
      message: "Repeat SOS signal registered and broadcast to responders. Unified secret-code cancellation remains active.",
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Cancels an active incident with secret-code (PIN) verification.
 * Applies uniformly across ALL SOS triggers (Manual, Shake, Gesture, Repeat SOS).
 */
app.post("/api/v1/incidents/:id/cancel", authenticateToken, async (req, res) => {
  const incidentId = req.params.id;
  const { pin } = req.body;
  const userId = req.user.userId;
  const now = Date.now();

  try {
    const incident = await getQuery("SELECT * FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) {
      return res.status(404).json({ error: "Incident not found." });
    }

    // Ownership check: Only the incident creator (or an authorized responder) can cancel
    if (incident.user_id !== userId && req.user.role !== "RESPONDER") {
      return res.status(403).json({ error: "Forbidden: You cannot cancel an incident that does not belong to you." });
    }

    const cancellableStates = ["ACTIVE", "REPEAT_ACTIVE", "ACKNOWLEDGED", "RESPONDING"];
    if (!cancellableStates.includes(incident.status)) {
      return res.status(400).json({ error: `Cannot cancel incident in status: ${incident.status}` });
    }

    // Check persistent rate-limiting for failed PIN attempts from SQLite pin_lockouts table
    let lockout = await getQuery("SELECT * FROM pin_lockouts WHERE user_id = ?", [userId]);
    if (lockout && lockout.locked_until > now) {
      const waitSec = Math.ceil((lockout.locked_until - now) / 1000);
      return res.status(429).json({
        error: `Cancellation temporarily locked due to repeated incorrect PIN entries. Try again in ${waitSec}s.`,
      });
    }

    // Verify secret code (PIN) using PBKDF2 with timing-safe comparison
    const isPinValid = await verifyUserPin(userId, pin);
    if (!isPinValid) {
      const currentFailures = (lockout ? lockout.failure_count : 0) + 1;
      const remainingAttempts = Math.max(0, MAX_PIN_FAILURES - currentFailures);

      if (currentFailures >= MAX_PIN_FAILURES) {
        const lockedUntil = now + PIN_LOCKOUT_MS;
        await runQuery(
          `INSERT INTO pin_lockouts (user_id, failure_count, locked_until, updated_at)
           VALUES (?, 0, ?, ?)
           ON CONFLICT(user_id) DO UPDATE SET
             failure_count = 0,
             locked_until = ?,
             updated_at = ?`,
          [userId, lockedUntil, now, lockedUntil, now]
        );

        await writeAuditLog(
          incidentId,
          userId,
          req.user.role,
          "PIN_VERIFICATION_LOCKED",
          `Exceeded maximum incorrect PIN attempts (${MAX_PIN_FAILURES}). Locked for 2 minutes across server reboots.`
        );

        return res.status(429).json({
          error: "Too many incorrect PIN attempts. Cancellation temporarily locked for 2 minutes.",
          remainingAttempts: 0,
        });
      }

      await runQuery(
        `INSERT INTO pin_lockouts (user_id, failure_count, locked_until, updated_at)
         VALUES (?, ?, 0, ?)
         ON CONFLICT(user_id) DO UPDATE SET
           failure_count = ?,
           locked_until = 0,
           updated_at = ?`,
        [userId, currentFailures, now, currentFailures, now]
      );

      await writeAuditLog(
        incidentId,
        userId,
        req.user.role,
        "PIN_VERIFICATION_FAILED",
        `Incorrect cancellation PIN entered. Attempts remaining: ${remainingAttempts}`
      );

      return res.status(401).json({
        error: "Incorrect PIN entered.",
        remainingAttempts,
      });
    }

    // Valid PIN verified -> clear persistent lockout record
    await runQuery("DELETE FROM pin_lockouts WHERE user_id = ?", [userId]);

    // Update status to CANCELLED
    await runQuery(
      `UPDATE incidents SET status = 'CANCELLED', updated_at = ? WHERE id = ?`,
      [now, incidentId]
    );

    await writeAuditLog(
      incidentId,
      userId,
      req.user.role,
      "INCIDENT_CANCELLED",
      "Cancelled by user via verified secret code (PIN). Unified cancellation policy applied."
    );

    broadcastEvent("incident_cancelled", {
      incidentId,
      status: "CANCELLED",
      cancelledAt: now,
    });

    res.json({
      success: true,
      incidentId,
      status: "CANCELLED",
      message: "Emergency incident cancelled successfully.",
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Live GPS coordinate streaming endpoint.
 */
app.patch("/api/v1/incidents/:id/location", authenticateToken, async (req, res) => {
  const incidentId = req.params.id;
  const { latitude, longitude, accuracy } = req.body;
  const now = Date.now();

  if (latitude === undefined || longitude === undefined) {
    return res.status(400).json({ error: "Latitude and longitude are required." });
  }

  try {
    const incident = await getQuery("SELECT user_id, status FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) {
      return res.status(404).json({ error: "Incident not found." });
    }

    if (incident.user_id !== req.user.userId && req.user.role !== "RESPONDER") {
      return res.status(403).json({ error: "Forbidden: You are not authorized to update location for this incident." });
    }

    // Location streaming is only permitted during active emergency states
    if (incident.status === "RESOLVED" || incident.status === "CANCELLED") {
      return res.status(403).json({
        error: "Location sharing expired: Incident has already been resolved or cancelled.",
      });
    }

    await runQuery(
      `UPDATE incidents SET latitude = ?, longitude = ?, location_accuracy = ?, updated_at = ? WHERE id = ?`,
      [latitude, longitude, accuracy || null, now, incidentId]
    );

    await runQuery(
      `INSERT INTO incident_location_history (incident_id, latitude, longitude, accuracy, timestamp)
       VALUES (?, ?, ?, ?, ?)`,
      [incidentId, latitude, longitude, accuracy || 0, now]
    );

    broadcastEvent("location_updated", {
      incidentId,
      latitude,
      longitude,
      accuracy,
      timestamp: now,
    });

    res.json({ success: true, incidentId, latitude, longitude, updatedAt: now });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Audio evidence upload endpoint.
 */
app.post("/api/v1/incidents/:id/audio", authenticateToken, upload.single("audio"), async (req, res) => {
  const incidentId = req.params.id;
  if (!req.file) {
    return res.status(400).json({ error: "No audio file uploaded." });
  }

  try {
    const incident = await getQuery("SELECT user_id, status FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) {
      if (req.file && fs.existsSync(req.file.path)) {
        try { fs.unlinkSync(req.file.path); } catch (e) {}
      }
      return res.status(404).json({ error: "Incident not found." });
    }

    if (incident.user_id !== req.user.userId && req.user.role !== "RESPONDER") {
      if (req.file && fs.existsSync(req.file.path)) {
        try { fs.unlinkSync(req.file.path); } catch (e) {}
      }
      return res.status(403).json({ error: "Forbidden: You are not authorized to upload audio for this incident." });
    }

    const relativePath = path.relative(__dirname, req.file.path);
    await runQuery(`UPDATE incidents SET audio_file_path = ?, updated_at = ? WHERE id = ?`, [
      relativePath,
      Date.now(),
      incidentId,
    ]);

    await writeAuditLog(
      incidentId,
      req.user.userId,
      req.user.role,
      "AUDIO_UPLOADED",
      `Saved audio sample: ${req.file.filename} (${req.file.size} bytes)`
    );

    broadcastEvent("audio_ready", {
      incidentId,
      audioUrl: `/api/v1/incidents/${incidentId}/audio`,
    });

    res.json({
      success: true,
      incidentId,
      audioUrl: `/api/v1/incidents/${incidentId}/audio`,
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Protected audio download/streaming endpoint.
 */
app.get("/api/v1/incidents/:id/audio", async (req, res) => {
  const incidentId = req.params.id;

  // Accept token from Authorization header or URL query parameter (for HTML5 audio elements)
  let token = null;
  const authHeader = req.headers.authorization;
  if (authHeader && authHeader.startsWith("Bearer ")) {
    token = authHeader.substring(7);
  } else if (req.query.token) {
    token = req.query.token;
  }

  if (!token) {
    return res.status(401).json({ error: "Authentication required to access incident audio." });
  }

  try {
    let user;
    try {
      user = jwt.verify(token, JWT_SECRET);
    } catch (e) {
      return res.status(401).json({ error: "Invalid or expired token." });
    }

    const incident = await getQuery("SELECT user_id, audio_file_path, status FROM incidents WHERE id = ?", [incidentId]);
    if (!incident || !incident.audio_file_path) {
      return res.status(404).json({ error: "Audio evidence not found." });
    }

    if (incident.user_id !== user.userId && user.role !== "RESPONDER") {
      return res.status(403).json({ error: "Forbidden: You do not have permission to access this incident's audio." });
    }

    const fullPath = path.join(__dirname, incident.audio_file_path);
    if (!fs.existsSync(fullPath)) {
      return res.status(404).json({ error: "Audio file missing on storage." });
    }

    res.setHeader("Content-Type", "audio/wav");
    fs.createReadStream(fullPath).pipe(res);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// ==============================================================================
// 4. Responder Command Portal Endpoints
// ==============================================================================

app.get(["/api/v1/ping", "/api/v1/health"], async (req, res) => {
  let dbHealthy = false;
  try {
    const pingDb = await getQuery("SELECT 1 AS alive");
    dbHealthy = pingDb && pingDb.alive === 1;
  } catch (e) {
    dbHealthy = false;
  }

  res.json({
    success: true,
    status: dbHealthy ? "HEALTHY" : "DEGRADED",
    message: "DURGA Central Emergency Backend Online",
    database: dbHealthy ? "CONNECTED_SQLITE_WAL" : "DISCONNECTED",
    fcmConfigured: isFcmConfigured(),
    authMode: process.env.DURGA_AUTH_MODE || "DEVELOPMENT",
    activeSseStreams: sseClients.size,
    uptimeSeconds: Math.floor(process.uptime()),
    timestamp: Date.now(),
  });
});

// In-memory rate limiting map for test notifications (userId -> timestamp)
const testNotificationRateLimits = new Map();

/**
 * Controlled Internet Test Notification Endpoint.
 * Authenticates caller, enforces rate limiting, resolves eligible recipient,
 * and submits genuine FCM message.
 */
app.post("/api/v1/notifications/test", authenticateToken, async (req, res) => {
  const senderUserId = req.user.userId;
  const now = Date.now();

  // Rate-limiting: 1 send every 10 seconds per user
  const lastSent = testNotificationRateLimits.get(senderUserId) || 0;
  if (now - lastSent < 10000) {
    const waitSec = Math.ceil((10000 - (now - lastSent)) / 1000);
    return res.status(429).json({
      error: `Rate limited: Please wait ${waitSec} seconds before sending another test push.`,
    });
  }

  const { targetUserId, targetPhone, title, body } = req.body;

  try {
    let targetDevice = null;

    if (targetPhone) {
      const cleanPhone = targetPhone.trim();
      const targetUser = await getQuery(
        "SELECT id FROM users WHERE phone = ? OR phone LIKE ?",
        [cleanPhone, `%${cleanPhone.slice(-10)}`]
      );
      if (targetUser) {
        targetDevice = await getQuery(
          "SELECT * FROM devices WHERE user_id = ? AND fcm_token IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
          [targetUser.id]
        );
      }
    } else if (targetUserId) {
      targetDevice = await getQuery(
        "SELECT * FROM devices WHERE user_id = ? AND fcm_token IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
        [targetUserId]
      );
    } else {
      // Auto-resolve to any other active registered device in the database
      targetDevice = await getQuery(
        "SELECT * FROM devices WHERE user_id != ? AND fcm_token IS NOT NULL ORDER BY updated_at DESC LIMIT 1",
        [senderUserId]
      );
    }

    if (!targetDevice) {
      return res.status(404).json({
        error: "No eligible recipient device with active push token found. Ensure target phone is logged in.",
      });
    }

    testNotificationRateLimits.set(senderUserId, now);

    const result = await sendTestPushNotification(
      req.user,
      targetDevice,
      title,
      body,
      async (invalidToken) => {
        await runQuery("UPDATE devices SET fcm_token = NULL WHERE fcm_token = ?", [invalidToken]);
      }
    );

    await writeAuditLog(
      null,
      senderUserId,
      req.user.role,
      "TEST_NOTIFICATION_SENT",
      `Recipient: ${targetDevice.user_id}, Status: ${result.status}`
    );

    res.json(result);
  } catch (err) {
    console.error("[TEST NOTIFICATION ERROR]", err);
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/v1/notifications/status", (req, res) => {
  res.json({
    fcmConfigured: isFcmConfigured(),
    serviceAccountPath: getServiceAccountPath(),
    provider: "FCM_HTTP_V1",
    mode: isFcmConfigured() ? "PRODUCTION_FCM_ACTIVE" : "DEVELOPMENT_AUDIT_MODE",
  });
});

app.get("/api/v1/incidents/active", authenticateToken, requireRole("RESPONDER"), async (req, res) => {
  try {
    const incidents = await allQuery(`
      SELECT * FROM incidents
      WHERE status IN ('ACTIVE', 'REPEAT_ACTIVE', 'ACKNOWLEDGED', 'RESPONDING')
      ORDER BY updated_at DESC
    `);
    res.json(incidents);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Returns active incidents within the specified radius (default 1.0 km)
 * for a caller device, excluding the victim's own incidents.
 * Sanitizes citizen identity and phone number for nearby citizen privacy.
 */
app.get("/api/v1/incidents/proximity", authenticateToken, async (req, res) => {
  const { latitude, longitude, radius = 1.0 } = req.query;
  const callerDeviceId = req.query.excludeDeviceId || req.headers["x-device-id"] || null;
  const callerLat = latitude ? parseFloat(latitude) : null;
  const callerLon = longitude ? parseFloat(longitude) : null;
  const radiusKm = parseFloat(radius);
  const callerUserId = req.user.userId;

  try {
    // 1. Strict 15-minute Time-To-Live (TTL) for active proximity alerts
    // Any incident created more than 15 minutes ago is STALE and MUST NEVER trigger proximity sirens
    const activeCutoffTime = Date.now() - (15 * 60 * 1000);

    // 2. Strict caller exclusion:
    // ALWAYS exclude caller's own incidents by user_id to prevent self-proximity echo alarms.
    let sql = `
      SELECT * FROM incidents
      WHERE status IN ('ACTIVE', 'REPEAT_ACTIVE', 'ACKNOWLEDGED', 'RESPONDING')
        AND created_at >= ?
        AND user_id != ?
    `;
    const params = [activeCutoffTime, callerUserId];

    if (callerDeviceId) {
      sql += ` AND (trigger_device_id IS NULL OR trigger_device_id != ?)`;
      params.push(callerDeviceId);
    }
    sql += ` ORDER BY updated_at DESC`;

    const incidents = await allQuery(sql, params);

    const eligible = [];
    for (const inc of incidents) {
      // 3. Proximity requires valid coordinates on both caller and incident
      // Never synthesize fake proximity when GPS is unavailable
      if (callerLat === null || callerLon === null || inc.latitude === null || inc.longitude === null) {
        continue;
      }

      const distanceKm = calculateHaversineDistanceKm(callerLat, callerLon, inc.latitude, inc.longitude);
      if (distanceKm <= radiusKm) {
        // Privacy protection: Sanitize sensitive personal info for nearby citizens
        eligible.push({
          id: inc.id,
          status: inc.status,
          latitude: inc.latitude,
          longitude: inc.longitude,
          location_accuracy: inc.location_accuracy,
          distress_score: inc.distress_score,
          distress_classification: inc.distress_classification,
          trigger_source: inc.trigger_source,
          nearest_police_station: inc.nearest_police_station,
          created_at: inc.created_at,
          updated_at: inc.updated_at,
          distanceKm: parseFloat(distanceKm.toFixed(3)),
        });
      }
    }

    res.json(eligible);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/v1/incidents/:id", authenticateToken, async (req, res) => {
  const incidentId = req.params.id;
  try {
    const incident = await getQuery("SELECT * FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) return res.status(404).json({ error: "Incident not found." });

    // Enforce authorization: only the incident owner or authorized responder can view full incident details
    if (incident.user_id !== req.user.userId && req.user.role !== "RESPONDER") {
      return res.status(403).json({ error: "Forbidden: You are not authorized to view this incident record." });
    }

    const history = await allQuery(
      "SELECT * FROM incident_location_history WHERE incident_id = ? ORDER BY timestamp ASC",
      [incidentId]
    );

    const auditTrail = await allQuery(
      "SELECT * FROM audit_logs WHERE incident_id = ? ORDER BY timestamp ASC",
      [incidentId]
    );

    res.json({
      ...incident,
      locationHistory: history,
      auditTrail,
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post("/api/v1/incidents/:id/acknowledge", authenticateToken, requireRole("RESPONDER"), async (req, res) => {
  const incidentId = req.params.id;
  const responderId = req.user.userId;
  const now = Date.now();

  try {
    await runQuery(`UPDATE incidents SET status = 'ACKNOWLEDGED', updated_at = ? WHERE id = ?`, [now, incidentId]);

    await writeAuditLog(incidentId, responderId, "RESPONDER", "RESPONDER_ACKNOWLEDGED", "Incident acknowledged by dispatcher");

    broadcastEvent("incident_acknowledged", { incidentId, responderId, acknowledgedAt: now });
    res.json({ success: true, incidentId, status: "ACKNOWLEDGED" });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.post("/api/v1/incidents/:id/resolve", authenticateToken, requireRole("RESPONDER"), async (req, res) => {
  const incidentId = req.params.id;
  const responderId = req.user.userId;
  const { notes = "Incident verified safe and resolved by authorized responder." } = req.body;
  const now = Date.now();

  try {
    await runQuery(
      `UPDATE incidents SET
         status = 'RESOLVED',
         resolved_by_responder_id = ?,
         resolution_notes = ?,
         resolved_at = ?,
         updated_at = ?
       WHERE id = ?`,
      [responderId, notes, now, now, incidentId]
    );

    await writeAuditLog(
      incidentId,
      responderId,
      "RESPONDER",
      "INCIDENT_RESOLVED",
      `Resolution notes: ${notes}. Location tokens invalidated.`
    );

    broadcastEvent("incident_resolved", {
      incidentId,
      responderId,
      notes,
      resolvedAt: now,
      status: "RESOLVED",
    });

    res.json({ success: true, incidentId, status: "RESOLVED", message: "Incident resolved. Telemetry stream stopped." });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/v1/police-stations", async (req, res) => {
  try {
    const stations = await allQuery("SELECT * FROM police_stations ORDER BY name ASC");
    res.json(stations);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

// SSE Telemetry Stream Endpoint
app.get("/api/v1/events/stream", (req, res) => {
  res.setHeader("Content-Type", "text/event-stream");
  res.setHeader("Cache-Control", "no-cache");
  res.setHeader("Connection", "keep-alive");
  res.flushHeaders();

  sseClients.add(res);
  console.log(`[DURGA SSE] Client connected. Total active streams: ${sseClients.size}`);

  req.on("close", () => {
    sseClients.delete(res);
    console.log(`[DURGA SSE] Client disconnected. Total active streams: ${sseClients.size}`);
  });
});

// Start Server if run directly
let server = null;
if (require.main === module) {
  server = app.listen(PORT, () => {
    console.log(`=================================================================`);
    console.log(`  DURGA Central Emergency Response Backend is LIVE`);
    console.log(`  Port: ${PORT}`);
    console.log(`  API Base: http://localhost:${PORT}/api/v1`);
    console.log(`  Responder Portal: http://localhost:${PORT}/portal`);
    console.log(`=================================================================`);
  });
}

module.exports = { app, server };

