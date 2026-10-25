package com.durga.emergency.ui.gesture

import android.content.Context
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.os.VibratorManager
import android.view.LayoutInflater
import android.view.View
import android.widget.Button
import android.widget.CheckBox
import android.widget.EditText
import android.widget.ProgressBar
import android.widget.RadioButton
import android.widget.RadioGroup
import android.widget.SeekBar
import android.widget.TextView
import android.widget.Toast
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.durga.emergency.R
import com.durga.emergency.databinding.ActivityGestureSettingsBinding
import com.durga.emergency.sensors.CustomGestureDetector
import com.durga.emergency.sensors.GestureConfig
import com.durga.emergency.sensors.GesturePoint
import com.durga.emergency.sensors.GestureRepository
import com.durga.emergency.sensors.GestureType
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import java.util.UUID

class GestureSettingsActivity : AppCompatActivity() {

    private lateinit var binding: ActivityGestureSettingsBinding
    private lateinit var repository: GestureRepository

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var testGestureDetector: CustomGestureDetector? = null

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityGestureSettingsBinding.inflate(layoutInflater)
        setContentView(binding.root)

        repository = GestureRepository.getInstance(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        setupToolbar()
        setupMasterSwitch()
        setupTestMode()
        setupAddGestureButton()
        refreshGesturesUi()
    }

    private fun setupToolbar() {
        binding.toolbar.setNavigationOnClickListener {
            finish()
        }
    }

    private fun setupMasterSwitch() {
        binding.switchGestureEnabled.isChecked = repository.isGestureActivationEnabled
        binding.switchGestureEnabled.setOnCheckedChangeListener { _, isChecked ->
            repository.isGestureActivationEnabled = isChecked
            Toast.makeText(
                this,
                if (isChecked) "SOS Gestures Activated" else "SOS Gestures Disabled",
                Toast.LENGTH_SHORT
            ).show()
        }
    }

