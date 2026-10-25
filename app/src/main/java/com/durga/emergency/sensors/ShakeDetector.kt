package com.durga.emergency.sensors

import android.hardware.Sensor
import android.hardware.SensorEvent
import android.hardware.SensorEventListener

/**
 * Backward compatibility wrapper delegating to CustomGestureDetector.
 * @deprecated Use CustomGestureDetector with user-configured GestureConfig instead.
 */
@Deprecated("Use CustomGestureDetector with user-configured GestureConfig instead")
class ShakeDetector(
    private val onShakeDetected: () -> Unit
) : SensorEventListener {

    private val detector = CustomGestureDetector(
        config = GestureConfig.createDefaultShake(),
        onGestureMatched = { _, _ -> onShakeDetected() }
    )

    override fun onAccuracyChanged(sensor: Sensor?, accuracy: Int) {
        detector.onAccuracyChanged(sensor, accuracy)
    }

    override fun onSensorChanged(event: SensorEvent) {
        detector.onSensorChanged(event)
    }
}
