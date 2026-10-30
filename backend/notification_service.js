/**
 * DURGA Push Notification Service
 * Integrates Firebase Cloud Messaging (FCM) HTTP v1 API
 * Strictly adheres to rule: Never claim delivery without verified provider status.
 */

const fs = require("fs");
const path = require("path");
const crypto = require("crypto");
const { initializeApp, cert, getApps } = require("firebase-admin/app");
const { getMessaging } = require("firebase-admin/messaging");

let fcmInitialized = false;
let messaging = null;

// Look for Firebase Service Account Key (via env var path, raw JSON, or local file)
const SERVICE_ACCOUNT_PATH = process.env.FIREBASE_SERVICE_ACCOUNT_PATH || path.join(__dirname, "serviceAccountKey.json");

try {
  let serviceAccount = null;

  // 1. Resolve credentials from FIREBASE_SERVICE_ACCOUNT_JSON (raw JSON string in env)
  if (process.env.FIREBASE_SERVICE_ACCOUNT_JSON) {
    try {
      serviceAccount = JSON.parse(process.env.FIREBASE_SERVICE_ACCOUNT_JSON);
    } catch (e) {
      console.error("[DURGA NOTIFICATIONS] Failed to parse FIREBASE_SERVICE_ACCOUNT_JSON:", e.message);
    }
  }

  // 2. Resolve credentials from explicit FIREBASE_SERVICE_ACCOUNT_PATH (if set and not yet resolved)
  if (!serviceAccount && process.env.FIREBASE_SERVICE_ACCOUNT_PATH) {
    if (fs.existsSync(process.env.FIREBASE_SERVICE_ACCOUNT_PATH)) {
      try {
        serviceAccount = JSON.parse(fs.readFileSync(process.env.FIREBASE_SERVICE_ACCOUNT_PATH, "utf8"));
      } catch (e) {
        console.error("[DURGA NOTIFICATIONS] Failed to read FIREBASE_SERVICE_ACCOUNT_PATH:", e.message);
      }
    } else {
      console.warn(`[DURGA NOTIFICATIONS] FIREBASE_SERVICE_ACCOUNT_PATH does not exist: ${process.env.FIREBASE_SERVICE_ACCOUNT_PATH}`);
    }
  }

  // 3. Resolve credentials from default local serviceAccountKey.json (if not yet resolved)
  if (!serviceAccount && fs.existsSync(SERVICE_ACCOUNT_PATH)) {
    try {
      serviceAccount = JSON.parse(fs.readFileSync(SERVICE_ACCOUNT_PATH, "utf8"));
    } catch (e) {
      console.error("[DURGA NOTIFICATIONS] Failed to read serviceAccountKey.json:", e.message);
    }
  }

  if (serviceAccount) {
    // Validate required fields without exposing sensitive contents
    if (!serviceAccount.project_id || !serviceAccount.client_email || !serviceAccount.private_key) {
      throw new Error("Invalid service account: missing project_id, client_email, or private_key.");
    }

    const app = getApps().length === 0
      ? initializeApp({ credential: cert(serviceAccount) })
      : getApps()[0];

    messaging = getMessaging(app);
    fcmInitialized = true;
    console.log(`[DURGA NOTIFICATIONS] Firebase Admin SDK initialized successfully for project: ${serviceAccount.project_id}`);
  } else {
    if (process.env.DURGA_REQUIRE_FCM === "true") {
      throw new Error("Production Configuration Error: FCM service account credentials are required.");
    }
    console.log(
      "[DURGA NOTIFICATIONS] No service account key found. Operating in verified development audit mode."
    );
  }
} catch (e) {
  fcmInitialized = false;
  messaging = null;
  if (process.env.DURGA_REQUIRE_FCM === "true") {
    throw e;
  }
  console.warn("[DURGA NOTIFICATIONS] Firebase initialization warning:", e.message);
}

/**
 * Dispatches high-priority emergency push notifications to target device tokens.
 *
 * @param {Array<{fcm_token: string, device_id: string, user_id: string, distanceKm: number}>} targetDevices
 * @param {Object} incident
 */
