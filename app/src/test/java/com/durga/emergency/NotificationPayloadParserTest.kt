package com.durga.emergency

import com.durga.emergency.utils.NotificationPayloadParser
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Test

class NotificationPayloadParserTest {

    @Test
    fun parse_withIncidentIdOnly_resolvesCorrectly() {
        val payload = mapOf(
            "incidentId" to "INC-98765",
            "type" to "DURGA_EMERGENCY",
            "title" to "Emergency Alert",
            "body" to "Scream detected nearby"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("INC-98765", parsed.eventId)
        assertEquals("DURGA_EMERGENCY", parsed.type)
        assertFalse(parsed.isTest)
        assertEquals("Emergency Alert", parsed.title)
        assertEquals("Scream detected nearby", parsed.body)
    }

    @Test
    fun parse_withEventIdOnly_resolvesCorrectly() {
        val payload = mapOf(
            "eventId" to "INC-12345",
            "type" to "DURGA_EMERGENCY"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("INC-12345", parsed.eventId)
        assertFalse(parsed.isTest)
    }

    @Test
    fun parse_withBothIncidentIdAndEventId_prefersEventId() {
        val payload = mapOf(
            "eventId" to "INC-EVENT-1",
            "incidentId" to "INC-EVENT-1",
            "type" to "DURGA_EMERGENCY"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("INC-EVENT-1", parsed.eventId)
    }

    @Test
    fun parse_missingIdentifierEmergency_fallsBackSafely() {
        val payload = mapOf(
            "type" to "DURGA_EMERGENCY"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("INC-UNKNOWN", parsed.eventId)
        assertFalse(parsed.isTest)
        assertNotNull(parsed.title)
        assertNotNull(parsed.body)
    }

    @Test
    fun parse_missingIdentifierTest_fallsBackToTestFcm() {
        val payload = mapOf(
            "type" to "DURGA_TEST"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("TEST-FCM", parsed.eventId)
        assertTrue(parsed.isTest)
    }

    @Test
    fun parse_distressTelemetry_extractsAllMetrics() {
        val payload = mapOf(
            "eventId" to "INC-TELEMETRY-01",
            "incidentId" to "INC-TELEMETRY-01",
            "type" to "DURGA_EMERGENCY",
            "latitude" to "12.9716",
            "longitude" to "77.5946",
            "accuracy" to "12.5",
            "distressScore" to "0.92",
            "distressClassification" to "Scream / High Distress",
            "nearestPoliceStation" to "Cubbon Park Police Station",
            "distanceKm" to "0.45",
            "status" to "ACTIVE"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("INC-TELEMETRY-01", parsed.eventId)
        assertEquals(12.9716, parsed.latitude!!, 0.0001)
        assertEquals(77.5946, parsed.longitude!!, 0.0001)
        assertEquals(12.5f, parsed.accuracy!!, 0.01f)
        assertEquals(0.92f, parsed.distressScore, 0.01f)
        assertEquals("Scream / High Distress", parsed.classification)
        assertEquals("Cubbon Park Police Station", parsed.nearestStation)
        assertEquals(0.45, parsed.distanceKm!!, 0.01)
        assertEquals("ACTIVE", parsed.status)
    }

    @Test
    fun parse_testPayload_identifiesAsTest() {
        val payload = mapOf(
            "eventId" to "TEST-FCM-999",
            "type" to "DURGA_TEST",
            "title" to "DURGA Test Notification",
            "body" to "Testing verified FCM pipeline"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertTrue(parsed.isTest)
        assertEquals("TEST-FCM-999", parsed.eventId)
        assertEquals("DURGA_TEST", parsed.type)
    }

    @Test
    fun parse_backendFieldNames_resolvesFallbacksCorrectly() {
        // Backend notification_service.js emits locationAccuracy and nearestStation
        val payload = mapOf(
            "incidentId" to "INC-BACKEND-01",
            "type" to "DURGA_EMERGENCY",
            "latitude" to "28.6139",
            "longitude" to "77.2090",
            "locationAccuracy" to "15.0",
            "distressScore" to "0.88",
            "classification" to "Scream Detected",
            "nearestStation" to "Connaught Place Police Station",
            "distanceKm" to "0.32",
            "status" to "ACTIVE"
        )

        val parsed = NotificationPayloadParser.parse(payload)

        assertEquals("INC-BACKEND-01", parsed.eventId)
        assertEquals(28.6139, parsed.latitude!!, 0.0001)
        assertEquals(77.2090, parsed.longitude!!, 0.0001)
        assertEquals(15.0f, parsed.accuracy!!, 0.01f)
        assertEquals(0.88f, parsed.distressScore, 0.01f)
        assertEquals("Scream Detected", parsed.classification)
        assertEquals("Connaught Place Police Station", parsed.nearestStation)
        assertEquals(0.32, parsed.distanceKm!!, 0.01)
        assertEquals("ACTIVE", parsed.status)
    }
}
