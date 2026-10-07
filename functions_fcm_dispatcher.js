/**
 * DURGA - Firebase Cloud Functions Backend (Phase 13: Push Notification Dispatcher)
 * Automatically triggers when an emergency event is published to Firestore.
 * Dispatches high-priority Firebase Cloud Messaging (FCM) notifications to Guardians and Responders.
 */

const functions = require("firebase-functions");
const admin = require("firebase-admin");
admin.initializeApp();

exports.onEmergencyEventCreated = functions.firestore
  .document("emergencyEvents/{eventId}")
  .onCreate(async (snap, context) => {
    const event = snap.data();
    const eventId = context.params.eventId;

    console.log(`[DURGA] Processing emergency notification for Event ID: ${eventId}`);

    // High-priority FCM payload
    const payload = {
      notification: {
        title: "🚨 DURGA EMERGENCY ALERT",
        body: `Distress detected from ${event.userName || "Citizen"} (${event.emergencyLevel || "HIGH"}). Tap to inspect telemetry & dispatch unit.`,
      },
      data: {
        eventId: eventId,
        userName: String(event.userName || ""),
        emergencyLevel: String(event.emergencyLevel || "HIGH"),
        distressScore: String(event.distressScore || "0.0"),
        latitude: String(event.latitude || "0.0"),
        longitude: String(event.longitude || "0.0"),
        audioUrl: String(event.audioUrl || ""),
      },
      android: {
        priority: "high",
        notification: {
          channelId: "durga_emergency_alerts",
          sound: "default",
          priority: "high",
          defaultVibrateTimings: true,
        },
      },
      topic: "durga_responders", // Broadcast to connected responders & guardians
    };

    try {
      const response = await admin.messaging().send(payload);
      console.log(`[DURGA] FCM notification successfully dispatched: ${response}`);
      return response;
    } catch (error) {
      console.error(`[DURGA] Error sending FCM notification:`, error);
      return null;
    }
  });