async function dispatchProximityPushNotifications(targetDevices, incident, onInvalidTokenCallback = null) {
  if (!targetDevices || targetDevices.length === 0) {
    return {
      status: "NO_TARGETS_IN_RADIUS",
      recipientsCount: 0,
      isRealDelivery: Boolean(fcmInitialized),
      results: [],
    };
  }

  const results = [];
  const eventIdentifier = String(incident.id || "");

  for (const device of targetDevices) {
    const payload = {
      title: "DURGA EMERGENCY ALERT",
      body: `Citizen in distress ~${device.distanceKm} km away. Tap to inspect telemetry & view walking route.`,
      data: {
        eventId: eventIdentifier,
        incidentId: eventIdentifier,
        type: "DURGA_EMERGENCY",
        latitude: String(incident.latitude || "0.0"),
        longitude: String(incident.longitude || "0.0"),
        locationAccuracy: String(incident.location_accuracy || "10.0"),
        distressScore: String(incident.distress_score || "0.0"),
        distressClassification: String(incident.distress_classification || "Distress Detected"),
        status: String(incident.status || "ACTIVE"),
        nearestStation: String(incident.nearest_police_station || ""),
        distanceKm: String(device.distanceKm || "0.0"),
        timestamp: String(Date.now()),
      },
    };

    const isMockToken = !device.fcm_token || device.fcm_token.startsWith("mock_");

    if (fcmInitialized && messaging && device.fcm_token && !isMockToken) {
      try {
        const message = {
          token: device.fcm_token,
          notification: {
            title: payload.title,
            body: payload.body,
          },
          data: payload.data,
          android: {
            priority: "high",
            notification: {
              channelId: "durga_emergency_alerts",
              priority: "max",
              defaultSound: true,
              defaultVibrateTimings: true,
              visibility: "public",
            },
          },
        };

        const response = await messaging.send(message);
        results.push({
          deviceId: device.device_id,
          userId: device.user_id,
          token: device.fcm_token.substring(0, 10) + "...",
          status: "FCM_SUBMISSION_ACCEPTED",
          isRealDelivery: true,
          messageId: response,
          distanceKm: device.distanceKm,
        });
        console.log(`[DURGA FCM] Sent alert to user ${device.user_id} (${device.distanceKm} km): ${response}`);
      } catch (err) {
        console.error(`[DURGA FCM] Failed to send to token ${device.fcm_token.substring(0, 10)}:`, err.message);
        if (
          (err.code === "messaging/invalid-registration-token" ||
            err.code === "messaging/registration-token-not-registered") &&
          onInvalidTokenCallback
        ) {
          try {
            await onInvalidTokenCallback(device.fcm_token);
          } catch (cleanErr) {
            console.error("[DURGA FCM] Error cleaning invalid token:", cleanErr.message);
          }
        }
        results.push({
          deviceId: device.device_id,
          userId: device.user_id,
          status: "PROVIDER_REJECTED",
          isRealDelivery: false,
          error: err.message,
          distanceKm: device.distanceKm,
        });
      }
    } else {
      // Development mode without live Firebase service credentials or mock token in test mode
      results.push({
        deviceId: device.device_id,
        userId: device.user_id,
        token: device.fcm_token ? device.fcm_token.substring(0, 10) + "..." : "NONE",
        status: "RECORDED_IN_DISPATCH_QUEUE",
        isRealDelivery: false,
        note: fcmInitialized
          ? "Backend targeted within 1km radius (Mock token detected in test mode)"
          : "Backend accepted & targeted within 1km radius (Development audit mode: Firebase service account not configured)",
        distanceKm: device.distanceKm,
      });
      console.log(
        `[DURGA DISPATCH (LOCAL)] Target within 1km: User ${device.user_id} at ${device.distanceKm} km. (Payload prepared)`
      );
    }
  }

  const hasRealDelivery = results.some((r) => r.isRealDelivery);
  return {
    status: hasRealDelivery ? "FCM_DISPATCHED" : "LOCAL_DISPATCH_LOGGED_MOCK",
    isRealDelivery: hasRealDelivery,
    recipientsCount: results.length,
    results,
  };
}

/**
 * Sends an authenticated test notification to a specific target device.
 *
 * @param {Object} senderUser - Authenticated sender profile from JWT
 * @param {Object} targetDevice - Target device record with fcm_token
 * @param {string} customTitle
 * @param {string} customBody
 * @param {Function} onInvalidTokenCallback - Callback to deactivate dead tokens
 */
