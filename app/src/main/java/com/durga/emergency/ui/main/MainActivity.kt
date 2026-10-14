package com.durga.emergency.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.content.pm.PackageManager
import android.hardware.Sensor
import android.hardware.SensorManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.media.AudioAttributes
import android.media.RingtoneManager
import androidx.core.app.NotificationCompat
import androidx.core.content.ContextCompat
import com.durga.emergency.R
import com.durga.emergency.audio.DurgaAudioPlayer
import com.durga.emergency.data.model.EmergencyEvent
import com.durga.emergency.databinding.ActivityMainBinding
import com.durga.emergency.emergency.EmergencyState
import com.durga.emergency.emergency.UserRole
import com.durga.emergency.sensors.ShakeDetector
import com.durga.emergency.service.DurgaFirebaseMessagingService
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var shakeDetector: ShakeDetector? = null
    private lateinit var audioPlayer: DurgaAudioPlayer

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocationGranted = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val recordAudioGranted = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        if (fineLocationGranted && recordAudioGranted) {
            Toast.makeText(this, "Sensors active: GPS & Audio ready", Toast.LENGTH_SHORT).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)
        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioPlayer = DurgaAudioPlayer(this)

        setupSensors()
        setupListeners()
        observeViewModel()
        requestAppPermissions()

        if (viewModel.isFirstLaunch) {
            showRoleSelectionDialog()
        }
    }

    private fun setupSensors() {
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        shakeDetector = ShakeDetector {
            runOnUiThread {
                if (viewModel.currentRole.value == UserRole.USER) {
                    Toast.makeText(this, "⚠️ Gesture detected! 3 deliberate shakes verified.", Toast.LENGTH_SHORT).show()
                    viewModel.triggerEmergency()
                }
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

        // Role Switcher Button
        binding.btnSwitchRole.setOnClickListener {
            showRoleSelectionDialog()
        }

        // Role Tab Bar
        binding.btnTabUser.setOnClickListener { viewModel.setRole(UserRole.USER) }
        binding.btnTabContact.setOnClickListener { viewModel.setRole(UserRole.EMERGENCY_CONTACT) }
        binding.btnTabResponder.setOnClickListener { viewModel.setRole(UserRole.POLICE_RESPONDER) }

        // Contact Edit Button
        binding.btnEditContact.setOnClickListener {
            showEditContactDialog()
        }

        // Contact Actions
        binding.btnContactPlayAudio.setOnClickListener {
            playEmergencyAudio()
        }

        binding.btnContactOpenMap.setOnClickListener {
            val event = viewModel.activeEmergencyEvent.value
            if (event?.latitude != null && event.longitude != null) {
                openMapLocation(event.latitude, event.longitude, event.userName)
            } else {
                Toast.makeText(this, "Location coordinates unavailable", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnContactCallVictim.setOnClickListener {
            val phone = viewModel.contactPhone.value ?: "+919876543210"
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:$phone"))
            startActivity(dialIntent)
        }

        // Responder Actions
        binding.btnResponderPlayAudio.setOnClickListener {
            playEmergencyAudio()
        }

        binding.btnResponderMap.setOnClickListener {
            val event = viewModel.activeEmergencyEvent.value
            if (event?.latitude != null && event.longitude != null) {
                openMapLocation(event.latitude, event.longitude, "Emergency Incident")
            }
        }

        binding.btnResponderAcknowledge.setOnClickListener {
            viewModel.activeEmergencyEvent.value?.let { event ->
                viewModel.updateIncidentStatus(event.eventId, "ACKNOWLEDGED")
                Toast.makeText(this, "Incident ${event.eventId} ACKNOWLEDGED", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResponderRespond.setOnClickListener {
            viewModel.activeEmergencyEvent.value?.let { event ->
                viewModel.updateIncidentStatus(event.eventId, "RESPONDING")
                Toast.makeText(this, "Unit dispatched to ${event.eventId}", Toast.LENGTH_SHORT).show()
            }
        }

        binding.btnResponderResolve.setOnClickListener {
            viewModel.activeEmergencyEvent.value?.let { event ->
                viewModel.updateIncidentStatus(event.eventId, "RESOLVED")
                Toast.makeText(this, "Incident ${event.eventId} marked RESOLVED", Toast.LENGTH_SHORT).show()
            }
        }

        // Card status click to reset when resolved
        binding.cardStatus.setOnClickListener {
            if (viewModel.emergencyState.value == EmergencyState.ACTIVE) {
                viewModel.resetToSafe()
                Toast.makeText(this, "Incident closed. Returning to protected state.", Toast.LENGTH_SHORT).show()
            }
        }

        // Simulate Nearby Mesh Citizen SOS
        binding.btnSimulateNearbyAlert.setOnClickListener {
            Toast.makeText(this, "📡 Simulating incoming proximity mesh SOS from nearby citizen...", Toast.LENGTH_SHORT).show()
            viewModel.simulateNearbyMeshAlert()
        }

        // Proximity Card Actions
        binding.btnProximityDismiss.setOnClickListener {
            binding.cardProximityAlert.visibility = View.GONE
            viewModel.dismissNearbyMeshAlert()
        }

        binding.btnProximityViewMap.setOnClickListener {
            val event = viewModel.nearbyMeshAlertEvent.value
            if (event?.latitude != null && event.longitude != null) {
                openMapLocation(event.latitude, event.longitude, event.userName)
            }
        }

        binding.btnProximityCall112.setOnClickListener {
            val dialIntent = Intent(Intent.ACTION_DIAL, Uri.parse("tel:112"))
            startActivity(dialIntent)
        }
    }

    private fun playEmergencyAudio() {
        val event = viewModel.activeEmergencyEvent.value
        val audioUrl = event?.audioUrl
        if (!audioUrl.isNullOrEmpty()) {
            if (audioPlayer.isPlaying) {
                audioPlayer.stopAudio()
                Toast.makeText(this, "Audio playback stopped", Toast.LENGTH_SHORT).show()
                binding.btnContactPlayAudio.text = "PLAY AUDIO"
                binding.btnResponderPlayAudio.text = "AUDIO"
            } else {
                Toast.makeText(this, "🔊 Playing distress audio sample...", Toast.LENGTH_SHORT).show()
                binding.btnContactPlayAudio.text = "STOP"
                binding.btnResponderPlayAudio.text = "STOP"
                audioPlayer.playAudio(
                    audioPathOrUrl = audioUrl,
                    onComplete = {
                        runOnUiThread {
                            binding.btnContactPlayAudio.text = "PLAY AUDIO"
                            binding.btnResponderPlayAudio.text = "AUDIO"
                            Toast.makeText(this, "Playback finished", Toast.LENGTH_SHORT).show()
                        }
                    },
                    onError = { err ->
                        runOnUiThread {
                            binding.btnContactPlayAudio.text = "PLAY AUDIO"
                            binding.btnResponderPlayAudio.text = "AUDIO"
                            Toast.makeText(this, "Playback error: $err", Toast.LENGTH_SHORT).show()
                        }
                    }
                )
            }
        } else {
            Toast.makeText(this, "No audio recording available for this incident", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewModel.currentRole.observe(this) { role ->
            applyRoleView(role)
        }

        viewModel.countdownSeconds.observe(this) { seconds ->
            if (viewModel.emergencyState.value == EmergencyState.VERIFYING) {
                binding.tvCountdownNumber.text = seconds.toString()
            }
        }

        viewModel.recordingSecondsRemaining.observe(this) { seconds ->
            if (viewModel.emergencyState.value == EmergencyState.RECORDING) {
                binding.tvCountdownNumber.text = seconds.toString()
            }
        }

        viewModel.locationInfo.observe(this) { info ->
            binding.tvTelemetryGps.text = info
        }

        viewModel.aiDistressInfo.observe(this) { info ->
            binding.tvTelemetryAi.text = info
        }

        viewModel.userName.observe(this) { updateContactCardText() }
        viewModel.contactName.observe(this) { updateContactCardText() }
        viewModel.contactPhone.observe(this) { updateContactCardText() }

        viewModel.emergencyState.observe(this) { state ->
            renderUserState(state)
        }

        viewModel.activeEmergencyEvent.observe(this) { event ->
            renderContactDashboard(event)
            renderResponderDashboard(event)
        }

        viewModel.nearbyMeshAlertEvent.observe(this) { event ->
            handleNearbyMeshAlert(event)
        }
    }

    private fun applyRoleView(role: UserRole) {
        binding.btnSwitchRole.text = "ROLE: ${role.name.replace('_', ' ')}"

        // Reset Tab button styles
        binding.btnTabUser.alpha = if (role == UserRole.USER) 1.0f else 0.6f
        binding.btnTabContact.alpha = if (role == UserRole.EMERGENCY_CONTACT) 1.0f else 0.6f
        binding.btnTabResponder.alpha = if (role == UserRole.POLICE_RESPONDER) 1.0f else 0.6f

        when (role) {
            UserRole.USER -> {
                binding.layoutUserDashboard.visibility = View.VISIBLE
                binding.layoutContactDashboard.visibility = View.GONE
                binding.layoutResponderDashboard.visibility = View.GONE
            }
            UserRole.EMERGENCY_CONTACT -> {
                binding.layoutUserDashboard.visibility = View.GONE
                binding.layoutContactDashboard.visibility = View.VISIBLE
                binding.layoutResponderDashboard.visibility = View.GONE
            }
            UserRole.POLICE_RESPONDER -> {
                binding.layoutUserDashboard.visibility = View.GONE
                binding.layoutContactDashboard.visibility = View.GONE
                binding.layoutResponderDashboard.visibility = View.VISIBLE
            }
        }
    }

    private fun updateContactCardText() {
        val contact = viewModel.contactName.value ?: "Trusted Contact"
        val phone = viewModel.contactPhone.value ?: "+91-9876543210"
        binding.tvUserContactName.text = "$contact ($phone)"
    }

    private fun renderUserState(state: EmergencyState) {
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
                binding.tvCountdownTitle.text = "EMERGENCY TRIGGERED"
                binding.tvCountdownSub.text = "Dispatching alerts in seconds..."
                binding.btnCancelEmergency.visibility = View.VISIBLE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_amber_warning))
                binding.tvStatusTitle.text = "VERIFYING EMERGENCY TRIGGER"
                binding.tvStatusSubtitle.text = "Cancellation window active (5 seconds)"
            }
            EmergencyState.RECORDING -> {
                binding.layoutCountdown.visibility = View.VISIBLE
                binding.tvCountdownTitle.text = "RECORDING AUDIO DISTRESS"
                binding.tvCountdownSub.text = "Microphone active. Capturing acoustic sample..."
                binding.btnCancelEmergency.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "RECORDING ACOUSTIC SAMPLE"
                binding.tvStatusSubtitle.text = "Listening for vocalization & distress energy..."
            }
            EmergencyState.ANALYZING -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "ANALYZING ACOUSTIC DISTRESS..."
                binding.tvStatusSubtitle.text = "AI Distress Scoring Layer processing audio..."
            }
            EmergencyState.LOCATION_ACQUIRING -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "ACQUIRING GPS LOCATION..."
                binding.tvStatusSubtitle.text = "Contacting satellite & network providers..."
            }
            EmergencyState.CANCELLED -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_blue_accent))
                binding.tvStatusTitle.text = "EMERGENCY CANCELLED"
                binding.tvStatusSubtitle.text = "User confirmed safe. No alert dispatched."
            }
            EmergencyState.ACTIVE -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_red_bright))
                binding.tvStatusTitle.text = "🚨 EMERGENCY ACTIVE — ESCALATED"
                binding.btnSos.isEnabled = true
                binding.btnSos.text = "ACTIVE"
            }
            EmergencyState.RESOLVED -> {
                binding.layoutCountdown.visibility = View.GONE
                binding.viewStatusDot.setBackgroundColor(getColor(R.color.durga_green_safe))
                binding.tvStatusTitle.text = "INCIDENT RESOLVED"
                binding.tvStatusSubtitle.text = "Emergency responder marked incident resolved."
            }
            else -> {
                binding.layoutCountdown.visibility = View.GONE
            }
        }
    }

    private fun renderContactDashboard(event: EmergencyEvent?) {
        if (event != null && event.status != "RESOLVED" && event.status != "CANCELLED") {
            binding.tvContactAlertBadge.text = "🚨 ${event.status}"
            binding.tvContactAlertBadge.setBackgroundResource(R.drawable.bg_badge_high)
            binding.tvContactIncidentId.text = event.eventId
            binding.tvContactVictimName.text = "⚠️ DISTRESS DETECTED FROM ${event.userName.uppercase()}"
            binding.tvContactDetails.text = "AI Distress: ${event.emergencyLevel} (${String.format("%.2f", event.distressScore)})\n" +
                    "Assessment: ${event.distressClassification}\n" +
                    "Nearest Station: ${event.nearestPoliceStationName} (${String.format("%.1f", event.nearestPoliceStationDistanceKm)} km)\n" +
                    "GPS Accuracy: ±${String.format("%.0f", event.locationAccuracy)}m"
            binding.layoutContactActions.visibility = View.VISIBLE
        } else {
            binding.tvContactAlertBadge.text = "STANDBY"
            binding.tvContactAlertBadge.setBackgroundResource(R.drawable.bg_badge_safe)
            binding.tvContactIncidentId.text = "No active distress"
            binding.tvContactVictimName.text = "${viewModel.userName.value ?: "Citizen"}: Protected & Safe"
            binding.tvContactDetails.text = "When an emergency is triggered, live GPS coordinates, AI distress score, and nearest police station route will appear here."
            binding.layoutContactActions.visibility = View.GONE
        }
    }

    private fun renderResponderDashboard(event: EmergencyEvent?) {
        if (event != null && event.status != "RESOLVED" && event.status != "CANCELLED") {
            binding.tvResponderStatusBadge.text = event.status
            binding.tvResponderStatusBadge.setBackgroundResource(
                when (event.status) {
                    "ACTIVE" -> R.drawable.bg_badge_high
                    "ACKNOWLEDGED", "RESPONDING" -> R.drawable.bg_badge_ack
                    else -> R.drawable.bg_badge_safe
                }
            )
            binding.tvResponderIncidentId.text = event.eventId
            binding.tvResponderVictimTitle.text = "🚨 INCIDENT: ${event.userName} (${event.emergencyLevel})"
            binding.tvResponderDetails.text = "Distress Assessment: ${event.distressClassification}\nRegistered Contact: ${event.emergencyContactPhone}"
            binding.tvResponderGps.text = "Location: ${String.format("%.4f, %.4f", event.latitude ?: 0.0, event.longitude ?: 0.0)} (±${event.locationAccuracy?.toInt()}m)"
            binding.tvResponderAi.text = "AI Distress: ${event.emergencyLevel} (${String.format("%.2f", event.distressScore)})"
            binding.tvResponderStation.text = "Station: ${event.nearestPoliceStationName} (${String.format("%.1f", event.nearestPoliceStationDistanceKm)} km away)"

            binding.layoutResponderTelemetry.visibility = View.VISIBLE
            binding.layoutResponderActions.visibility = View.VISIBLE
        } else {
            binding.tvResponderStatusBadge.text = "STANDBY"
            binding.tvResponderStatusBadge.setBackgroundResource(R.drawable.bg_badge_safe)
            binding.tvResponderIncidentId.text = "All sectors clear"
            binding.tvResponderVictimTitle.text = "No Active Incidents"
            binding.tvResponderDetails.text = "Waiting for real-time dispatch alerts from citizens in distress."
            binding.layoutResponderTelemetry.visibility = View.GONE
            binding.layoutResponderActions.visibility = View.GONE
        }
    }

    private fun openMapLocation(latitude: Double, longitude: Double, label: String) {
        val uri = Uri.parse("geo:$latitude,$longitude?q=$latitude,$longitude($label)")
        val mapIntent = Intent(Intent.ACTION_VIEW, uri)
        if (mapIntent.resolveActivity(packageManager) != null) {
            startActivity(mapIntent)
        } else {
            // Browser fallback
            val webUri = Uri.parse("https://www.google.com/maps/search/?api=1&query=$latitude,$longitude")
            startActivity(Intent(Intent.ACTION_VIEW, webUri))
        }
    }

    private fun showRoleSelectionDialog() {
        val roles = arrayOf("USER (Protected Citizen)", "EMERGENCY CONTACT (Guardian)", "POLICE / RESPONDER (Dispatch)")
        MaterialAlertDialogBuilder(this)
            .setTitle("Welcome to DURGA — Choose Role")
            .setItems(roles) { _, which ->
                val selectedRole = when (which) {
                    0 -> UserRole.USER
                    1 -> UserRole.EMERGENCY_CONTACT
                    else -> UserRole.POLICE_RESPONDER
                }
                viewModel.setRole(selectedRole)
                viewModel.setFirstLaunchCompleted()
                Toast.makeText(this, "Active Role: ${selectedRole.name}", Toast.LENGTH_SHORT).show()
            }
            .setCancelable(false)
            .show()
    }

    private fun showEditContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_contact, null)
        val etUser = dialogView.findViewById<EditText>(R.id.etUserName)
        val etContact = dialogView.findViewById<EditText>(R.id.etContactName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etContactPhone)

        etUser.setText(viewModel.userName.value)
        etContact.setText(viewModel.contactName.value)
        etPhone.setText(viewModel.contactPhone.value)

        AlertDialog.Builder(this)
            .setTitle("Guardian & User Setup")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                val user = etUser.text.toString().trim().ifEmpty { "Protected Citizen" }
                val contact = etContact.text.toString().trim().ifEmpty { "Trusted Guardian" }
                val phone = etPhone.text.toString().trim().ifEmpty { "+91-9876543210" }

                viewModel.saveContactInfo(user, contact, phone)
                Toast.makeText(this, "Emergency contacts updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
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

    private fun handleNearbyMeshAlert(event: EmergencyEvent?) {
        if (event == null || event.status != "ACTIVE") {
            binding.cardProximityAlert.visibility = View.GONE
            return
        }

        // 1. Populate Proximity Alert Card on User screen
        binding.cardProximityAlert.visibility = View.VISIBLE
        binding.tvProximityVictimName.text = event.userName
        binding.tvProximityAiStatus.text = "Acoustic AI: ${event.distressClassification}"
        binding.tvProximityDistanceBadge.text = "~350m AWAY (MESH)"

        // 2. Send real Android OS Heads-Up Notification with High Priority
        sendLocalProximityNotification(event)

        // 3. Show In-App Emergency Modal Popup Dialog on the screen
        MaterialAlertDialogBuilder(this)
            .setTitle("🚨 CRITICAL SOS: NEARBY CITIZEN IN DISTRESS")
            .setMessage(
                "Proximity mesh detection: A citizen (${event.userName}) is in critical distress ~350m from your current location!\n\n" +
                "• Acoustic AI Assessment: ${event.distressClassification}\n" +
                "• Assigned Station: ${event.nearestPoliceStationName}\n" +
                "• Incident ID: ${event.eventId}\n\n" +
                "Are you able to assist or coordinate with emergency responders?"
            )
            .setPositiveButton("🚨 VIEW ON MAP & RESPOND") { _, _ ->
                openMapLocation(event.latitude ?: 18.5204, event.longitude ?: 73.8567, event.userName)
            }
            .setNegativeButton("📞 CALL 112 (POLICE)") { _, _ ->
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            }
            .setNeutralButton("DISMISS", null)
            .show()
    }

    private fun sendLocalProximityNotification(event: EmergencyEvent) {
        val notificationManager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        val channelId = DurgaFirebaseMessagingService.CHANNEL_ID
        val alarmSound = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)

        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            val channel = NotificationChannel(
                channelId,
                DurgaFirebaseMessagingService.CHANNEL_NAME,
                NotificationManager.IMPORTANCE_HIGH
            ).apply {
                description = "High-priority emergency alerts from protected citizens in proximity mesh"
                enableLights(true)
                enableVibration(true)
                vibrationPattern = longArrayOf(0, 500, 200, 500)
                setSound(
                    alarmSound,
                    AudioAttributes.Builder()
                        .setUsage(AudioAttributes.USAGE_ALARM)
                        .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                        .build()
                )
            }
            notificationManager.createNotificationChannel(channel)
        }

        val intent = Intent(this, MainActivity::class.java).apply {
            flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TOP
            putExtra("EXTRA_EVENT_ID", event.eventId)
        }

        val pendingIntent = PendingIntent.getActivity(
            this,
            event.eventId.hashCode(),
            intent,
            PendingIntent.FLAG_ONE_SHOT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(this, channelId)
            .setSmallIcon(R.drawable.bg_badge_high)
            .setContentTitle("🚨 NEARBY CITIZEN SOS: ${event.userName}")
            .setContentText("Distress ~350m away. AI Scream Detected. Tap to respond.")
            .setStyle(NotificationCompat.BigTextStyle().bigText(
                "Citizen ${event.userName} triggered an emergency ~350m away.\n" +
                "AI Analysis: ${event.distressClassification}\n" +
                "Nearest Station: ${event.nearestPoliceStationName}"
            ))
            .setPriority(NotificationCompat.PRIORITY_HIGH)
            .setDefaults(NotificationCompat.DEFAULT_ALL)
            .setAutoCancel(true)
            .setContentIntent(pendingIntent)
            .build()

        notificationManager.notify(event.eventId.hashCode(), notification)
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stopAudio()
    }
}
