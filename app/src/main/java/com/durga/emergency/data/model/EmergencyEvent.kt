package com.durga.emergency.data.model

data class EmergencyEvent(
    val eventId: String = "",
    val userId: String = "",
    val userName: String = "Protected Citizen",
    val timestamp: Long = System.currentTimeMillis(),
    val latitude: Double? = null,
    val longitude: Double? = null,
    val locationAccuracy: Float? = null,
    val distressScore: Float = 0.0f,
    val distressClassification: String = "Pending",
    val emergencyLevel: String = "HIGH",
    val audioUrl: String? = null,
    val emergencyContactPhone: String = "",
    val nearestPoliceStationName: String = "",
    val nearestPoliceStationDistanceKm: Double? = null,
    val status: String = "ACTIVE",
    val createdAt: Long = System.currentTimeMillis()
)