    private fun setupTestMode() {
        binding.switchTestMode.setOnCheckedChangeListener { _, isChecked ->
            if (isChecked) {
                startTestMode()
            } else {
                stopTestMode()
            }
        }

        binding.btnClearTestCanvas.setOnClickListener {
            binding.drawingViewTest.clear()
            binding.tvTestStatusLabel.text = "Canvas cleared. Draw your pattern template to test match."
            binding.tvTestStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        }

        binding.drawingViewTest.onDrawingFinished = { drawnPoints ->
            val primary = repository.getPrimaryGesture()
            if (primary.type == GestureType.TOUCH_PATTERN_DRAW && testGestureDetector != null) {
                val similarity = testGestureDetector!!.evaluateTouchPattern(drawnPoints, primary.touchPatternPoints)
                val isMatch = similarity >= primary.sensitivityThreshold

                if (isMatch) {
                    binding.tvTestStatusLabel.text = "✅ TOUCH PATTERN MATCH CONFIRMED (${(similarity * 100).toInt()}%) (Test Mode)"
                    binding.tvTestStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.durga_green_safe))
                    binding.drawingViewTest.setStrokeColor(0xFF34D399.toInt())
                    triggerHapticFeedback()
                } else {
                    binding.tvTestStatusLabel.text = "❌ Pattern mismatch: ${(similarity * 100).toInt()}% similarity (Requires ${(primary.sensitivityThreshold * 100).toInt()}%)"
                    binding.tvTestStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.durga_red_bright))
                    binding.drawingViewTest.setStrokeColor(0xFFEF4444.toInt())
                }
            }
        }
    }

    private fun startTestMode() {
        val primary = repository.getPrimaryGesture()

        testGestureDetector = CustomGestureDetector(
            config = primary,
            onGestureMatched = { _, _ -> },
            onTestFeedback = { peakGForce, currentCount, requiredCount, isMatched ->
                runOnUiThread {
                    binding.pbAccelerationMeter.progress = peakGForce.toInt().coerceIn(0, 30)
                    binding.tvTestTelemetry.text = String.format("Peak Acceleration: %.1f m/s² | Repetitions: %d / %d", peakGForce, currentCount, requiredCount)

                    if (isMatched) {
                        binding.tvTestStatusLabel.text = "✅ MOTION GESTURE MATCH CONFIRMED (Test Mode — No SOS)"
                        binding.tvTestStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.durga_green_safe))
                        triggerHapticFeedback()
                    } else {
                        binding.tvTestStatusLabel.text = "⚡ Shake detected: $currentCount / $requiredCount deliberate shakes"
                        binding.tvTestStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
                    }
                }
            }
        ).apply {
            isTestMode = true
        }

        if (primary.type == GestureType.TOUCH_PATTERN_DRAW) {
            binding.layoutTouchTestCanvas.visibility = View.VISIBLE
            binding.tvTestStatusLabel.text = "Touch Pattern Test: Draw on canvas below to test template match"
        } else {
            binding.layoutTouchTestCanvas.visibility = View.GONE
            binding.tvTestStatusLabel.text = "Listening for motion: Perform '${primary.name}' now..."
            accelerometer?.let {
                sensorManager?.registerListener(testGestureDetector, it, SensorManager.SENSOR_DELAY_UI)
            }
        }
    }

    private fun stopTestMode() {
        sensorManager?.unregisterListener(testGestureDetector)
        testGestureDetector = null
        binding.layoutTouchTestCanvas.visibility = View.GONE
        binding.tvTestStatusLabel.text = "Test mode deactivated. Toggle switch above to re-enable."
        binding.tvTestStatusLabel.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
        binding.pbAccelerationMeter.progress = 0
        binding.tvTestTelemetry.text = "G-Force: 0.0 m/s² | Repetitions: 0 / 0"
    }

    private fun refreshGesturesUi() {
        val gestures = repository.getAllGestures()
        val primary = repository.getPrimaryGesture()

        // Update Primary Card
        binding.tvPrimaryGestureName.text = primary.name
        binding.tvPrimaryTypeBadge.text = if (primary.type == GestureType.MOTION_SHAKE_SEQUENCE) "MOTION SHAKE" else "TOUCH PATTERN"
        binding.tvPrimaryGestureDetails.text = if (primary.type == GestureType.MOTION_SHAKE_SEQUENCE) {
            String.format("Sensitivity: %.1f m/s² | Repetitions: %d shakes | Window: %dms", primary.sensitivityThreshold, primary.repetitionCount, primary.windowDurationMs)
        } else {
            String.format("Template stroke pattern with %d trajectory points | Min similarity: %d%%", primary.touchPatternPoints.size, (primary.sensitivityThreshold * 100).toInt())
        }

        // Populate Saved Gestures Container
        binding.layoutSavedGesturesContainer.removeAllViews()
        val inflater = LayoutInflater.from(this)

        for (gesture in gestures) {
            val cardView = inflater.inflate(R.layout.item_gesture_card, binding.layoutSavedGesturesContainer, false)
            val tvName = cardView.findViewById<TextView>(R.id.tvItemGestureName)
            val tvBadge = cardView.findViewById<TextView>(R.id.tvItemPrimaryBadge)
            val tvDetails = cardView.findViewById<TextView>(R.id.tvItemGestureDetails)
            val btnSetPrimary = cardView.findViewById<Button>(R.id.btnItemSetPrimary)
            val btnDelete = cardView.findViewById<Button>(R.id.btnItemDelete)

            tvName.text = gesture.name
            if (gesture.isPrimary) {
                tvBadge.visibility = View.VISIBLE
                btnSetPrimary.visibility = View.GONE
                btnDelete.visibility = View.GONE
            } else {
                tvBadge.visibility = View.GONE
                btnSetPrimary.visibility = View.VISIBLE
                btnDelete.visibility = View.VISIBLE
            }

            tvDetails.text = if (gesture.type == GestureType.MOTION_SHAKE_SEQUENCE) {
                "Motion: ${gesture.sensitivityThreshold} m/s², ${gesture.repetitionCount} shakes, ${gesture.windowDurationMs}ms window"
            } else {
                "Touch: Draw pattern template (${gesture.touchPatternPoints.size} points)"
            }

            btnSetPrimary.setOnClickListener {
                repository.setPrimaryGesture(gesture.id)
                refreshGesturesUi()
                if (binding.switchTestMode.isChecked) {
                    stopTestMode()
                    startTestMode()
                }
                Toast.makeText(this, "'${gesture.name}' set as primary trigger", Toast.LENGTH_SHORT).show()
            }

            btnDelete.setOnClickListener {
                MaterialAlertDialogBuilder(this)
                    .setTitle("Delete Gesture")
                    .setMessage("Are you sure you want to delete '${gesture.name}'?")
                    .setPositiveButton("Delete") { _, _ ->
                        repository.deleteGesture(gesture.id)
                        refreshGesturesUi()
                    }
                    .setNegativeButton("Cancel", null)
                    .show()
            }

            binding.layoutSavedGesturesContainer.addView(cardView)
        }
    }

    private fun setupAddGestureButton() {
        binding.btnAddNewGesture.setOnClickListener {
            showCreateGestureDialog()
        }
    }

    private fun showCreateGestureDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_create_gesture, null)
        val etName = dialogView.findViewById<EditText>(R.id.etDialogGestureName)
        val rgType = dialogView.findViewById<RadioGroup>(R.id.rgDialogGestureType)
        val rbMotion = dialogView.findViewById<RadioButton>(R.id.rbDialogMotion)
        val rbTouch = dialogView.findViewById<RadioButton>(R.id.rbDialogTouch)
        val layoutMotion = dialogView.findViewById<View>(R.id.layoutDialogMotionParams)
        val layoutTouch = dialogView.findViewById<View>(R.id.layoutDialogTouchParams)

        val tvSensitivity = dialogView.findViewById<TextView>(R.id.tvSensitivityLabel)
        val sbSensitivity = dialogView.findViewById<SeekBar>(R.id.sbDialogSensitivity)
        val tvCount = dialogView.findViewById<TextView>(R.id.tvCountLabel)
        val sbCount = dialogView.findViewById<SeekBar>(R.id.sbDialogCount)
        val tvWindow = dialogView.findViewById<TextView>(R.id.tvWindowLabel)
        val sbWindow = dialogView.findViewById<SeekBar>(R.id.sbDialogWindow)

        val drawingView = dialogView.findViewById<GestureDrawingView>(R.id.drawingViewDialog)
        val btnClearCanvas = dialogView.findViewById<Button>(R.id.btnDialogClearCanvas)
        val cbSetPrimary = dialogView.findViewById<CheckBox>(R.id.cbDialogSetPrimary)

        // Type toggle
        rgType.setOnCheckedChangeListener { _, checkedId ->
            if (checkedId == R.id.rbDialogMotion) {
                layoutMotion.visibility = View.VISIBLE
                layoutTouch.visibility = View.GONE
            } else {
                layoutMotion.visibility = View.GONE
                layoutTouch.visibility = View.VISIBLE
            }
        }

        // SeekBars setup
        sbSensitivity.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(8)
                tvSensitivity.text = "Acceleration Sensitivity: $value.0 m/s²"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbCount.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val value = progress.coerceAtLeast(2)
                tvCount.text = "Required Shakes: $value"
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        sbWindow.setOnSeekBarChangeListener(object : SeekBar.OnSeekBarChangeListener {
            override fun onProgressChanged(seekBar: SeekBar?, progress: Int, fromUser: Boolean) {
                val sec = progress / 10.0f
                tvWindow.text = String.format("Time Window: %.1f seconds", sec)
            }
            override fun onStartTrackingTouch(seekBar: SeekBar?) {}
            override fun onStopTrackingTouch(seekBar: SeekBar?) {}
        })

        btnClearCanvas.setOnClickListener {
            drawingView.clear()
        }

        MaterialAlertDialogBuilder(this)
            .setView(dialogView)
            .setPositiveButton("Save Gesture") { _, _ ->
                val name = etName.text.toString().trim().ifEmpty { "Custom Gesture" }
                val isMotion = rbMotion.isChecked
                val isPrimary = cbSetPrimary.isChecked

                val newGesture = if (isMotion) {
                    val sens = sbSensitivity.progress.coerceAtLeast(8).toFloat()
                    val count = sbCount.progress.coerceAtLeast(2)
                    val window = (sbWindow.progress.coerceAtLeast(10) * 100L)
                    GestureConfig(
                        id = "GESTURE_" + UUID.randomUUID().toString().take(8).uppercase(),
                        name = name,
                        type = GestureType.MOTION_SHAKE_SEQUENCE,
                        sensitivityThreshold = sens,
                        repetitionCount = count,
                        windowDurationMs = window,
                        cooldownDurationMs = 3000L,
                        isPrimary = isPrimary,
                        isEnabled = true
                    )
                } else {
                    val points = drawingView.getNormalizedPoints()
                    GestureConfig(
                        id = "GESTURE_" + UUID.randomUUID().toString().take(8).uppercase(),
                        name = name,
                        type = GestureType.TOUCH_PATTERN_DRAW,
                        sensitivityThreshold = 0.75f,
                        touchPatternPoints = points,
                        isPrimary = isPrimary,
                        isEnabled = true
                    )
                }

                repository.saveGesture(newGesture)
                refreshGesturesUi()
                Toast.makeText(this, "Gesture '$name' saved successfully!", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun triggerHapticFeedback() {
        try {
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.S) {
                val vibratorManager = getSystemService(Context.VIBRATOR_MANAGER_SERVICE) as? VibratorManager
                vibratorManager?.defaultVibrator?.vibrate(VibrationEffect.createOneShot(50, VibrationEffect.DEFAULT_AMPLITUDE))
            } else {
                @Suppress("DEPRECATION")
                val vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator
                @Suppress("DEPRECATION")
                vibrator?.vibrate(50)
            }
        } catch (e: Exception) {
            // Non-critical haptic
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        stopTestMode()
    }
}
