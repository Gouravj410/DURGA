package com.durga.emergency.ui.main

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import android.graphics.Color
import android.hardware.Sensor
import android.hardware.SensorManager
import android.os.Build
import android.os.Bundle
import android.view.View
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.durga.emergency.R
import com.durga.emergency.databinding.ActivityMainBinding
import com.durga.emergency.emergency.EmergencyState
import com.durga.emergency.emergency.UserRole
import com.durga.emergency.sensors.ShakeDetector

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeDetector: ShakeDetector? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (fineLocationGranted && recordAudioGranted) {
            Toast.makeText(this, "Permissions granted: Location & Audio active", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        setupSensors()
        setupListeners()
        observeViewModel()
        requestAppPermissions()
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        shakeDetector = ShakeDetector {
            runOnUiThread {
                Toast.makeText(this, "⚠️ Gesture detected! 3 deliberate shakes verified.", Toast.LENGTH_SHORT).show()
                viewModel.triggerEmergency()
            }
        }
    }

    private fun setupListeners() {
        // SOS Button
        binding.btnSos.setOnClickListener {
            viewModel.triggerEmergency()
        }

        // Cancel Button during countdown
        binding.btnCancelEmergency.setOnClickListener {
            viewModel.cancelEmergency()
            Toast.makeText(this, "Emergency cancelled. User confirmed safe.", Toast.LENGTH_SHORT).show()
        }

        // Role Selection
        binding.btnRoleUser.setOnClickListener {
            viewModel.setRole(UserRole.USER)
        }
        binding.btnRoleContact.setOnClickListener {
            viewModel.setRole(UserRole.EMERGENCY_CONTACT)
        }
        binding.btnRoleResponder.setOnClickListener {
            viewModel.setRole(UserRole.POLICE_RESPONDER)
        }

        // Card status click to reset when resolved
        binding.cardStatus.setOnClickListener {
            if (viewModel.emergencyState.value == EmergencyState.ACTIVE) {
                viewModel.resetToSafe()
                Toast.makeText(this, "Incident closed. Returning to protected state.", Toast.LENGTH_SHORT).show()
            }
        }
    }

    private fun observeViewModel() {
        viewModel.currentRole.observe(this) { role ->
            binding.tvRoleBadge.text = "ROLE: ${role.name.replace('_', ' ')}"
            when (role) {
                UserRole.USER -> {
                    binding.btnSos.visibility = View.VISIBLE
                    binding.viewGlowRing.visibility = View.VISIBLE
                }
                UserRole.EMERGENCY_CONTACT, UserRole.POLICE_RESPONDER -> {
                    // Contact/Responder view
                    binding.btnSos.visibility = View.VISIBLE
                }
            }
        }

        viewModel.countdownSeconds.observe(this) { seconds ->
            binding.tvCountdownNumber.text = seconds.toString()
        }

        viewModel.locationInfo.observe(this) { info ->
            binding.tvTelemetryGps.text = info
        }

        viewModel.aiDistressInfo.observe(this) { info ->
            binding.tvTelemetryAi.text = info
        }

        viewModel.emergencyState.observe(this) { state ->
            renderState(state)
        }

        viewModel.activeEmergencyEvent.observe(this) { event ->
            if (event != null) {
                val detailMsg = "ID: ${event.eventId} | Station: ${event.nearestPoliceStationName} (${String.format("%.1f", event.nearestPoliceStationDistanceKm)} km)"
                binding.tvStatusSubtitle.text = detailMsg
            }
        }
    }

    private fun renderState(state: EmergencyState) {
        when (state) {
            EmergencyState.IDLE -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_green_safe))
                binding.tvStatusTitle.text = getString(R.string.status_protected)
                binding.tvStatusSubtitle.text = "Shake gesture & SOS button standby"
                binding.btnSos.isEnabled = true
                binding.btnSos.text = "SOS"
            }
            EmergencyState.TRIGGERED, EmergencyState.VERIFYING -> {
                binding.layoutCountdown.visibility = View.VISIBLE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_amber_warning))
                binding.tvStatusTitle.text = "VERIFYING EMERGENCY TRIGGER"
                binding.tvStatusSubtitle.text = "Cancellation window active (5 seconds)"
            }
            EmergencyState.CANCELLED -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_blue_accent))
                binding.tvStatusTitle.text = "EMERGENCY CANCELLED"
                binding.tvStatusSubtitle.text = "User confirmed safe. No alert dispatched."
            }
            EmergencyState.LOCATION_ACQUIRING -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "ACQUIRING GPS LOCATION..."
                binding.tvStatusSubtitle.text = "Contacting satellite & network providers..."
            }
            EmergencyState.ANALYZING -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "ANALYZING ACOUSTIC DISTRESS..."
                binding.tvStatusSubtitle.text = "AI Distress Scoring Layer processing audio..."
            }
            EmergencyState.ACTIVE -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "🚨 EMERGENCY ACTIVE — ESCALATED"
                binding.btnSos.isEnabled = true
                binding.btnSos.text = "ACTIVE"
            }
            else -> {
                binding.layoutCountdown.visibility = View.GONE
            }
        }
    }

    private fun requestAppPermissions() {
        val permissionsToRequest = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissionsToRequest.add(Manifest.permission.POST_NOTIFICATIONS)
        }

        val missingPermissions = permissionsToRequest.filter {
            ContextCompat.checkSelfPermission(this, it) != PackageManager.PERMISSION_GRANTED
        }

        if (missingPermissions.isNotEmpty()) {
            permissionLauncher.launch(missingPermissions.toTypedArray())
        }
    }

    override fun onResume() {
        super.onResume()
        accelerometer?.let {
            sensorManager?.registerListener(shakeDetector, it, SensorManager.SENSOR_DELAY_UI)
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(shakeDetector)
    }
}
