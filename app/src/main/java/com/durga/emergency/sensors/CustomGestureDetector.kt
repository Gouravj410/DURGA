package com.durga.emergency.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import android.util.Log
import kotlin.math.hypot
import kotlin.math.max
import kotlin.math.min
import kotlin.math.sqrt

/**
 * Production-grade configurable gesture detector.
 * Replaces hardcoded fixed 3-shake logic with configurable motion sequence evaluation,
 * safe test mode, and touch pattern similarity comparison.
 */
class CustomGestureDetector(
    var config: GestureConfig,
    private val onGestureMatched: (config: GestureConfig, confidence: Float) -> Unit,
    private val onTestFeedback: ((peakGForce: Float, currentCount: Int, requiredCount: Int, isMatched: Boolean) -> Unit)? = null
) : SensorEventListener {

    companion object {
        private const val TAG = "CUSTOM_GESTURE_DETECTOR"
        private const val MIN_SLOP_TIME_MS = 150L // Minimum debounce between directional reversals (~6.6 Hz max)
        private const val GRAVITY_ALPHA = 0.82f   // IIR filter factor isolating static 1g Earth gravity
    }

    var isTestMode: Boolean = false

    private var lastShakeTimestamp: Long = 0L
    private var lastTriggerTimestamp: Long = 0L
    private var shakeCount: Int = 0
    private var peakGForceObserved: Float = 0f

    // Dynamic gravity isolation state
    private val gravity = FloatArray(3) { 0f }
    private var hasGravityEstimate = false

    // Alternating directional reversal tracking
    private var lastPeakSign: Int = 0 // +1 or -1 on dominant axis

    fun reset() {
        shakeCount = 0
        lastPeakSign = 0
        lastShakeTimestamp = 0L
        peakGForceObserved = 0f
        hasGravityEstimate = false
    }

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return
        if (config.type != GestureType.MOTION_SHAKE_SEQUENCE || !config.isEnabled) return

        // 1. Isolate dynamic linear acceleration by separating static 1g Earth gravity
        if (!hasGravityEstimate) {
            gravity[0] = event.values[0]
            gravity[1] = event.values[1]
            gravity[2] = event.values[2]
            hasGravityEstimate = true
            return
        }

        gravity[0] = GRAVITY_ALPHA * gravity[0] + (1 - GRAVITY_ALPHA) * event.values[0]
        gravity[1] = GRAVITY_ALPHA * gravity[1] + (1 - GRAVITY_ALPHA) * event.values[1]
        gravity[2] = GRAVITY_ALPHA * gravity[2] + (1 - GRAVITY_ALPHA) * event.values[2]

        val linearX = event.values[0] - gravity[0]
        val linearY = event.values[1] - gravity[1]
        val linearZ = event.values[2] - gravity[2]

        // Dynamic linear acceleration magnitude (at rest on table = 0.0 m/s², picking up = ~2.0 m/s²)
        val dynamicMagnitude = sqrt((linearX * linearX + linearY * linearY + linearZ * linearZ).toDouble()).toFloat()

        if (dynamicMagnitude > peakGForceObserved) {
            peakGForceObserved = dynamicMagnitude
        }

        val now = System.currentTimeMillis()

        // 2. Check if dynamic acceleration exceeds configured sensitivity threshold
        if (dynamicMagnitude >= config.sensitivityThreshold) {
            // Debounce movements occurring too rapidly to be intentional physical gestures
            if (now - lastShakeTimestamp < MIN_SLOP_TIME_MS) {
                return
            }

            // Reset sequence if the sequence window expired
            if (now - lastShakeTimestamp > config.windowDurationMs) {
                shakeCount = 0
                lastPeakSign = 0
                peakGForceObserved = dynamicMagnitude
            }

            // 3. Directional sign reversal verification:
            // A true physical shake reverses direction (e.g. +X -> -X -> +X).
            // Unidirectional movements (lifting phone, rotating, setting on table) do NOT qualify.
            val absX = kotlin.math.abs(linearX)
            val absY = kotlin.math.abs(linearY)
            val absZ = kotlin.math.abs(linearZ)
            val dominantAxisVal = if (absX > absY) {
                if (absX > absZ) linearX else linearZ
            } else {
                if (absY > absZ) linearY else linearZ
            }
            val currentSign = if (dominantAxisVal >= 0f) 1 else -1

            val isDirectionalReversal = (lastPeakSign != 0 && currentSign != lastPeakSign)
            lastPeakSign = currentSign
            lastShakeTimestamp = now

            if (isDirectionalReversal || shakeCount == 0) {
                shakeCount++
            }

            val isMatch = shakeCount >= config.repetitionCount
            val confidence = min(1.0f, (peakGForceObserved / max(1.0f, config.sensitivityThreshold)))

            if (isTestMode) {
                onTestFeedback?.invoke(peakGForceObserved, shakeCount, config.repetitionCount, isMatch)
                if (isMatch) {
                    shakeCount = 0
                    lastPeakSign = 0
                    peakGForceObserved = 0f
                    Log.d(TAG, "Test mode: Gesture matched with confidence $confidence (No SOS triggered)")
                }
                return
            }

            // Normal SOS trigger mode with cooldown protection
            if (isMatch) {
                shakeCount = 0
                lastPeakSign = 0
                peakGForceObserved = 0f

                if (now - lastTriggerTimestamp >= config.cooldownDurationMs) {
                    lastTriggerTimestamp = now
                    Log.i(TAG, "SOS Gesture Confirmed: ${config.name} (Dynamic Threshold: ${config.sensitivityThreshold} m/s², Count: ${config.repetitionCount})")
                    onGestureMatched(config, confidence)
                } else {
                    Log.d(TAG, "Gesture match suppressed by cooldown (${config.cooldownDurationMs}ms)")
                }
            }
        }
    }

    /**
     * Evaluates a user-drawn stroke against the saved template points.
     * Returns a similarity score between 0.0f (no match) and 1.0f (identical).
     */
    fun evaluateTouchPattern(drawnPoints: List<GesturePoint>, templatePoints: List<GesturePoint>): Float {
        if (drawnPoints.size < 4 || templatePoints.size < 4) return 0f

        // Resample both point sets to 32 equidistant points for uniform comparison
        val resampledDrawn = resamplePoints(drawnPoints, 32)
        val resampledTemplate = resamplePoints(templatePoints, 32)

        var totalDistance = 0f
        for (i in resampledDrawn.indices) {
            val d = resampledDrawn[i]
            val t = resampledTemplate[i]
            totalDistance += hypot(d.x - t.x, d.y - t.y)
        }

        val averageDistance = totalDistance / resampledDrawn.size
        // Bounded similarity metric: 1.0 when identical, decays as average distance increases
        val similarity = max(0f, 1.0f - (averageDistance * 2.0f))

        Log.d(TAG, "Touch pattern evaluation: Similarity=$similarity against template size=${templatePoints.size}")
        return similarity
    }

    private fun resamplePoints(points: List<GesturePoint>, targetCount: Int): List<GesturePoint> {
        if (points.isEmpty()) return emptyList()
        val totalLength = calculatePathLength(points)
        val interval = totalLength / (targetCount - 1)
        var accumulatedDistance = 0f

        val result = mutableListOf(points.first())
        val workingPoints = points.toMutableList()

        var i = 1
        while (i < workingPoints.size && result.size < targetCount) {
            val prev = workingPoints[i - 1]
            val curr = workingPoints[i]
            val segmentDist = hypot(curr.x - prev.x, curr.y - prev.y)

            if ((accumulatedDistance + segmentDist) >= interval) {
                val ratio = (interval - accumulatedDistance) / segmentDist
                val newX = prev.x + ratio * (curr.x - prev.x)
                val newY = prev.y + ratio * (curr.y - prev.y)
                val interpolated = GesturePoint(newX, newY)
                result.add(interpolated)
                workingPoints.add(i, interpolated)
                accumulatedDistance = 0f
            } else {
                accumulatedDistance += segmentDist
            }
            i++
        }

        while (result.size < targetCount) {
            result.add(points.last())
        }

        return result
    }

    private fun calculatePathLength(points: List<GesturePoint>): Float {
        var length = 0f
        for (i in 1 until points.size) {
            length += hypot(points[i].x - points[i - 1].x, points[i].y - points[i - 1].y)
        }
        return if (length <= 0f) 0.001f else length
    }
}
