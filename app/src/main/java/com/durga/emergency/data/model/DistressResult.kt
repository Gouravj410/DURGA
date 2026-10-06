package com.durga.emergency.data.model

data class DistressResult(
    val score: Float = 0.0f,
    val classification: String = "Normal",
    val confidence: Float = 0.0f,
    val detectedSignals: List<String> = emptyList(),
    val timestamp: Long = System.currentTimeMillis()
) {
    val level: String
        get() = when {
            score >= 0.70f -> "HIGH"
            score >= 0.40f -> "MODERATE"
            else -> "LOW"
        }
}
