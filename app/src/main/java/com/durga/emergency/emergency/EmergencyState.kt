package com.durga.emergency.emergency

/**
 * Explicit emergency response states defining the DURGA pipeline:
 * SENSE -> ASSESS -> LOCATE -> DECIDE -> ALERT -> RESPOND
 */
enum class EmergencyState {
    IDLE,
    TRIGGERED,
    VERIFYING,
    CANCELLED,
    RECORDING,
    ANALYZING,
    LOCATION_ACQUIRING,
    ESCALATING,
    ACTIVE,
    ACKNOWLEDGED,
    RESPONDING,
    RESOLVED,
    FAILED
}

enum class UserRole {
    USER,
    EMERGENCY_CONTACT,
    POLICE_RESPONDER
}