async function sendTestPushNotification(senderUser, targetDevice, customTitle, customBody, onInvalidTokenCallback = null) {
  const eventId = "TEST-" + crypto.randomUUID().substring(0, 8).toUpperCase();
  const title = customTitle || "DURGA Test Notification";
  const body = customBody || `Internet notification test received from ${senderUser.name || "Authorized Citizen"}.`;

  const payload = {
    type: "DURGA_TEST",
    eventId,
    incidentId: eventId,
    title,
    body,
    senderId: String(senderUser.userId || senderUser.id || "UNKNOWN"),
    createdAt: String(Date.now()),
    schemaVersion: "1.0",
  };

  const isMockToken = !targetDevice.fcm_token || targetDevice.fcm_token.startsWith("mock_");

  if (fcmInitialized && messaging && targetDevice.fcm_token && !isMockToken) {
    try {
      const message = {
        token: targetDevice.fcm_token,
        notification: {
          title,
          body,
        },
        data: payload,
        android: {
          priority: "high",
          notification: {
            channelId: "durga_emergency_alerts",
            priority: "max",
            defaultSound: true,
            defaultVibrateTimings: true,
          },
        },
      };

      const messageId = await messaging.send(message);
      console.log(`[DURGA FCM TEST] Test push submitted to FCM: ${messageId} (Recipient: ${targetDevice.user_id})`);
      return {
        success: true,
        status: "FCM_SUBMISSION_ACCEPTED",
        isRealDelivery: true,
        messageId,
        recipientUserId: targetDevice.user_id,
        eventId,
        incidentId: eventId,
      };
    } catch (err) {
      console.error(`[DURGA FCM TEST] Error sending to token ${targetDevice.fcm_token.substring(0, 10)}:`, err.message);

      // Check for permanently dead tokens
      if (
        err.code === "messaging/invalid-registration-token" ||
        err.code === "messaging/registration-token-not-registered"
      ) {
        if (onInvalidTokenCallback) {
          try {
            await onInvalidTokenCallback(targetDevice.fcm_token);
          } catch (cleanErr) {
            console.error("[DURGA FCM TEST] Error running token cleanup:", cleanErr.message);
          }
        }
        return {
          success: false,
          status: "TOKEN_UNREGISTERED_OR_INVALID",
          isRealDelivery: false,
          error: "Recipient token is expired or unregistered.",
          recipientUserId: targetDevice.user_id,
          eventId,
          incidentId: eventId,
        };
      }

      return {
        success: false,
        status: "FCM_ERROR",
        isRealDelivery: false,
        error: err.message,
        recipientUserId: targetDevice.user_id,
        eventId,
        incidentId: eventId,
      };
    }
  }

  // Development audit mode or test mock dispatch
  console.log(`[DURGA TEST NOTIFICATION (AUDIT)] Validated test push to ${targetDevice.user_id}. Payload:`, payload);
  return {
    success: true,
    status: "DEVELOPMENT_MOCK_DISPATCH_RECORDED",
    isRealDelivery: false,
    messageId: `dev-msg-${crypto.randomUUID().substring(0, 8)}`,
    recipientUserId: targetDevice.user_id,
    eventId,
    incidentId: eventId,
    note: fcmInitialized
      ? "Push delivery mocked: Mock token detected in automated test."
      : "Push delivery mocked: Firebase Admin credentials not configured on backend. (serviceAccountKey.json required for real FCM transport)",
  };
}

/**
 * Validates and normalizes incoming notification payload data.
 * Guarantees that both eventId and incidentId can be extracted safely.
 */
function parseNotificationPayload(data) {
  if (!data || typeof data !== "object") {
    return {
      eventId: null,
      incidentId: null,
      type: "UNKNOWN",
      isValid: false,
      error: "Malformed or missing notification payload",
    };
  }
  const eventId = data.eventId || data.incidentId || null;
  const incidentId = data.incidentId || data.eventId || null;
  const type = data.type || (eventId && eventId.startsWith("TEST-") ? "DURGA_TEST" : "DURGA_EMERGENCY");

  return {
    eventId,
    incidentId,
    type,
    isValid: Boolean(eventId),
    isTest: type === "DURGA_TEST",
    data,
  };
}

module.exports = {
  dispatchProximityPushNotifications,
  sendTestPushNotification,
  parseNotificationPayload,
  isFcmConfigured: () => fcmInitialized,
  getServiceAccountPath: () => SERVICE_ACCOUNT_PATH,
};
