package com.durga.emergency.utils

data class ParsedNotification(
    val eventId: String,
    val isTest: Boolean,
    val type: String,
    val title: String,
    val body: String,
    val latitude: Double?,
    val longitude: Double?,
    val accuracy: Float?,
    val distressScore: Float,
    val classification: String,
    val nearestStation: String,
    val distanceKm: Double?,
    val status: String
)

object NotificationPayloadParser {

    fun parse(
        data: Map<String, String>,
        notificationTitle: String? = null,
        notificationBody: String? = null
    ): ParsedNotification {
        val type = data["type"] ?: "DURGA_EMERGENCY"
        val isTest = (type == "DURGA_TEST")
        val rawId = data["eventId"] ?: data["incidentId"]
        val eventId = rawId?.takeIf { it.isNotEmpty() }
            ?: if (isTest) "TEST-FCM" else "INC-UNKNOWN"

        val title = notificationTitle
            ?: data["title"]
            ?: if (isTest) "DURGA Test Notification" else "🚨 DURGA EMERGENCY ALERT"

        val body = notificationBody
            ?: data["body"]
            ?: if (isTest) "Internet notification test received." else "Distress detected. Tap to inspect telemetry."

        val lat = data["latitude"]?.toDoubleOrNull()
        val lon = data["longitude"]?.toDoubleOrNull()
        val acc = data["accuracy"]?.toFloatOrNull()
        val score = data["distressScore"]?.toFloatOrNull() ?: 0.95f
        val classification = data["distressClassification"] ?: "Acoustic Distress Confirmed"
        val station = data["nearestPoliceStation"] ?: "Nearest Police Station"
        val distKm = data["distanceKm"]?.toDoubleOrNull()
        val status = data["status"] ?: "ACTIVE"

        return ParsedNotification(
            eventId = eventId,
            isTest = isTest,
            type = type,
            title = title,
            body = body,
            latitude = lat,
            longitude = lon,
            accuracy = acc,
            distressScore = score,
            classification = classification,
            nearestStation = station,
            distanceKm = distKm,
            status = status
        )
    }
}
