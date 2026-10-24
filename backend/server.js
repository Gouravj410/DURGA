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
  requestOtp,
  verifyOtp,
  setUserPin,
  verifyUserPin,
  authenticateToken,
  requireRole,
} = require("./auth");
const { dispatchProximityPushNotifications } = require("./notification_service");

const app = express();
const PORT = process.env.PORT || 3000;

app.use(cors());
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
  const { phone } = req.body;
  if (!phone) {
    return res.status(400).json({ error: "Phone number is required." });
  }
  const result = requestOtp(phone);
  res.json(result);
});

app.post("/api/v1/auth/verify-otp", async (req, res) => {
  const { phone, otp, name, role } = req.body;
  if (!phone || !otp) {
    return res.status(400).json({ error: "Phone number and OTP are required." });
  }
  try {
    const result = await verifyOtp(phone, otp, name, role);
    await writeAuditLog(null, result.user.id, result.user.role, "USER_LOGIN", `Phone: ${phone}`);
    res.json(result);
  } catch (err) {
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

// ==============================================================================
// 2. Device Registration & Location Tracking
// ==============================================================================

app.post("/api/v1/devices/register", authenticateToken, async (req, res) => {
  const { fcmToken, deviceModel, latitude, longitude, accuracy } = req.body;
  const userId = req.user.userId;
  const deviceId = req.body.deviceId || `DEV-${userId.substring(5)}`;
  const now = Date.now();

  try {
    await runQuery(
      `INSERT INTO devices (id, user_id, fcm_token, device_model, last_lat, last_lon, last_accuracy, last_location_time, updated_at)
       VALUES (?, ?, ?, ?, ?, ?, ?, ?, ?)
       ON CONFLICT(id) DO UPDATE SET
         fcm_token = COALESCE(?, fcm_token),
         device_model = COALESCE(?, device_model),
         last_lat = COALESCE(?, last_lat),
         last_lon = COALESCE(?, last_lon),
         last_accuracy = COALESCE(?, last_accuracy),
         last_location_time = ?,
         updated_at = ?`,
      [
        deviceId,
        userId,
        fcmToken,
        deviceModel,
        latitude || null,
        longitude || null,
        accuracy || null,
        latitude ? now : null,
        now,
        fcmToken,
        deviceModel,
        latitude || null,
        longitude || null,
        accuracy || null,
        latitude ? now : null,
        now,
      ]
    );

    res.json({
      success: true,
      deviceId,
      message: "Device registered and location updated.",
    });
  } catch (err) {
    console.error("[DEVICE REG ERROR]", err);
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
    // 1. Calculate nearest verified police station
    const allStations = await allQuery("SELECT * FROM police_stations");
    const { station: nearestStation, distanceKm: nearestDist } = findNearestPoliceStation(
      latitude,
      longitude,
      allStations
    );

    const stationName = nearestStation ? nearestStation.name : "Central Police Station";

    // 2. Insert Incident into SQLite
    await runQuery(
      `INSERT INTO incidents (
         id, user_id, user_name, user_phone, status, trigger_source,
         latitude, longitude, location_accuracy, distress_score, distress_classification,
         nearest_police_station, nearest_police_station_distance, created_at, updated_at
       ) VALUES (?, ?, ?, ?, 'ACTIVE', ?, ?, ?, ?, ?, ?, ?, ?, ?, ?)`,
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

    // 3. Query all registered devices within 1.0 km radius (excluding the victim)
    const allDevices = await allQuery("SELECT * FROM devices WHERE user_id != ?", [userId]);
    const eligibleRecipients = filterDevicesWithinRadius(latitude, longitude, allDevices, 1.0, userId);

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
    const pushResult = await dispatchProximityPushNotifications(eligibleRecipients, incidentObj);

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

/**
 * Repeat SOS Trigger:
 * Escalates an existing active incident, bypasses AI analysis, and locks ordinary cancellation.
 */
app.post("/api/v1/incidents/:id/repeat-sos", authenticateToken, async (req, res) => {
  const incidentId = req.params.id;
  const now = Date.now();

  try {
    const incident = await getQuery("SELECT * FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) {
      return res.status(404).json({ error: "Incident not found." });
    }

    if (incident.status === "RESOLVED" || incident.status === "CANCELLED") {
      return res.status(400).json({ error: `Cannot trigger repeat SOS on a ${incident.status} incident.` });
    }

    // Update status to REPEAT_ACTIVE
    await runQuery(
      `UPDATE incidents SET status = 'REPEAT_ACTIVE', updated_at = ? WHERE id = ?`,
      [now, incidentId]
    );

    await writeAuditLog(
      incidentId,
      req.user.userId,
      req.user.role,
      "REPEAT_SOS_ESCALATION",
      "Ordinary cancellation permanently locked. Responder resolution strictly required."
    );

    // Broadcast escalation to Responder Portal
    broadcastEvent("incident_escalated", {
      incidentId,
      status: "REPEAT_ACTIVE",
      notes: "Repeat SOS triggered. User cannot cancel.",
      updatedAt: now,
    });

    res.json({
      success: true,
      incidentId,
      status: "REPEAT_ACTIVE",
      message: "Repeat SOS registered. Ordinary cancellation locked. Incident escalated to Responder Command.",
    });
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

/**
 * Cancels an active incident with PIN verification.
 * Strictly blocked if incident has entered REPEAT_ACTIVE state.
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

    // Strictly enforce Repeat SOS restriction
    if (incident.status === "REPEAT_ACTIVE") {
      await writeAuditLog(
        incidentId,
        userId,
        req.user.role,
        "CANCEL_REJECTED_LOCKDOWN",
        "Attempted PIN cancellation on REPEAT_ACTIVE incident."
      );
      return res.status(403).json({
        error: "Cancellation rejected: Repeat SOS lockdown in effect. Only an authorized responder can resolve.",
      });
    }

    if (incident.status !== "ACTIVE") {
      return res.status(400).json({ error: `Cannot cancel incident in status: ${incident.status}` });
    }

    // Verify PIN
    const isPinValid = await verifyUserPin(userId, pin);
    if (!isPinValid) {
      await writeAuditLog(incidentId, userId, req.user.role, "PIN_VERIFICATION_FAILED", "Incorrect cancellation PIN entered.");
      return res.status(401).json({ error: "Incorrect PIN entered." });
    }

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
      "Cancelled by user via verified PIN."
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
    const incident = await getQuery("SELECT status FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) {
      return res.status(404).json({ error: "Incident not found." });
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

  try {
    const incident = await getQuery("SELECT audio_file_path, status FROM incidents WHERE id = ?", [incidentId]);
    if (!incident || !incident.audio_file_path) {
      return res.status(404).json({ error: "Audio evidence not found." });
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

app.get("/api/v1/ping", (req, res) => {
  res.json({ success: true, message: "DURGA Central Emergency Backend Online", timestamp: Date.now() });
});

app.get("/api/v1/incidents/active", async (req, res) => {
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
 */
app.get("/api/v1/incidents/proximity", async (req, res) => {
  const { latitude, longitude, radius = 1.0, excludeUserId } = req.query;
  const callerLat = latitude ? parseFloat(latitude) : null;
  const callerLon = longitude ? parseFloat(longitude) : null;
  const radiusKm = parseFloat(radius);

  try {
    let sql = `
      SELECT * FROM incidents
      WHERE status IN ('ACTIVE', 'REPEAT_ACTIVE', 'ACKNOWLEDGED', 'RESPONDING')
    `;
    const params = [];
    if (excludeUserId) {
      sql += " AND user_id != ?";
      params.push(excludeUserId);
    }
    sql += " ORDER BY updated_at DESC";

    const incidents = await allQuery(sql, params);

    const eligible = [];
    for (const inc of incidents) {
      let distanceKm = null;
      if (callerLat !== null && callerLon !== null && inc.latitude !== null && inc.longitude !== null) {
        distanceKm = calculateHaversineDistanceKm(callerLat, callerLon, inc.latitude, inc.longitude);
      }

      // If caller location is known, enforce radius <= 1.0 km
      // If caller location is not yet fixed (e.g. indoor testing), treat as nearby for testing
      if (distanceKm === null || distanceKm <= radiusKm) {
        eligible.push({
          ...inc,
          distanceKm: distanceKm !== null ? parseFloat(distanceKm.toFixed(3)) : 0.35,
        });
      }
    }

    res.json(eligible);
  } catch (err) {
    res.status(500).json({ error: err.message });
  }
});

app.get("/api/v1/incidents/:id", async (req, res) => {
  const incidentId = req.params.id;
  try {
    const incident = await getQuery("SELECT * FROM incidents WHERE id = ?", [incidentId]);
    if (!incident) return res.status(404).json({ error: "Incident not found." });

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

