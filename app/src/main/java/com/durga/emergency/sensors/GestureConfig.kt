package com.durga.emergency.sensors

import android.graphics.PointF

enum class GestureType {
    MOTION_SHAKE_SEQUENCE,
    TOUCH_PATTERN_DRAW
}

data class GesturePoint(
    val x: Float,
    val y: Float,
    val timestamp: Long = System.currentTimeMillis()
)

/**
 * Configurable parameters for custom SOS triggers.
 * Eliminates hardcoded magic numbers and supports both motion and touch pattern gestures.
 */
data class GestureConfig(
    val id: String,
    val name: String,
    val type: GestureType,
    val sensitivityThreshold: Float = 13.0f, // m/s^2 acceleration
    val repetitionCount: Int = 3,           // required directional shakes
    val windowDurationMs: Long = 1500L,     // window duration in ms
    val cooldownDurationMs: Long = 3000L,   // cooldown to prevent accidental repeat activation
    val touchPatternPoints: List<GesturePoint> = emptyList(),
    val isPrimary: Boolean = false,
    val isEnabled: Boolean = true
) {
    companion object {
        fun createDefaultShake(): GestureConfig {
            return GestureConfig(
                id = "GESTURE_DEFAULT_SHAKE",
                name = "Triple Deliberate Shake (Default)",
                type = GestureType.MOTION_SHAKE_SEQUENCE,
                sensitivityThreshold = 13.0f,
                repetitionCount = 3,
                windowDurationMs = 1500L,
                cooldownDurationMs = 3000L,
                isPrimary = true,
                isEnabled = true
            )
        }

        fun createRapidShake(): GestureConfig {
            return GestureConfig(
                id = "GESTURE_RAPID_4SHAKE",
                name = "Rapid 4-Shake Emergency",
                type = GestureType.MOTION_SHAKE_SEQUENCE,
                sensitivityThreshold = 15.0f,
                repetitionCount = 4,
                windowDurationMs = 2000L,
                cooldownDurationMs = 3000L,
                isPrimary = false,
                isEnabled = true
            )
        }

        fun createDefaultTouchPattern(): GestureConfig {
            // Normalized Z-shape pattern points
            val zPoints = listOf(
                GesturePoint(0.1f, 0.1f),
                GesturePoint(0.5f, 0.1f),
                GesturePoint(0.9f, 0.1f),
                GesturePoint(0.5f, 0.5f),
                GesturePoint(0.1f, 0.9f),
                GesturePoint(0.5f, 0.9f),
                GesturePoint(0.9f, 0.9f)
            )
            return GestureConfig(
                id = "GESTURE_Z_PATTERN",
                name = "Z-Stroke Emergency Pattern",
                type = GestureType.TOUCH_PATTERN_DRAW,
                sensitivityThreshold = 0.75f, // 75% template similarity
                touchPatternPoints = zPoints,
                isPrimary = false,
                isEnabled = true
            )
        }
    }
}
