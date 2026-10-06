package com.durga.emergency.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener
import android.hardware.SensorManager
import kotlin.math.sqrt

class ShakeDetector(
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    companion object {
        // Acceleration threshold in m/s^2 (excluding earth gravity).
        // 13.0f corresponds to a deliberate, intentional shake.
        private const val SHAKE_THRESHOLD_GRAVITY = 13.0f
        private const val SHAKE_SLOP_TIME_MS = 350
        private const val SHAKE_RESET_TIME_MS = 1500
        private const val REQUIRED_SHAKE_COUNT = 3
    }

    private var shakeTimestamp: Long = 0
    private var shakeCount: Int = 0

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        // No-op
    }

    override fun onSensorChanged(event: SensorEvent) {
        if (event.sensor.type != Sensor.TYPE_ACCELEROMETER) return

        val x = event.values[0]
        val y = event.values[1]
        val z = event.values[2]

        // Calculate g-force acceleration
        val gX = x / SensorManager.GRAVITY_EARTH
        val gY = y / SensorManager.GRAVITY_EARTH
        val gZ = z / SensorManager.GRAVITY_EARTH

        val gForce = sqrt((gX * gX + gY * gY + gZ * gZ).toDouble()).toFloat()

        if (gForce > (SHAKE_THRESHOLD_GRAVITY / SensorManager.GRAVITY_EARTH)) {
            val now = System.currentTimeMillis()

            // Debounce: ignore shakes that happen too quickly
            if (shakeTimestamp + SHAKE_SLOP_TIME_MS > now) {
                return
            }

            // Reset count if too much time passed between shakes
            if (shakeTimestamp + SHAKE_RESET_TIME_MS < now) {
                shakeCount = 0
            }

            shakeTimestamp = now
            shakeCount++

            if (shakeCount >= REQUIRED_SHAKE_COUNT) {
                shakeCount = 0
                onShakeDetected()
            }
        }
    }
}
