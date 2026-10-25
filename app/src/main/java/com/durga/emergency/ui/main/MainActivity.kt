package com.durga.emergency.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.Ringtone
import android.media.RingtoneManager
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.widget.EditText
import android.widget.Toast
import androidx.activity.result.contract.ActivityResultContracts
import androidx.activity.viewModels
import androidx.appcompat.app.AlertDialog
import androidx.appcompat.app.AppCompatActivity
import androidx.core.content.ContextCompat
import com.durga.emergency.R
import com.durga.emergency.audio.DurgaAudioPlayer
import com.durga.emergency.data.PreferencesManager
import com.durga.emergency.data.model.EmergencyEvent
import com.durga.emergency.databinding.ActivityMainBinding
import com.durga.emergency.emergency.IncidentState
import com.durga.emergency.emergency.TriggerSource
import com.durga.emergency.sensors.CustomGestureDetector
import com.durga.emergency.sensors.GestureConfig
import com.durga.emergency.sensors.GestureRepository
import com.durga.emergency.sensors.GestureType
import com.durga.emergency.service.DurgaSafetyService
import com.durga.emergency.ui.login.LoginActivity
import com.durga.emergency.ui.gesture.GestureSettingsActivity
import androidx.lifecycle.lifecycleScope
import com.google.android.material.dialog.MaterialAlertDialogBuilder
import kotlinx.coroutines.launch

class MainActivity : AppCompatActivity() {

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: PreferencesManager
    private lateinit var gestureRepo: GestureRepository

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var customGestureDetector: CustomGestureDetector? = null
    private lateinit var audioPlayer: DurgaAudioPlayer

    private var alarmRingtone: Ringtone? = null
    private var vibrator: Vibrator? = null

    private val permissionLauncher = registerForActivityResult(
        ActivityResultContracts.RequestMultiplePermissions()
    ) { permissions ->
        val fineLocation = permissions[Manifest.permission.ACCESS_FINE_LOCATION] ?: false
        val recordAudio = permissions[Manifest.permission.RECORD_AUDIO] ?: false
        val postNotifications = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions[Manifest.permission.POST_NOTIFICATIONS] ?: false
        } else {
            true
        }

        if (fineLocation && recordAudio && postNotifications) {
            Toast.makeText(this, "Permissions active: Location, Audio & Push Alerts ready", Toast.LENGTH_SHORT).show()
        } else if (!postNotifications && Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            Toast.makeText(this, "⚠️ Notification permission denied. Background alerts will not appear on lock screen.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferencesManager(this)
        if (!prefs.isLoggedIn) {
            startActivity(Intent(this, LoginActivity::class.java))
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        audioPlayer = DurgaAudioPlayer(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        setupSensors()
        setupListeners()
        observeViewModel()
        requestAppPermissions()

        // Start Persistent Background Notification Trigger (Android 10-14 compliant)
        DurgaSafetyService.startService(this)

        // Retrieve and synchronize real FCM token with central backend
        com.durga.emergency.utils.FcmTokenHelper.syncRegistrationToken(this)

        handleIncomingIntent(intent)
    }

    override fun onNewIntent(intent: Intent) {
        super.onNewIntent(intent)
        setIntent(intent)
        handleIncomingIntent(intent)
    }

    private fun handleIncomingIntent(intent: Intent?) {
        if (intent?.action == DurgaSafetyService.ACTION_TRIGGER_SOS) {
            Toast.makeText(this, "🔴 Lock-Screen / Notification SOS Triggered!", Toast.LENGTH_LONG).show()
            viewModel.triggerEmergency(TriggerSource.PERSISTENT_NOTIFICATION_ACTION)
            return
        }

        val isTestNotification = intent?.getBooleanExtra("EXTRA_IS_TEST_NOTIFICATION", false) ?: false
        val eventId = intent?.getStringExtra("EXTRA_EVENT_ID") ?: ""

        if (isTestNotification) {
            MaterialAlertDialogBuilder(this)
                .setTitle("✅ Internet Push Notification Verified")
                .setMessage("Successfully received and opened DURGA Internet Test Notification.\n\nEvent ID: ${eventId.ifEmpty { "TEST-FCM-VERIFIED" }}\nTransport: Firebase Cloud Messaging (FCM HTTP v1)")
                .setPositiveButton("OK", null)
                .show()
        }
    }

    private fun setupSensors() {
        gestureRepo = GestureRepository.getInstance(this)
        sensorManager = getSystemService(Context.SENSOR_SERVICE) as? SensorManager
        accelerometer = sensorManager?.getDefaultSensor(Sensor.TYPE_ACCELEROMETER)

        val primary = gestureRepo.getPrimaryGesture()
        customGestureDetector = CustomGestureDetector(
            config = primary,
            onGestureMatched = { matchedConfig, confidence ->
                runOnUiThread {
                    Toast.makeText(
                        this,
                        "🚨 SOS Triggered: ${matchedConfig.name} (${(confidence * 100).toInt()}% confidence)",
                        Toast.LENGTH_SHORT
                    ).show()
                    viewModel.triggerEmergency(TriggerSource.IN_APP_SHAKE_GESTURE)
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::gestureRepo.isInitialized) {
            val primary = gestureRepo.getPrimaryGesture()
            customGestureDetector?.config = primary

            if (gestureRepo.isGestureActivationEnabled && primary.type == GestureType.MOTION_SHAKE_SEQUENCE) {
                accelerometer?.let {
                    sensorManager?.registerListener(customGestureDetector, it, SensorManager.SENSOR_DELAY_UI)
                }
            }
        }
    }

    override fun onPause() {
        super.onPause()
        sensorManager?.unregisterListener(customGestureDetector)
    }

    private fun setupListeners() {
        // Gesture Settings Button
        binding.btnGestureSettings.setOnClickListener {
            startActivity(Intent(this, GestureSettingsActivity::class.java))
        }

        // SOS Button
        binding.btnSos.setOnClickListener {
            viewModel.triggerEmergency(TriggerSource.MANUAL_SOS_BUTTON)
        }

        // Repeat SOS Button
        binding.btnRepeatSos.setOnClickListener {
            viewModel.triggerRepeatSos()
            Toast.makeText(this, getString(R.string.prompt_repeat_sos_warning), Toast.LENGTH_LONG).show()
        }

        // Cancel with PIN Button
        binding.btnCancelWithPin.setOnClickListener {
            showPinCancellationDialog()
        }

        // Fallback Cancellation during initial countdown
        binding.btnCancelEmergency.setOnClickListener {
            showPinCancellationDialog()
        }

        // "Are you safe?" Prompt Controls
        binding.btnSafeConfirm.setOnClickListener {
            viewModel.confirmUserSafe()
            Toast.makeText(this, "Confirmed safe. Alert cancelled.", Toast.LENGTH_SHORT).show()
        }

        binding.btnNeedHelp.setOnClickListener {
            viewModel.requestAssistanceManually(TriggerSource.MANUAL_SOS_BUTTON)
            Toast.makeText(this, "Dispatching emergency alerts immediately...", Toast.LENGTH_SHORT).show()
        }

        // Helplines
        binding.btnCallPolice112.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
        }

        binding.btnCallWomen1091.setOnClickListener {
            startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:1091")))
        }

        // Edit contact
        binding.btnEditContact.setOnClickListener {
            showEditContactDialog()
        }

        // Account Profile & Switch Button
        binding.btnAccountProfile.setOnClickListener {
            showAccountDialog()
        }

        // Proximity Alert Dismiss
        binding.btnProximityDismiss.setOnClickListener {
            stopProximityAlarm()
            binding.cardProximityAlert.visibility = View.GONE
            viewModel.dismissProximityAlert()
            Toast.makeText(this, "Proximity alert silenced", Toast.LENGTH_SHORT).show()
        }
    }

    private fun observeViewModel() {
        viewModel.incidentState.observe(this) { state ->
            updateUiForState(state)
        }

        viewModel.nearbyMeshAlertEvent.observe(this) { nearbyEvent ->
            handleNearbyProximityAlert(nearbyEvent)
        }

        viewModel.safetyPromptSeconds.observe(this) { seconds ->
            binding.tvSafeCountdown.text = "${seconds}s"
        }

        viewModel.recordingSecondsRemaining.observe(this) { seconds ->
            if (viewModel.incidentState.value == IncidentState.RECORDING) {
                binding.tvStatusSubtitle.text = "Recording 5-second distress sample: ${seconds}s"
            }
        }

        viewModel.locationInfo.observe(this) { loc ->
            binding.tvTelemetryGps.text = loc
        }

        viewModel.aiDistressInfo.observe(this) { ai ->
            binding.tvTelemetryAi.text = ai
        }

        viewModel.nearestStationInfo.observe(this) { station ->
            binding.tvNearestStationDetails.text = station
        }

        viewModel.userName.observe(this) { name ->
            binding.tvUserContactName.text = "${name} (+91-9876543210)"
        }

        viewModel.pinErrorMessage.observe(this) { err ->
            if (err != null) {
                Toast.makeText(this, err, Toast.LENGTH_LONG).show()
            }
        }
    }

    private fun updateUiForState(state: IncidentState) {
        when (state) {
            IncidentState.IDLE -> {
                binding.tvStatusTitle.text = getString(R.string.status_protected)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.text_primary))
                binding.tvStatusSubtitle.text = "Sensors active: In-app gesture, persistent SOS & manual ready"
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_green_safe))

                binding.layoutCountdown.visibility = View.GONE
                binding.cardAreYouSafe.visibility = View.GONE
                binding.layoutActiveIncidentActions.visibility = View.GONE
                binding.btnSos.isEnabled = true
            }

            IncidentState.TRIGGERED -> {
                binding.tvStatusTitle.text = getString(R.string.status_triggered)
                binding.tvStatusSubtitle.text = "Activating acoustic capture & safety verification..."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
            }

            IncidentState.RECORDING -> {
                binding.tvStatusTitle.text = getString(R.string.status_recording)
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
            }

            IncidentState.ANALYZING -> {
                binding.tvStatusTitle.text = getString(R.string.status_analyzing)
                binding.tvStatusSubtitle.text = "Evaluating RMS energy & peak transients..."
            }

            IncidentState.VERIFYING -> {
                binding.tvStatusTitle.text = getString(R.string.status_verifying)
                binding.tvStatusSubtitle.text = "Acoustic score uncertain. Confirm safety or request help."
                binding.cardAreYouSafe.visibility = View.VISIBLE
                binding.layoutActiveIncidentActions.visibility = View.GONE
            }

            IncidentState.ESCALATING -> {
                binding.cardAreYouSafe.visibility = View.GONE
                binding.tvStatusTitle.text = getString(R.string.status_escalating)
                binding.tvStatusSubtitle.text = "Acquiring high-accuracy GPS & querying 1 km radius..."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_red_bright))
            }

            IncidentState.ACTIVE -> {
                binding.cardAreYouSafe.visibility = View.GONE
                binding.layoutActiveIncidentActions.visibility = View.VISIBLE
                binding.btnRepeatSos.visibility = View.VISIBLE
                binding.btnCancelWithPin.visibility = View.VISIBLE
                binding.tvRepeatSosLockWarning.visibility = View.GONE

                binding.tvStatusTitle.text = getString(R.string.status_emergency)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.durga_red_bright))
                binding.tvStatusSubtitle.text = "Incident live: Nearby citizens targeted & responders alerted."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_red_bright))
            }

            IncidentState.REPEAT_ACTIVE -> {
                binding.cardAreYouSafe.visibility = View.GONE
                binding.layoutActiveIncidentActions.visibility = View.VISIBLE
                binding.btnRepeatSos.visibility = View.VISIBLE
                binding.btnCancelWithPin.visibility = View.VISIBLE // UNIFIED POLICY: Cancellation enabled across all triggers
                binding.tvRepeatSosLockWarning.visibility = View.GONE

                binding.tvStatusTitle.text = getString(R.string.status_repeat_active)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.durga_red_bright))
                binding.tvStatusSubtitle.text = "Repeat SOS re-notified. Responders alerted. Enter secret code to cancel."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_red_primary))
            }

            IncidentState.ACKNOWLEDGED -> {
                binding.tvStatusTitle.text = getString(R.string.status_acknowledged)
                binding.tvStatusSubtitle.text = "Dispatcher acknowledged alert. Police unit coordinating."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
            }

            IncidentState.RESPONDING -> {
                binding.tvStatusTitle.text = getString(R.string.status_responding)
                binding.tvStatusSubtitle.text = "Police response unit en route to your coordinates."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_blue_accent))
            }

            IncidentState.RESOLVED -> {
                binding.layoutActiveIncidentActions.visibility = View.GONE
                binding.cardAreYouSafe.visibility = View.GONE

                binding.tvStatusTitle.text = getString(R.string.status_resolved)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.durga_green_safe))
                binding.tvStatusSubtitle.text = "Incident officially resolved by verified responder."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_green_safe))

                binding.root.postDelayed({
                    viewModel.stateMachine.processEvent(com.durga.emergency.emergency.IncidentEvent.ResetToIdle)
                }, 3000)
            }

            IncidentState.CANCELLED -> {
                binding.layoutActiveIncidentActions.visibility = View.GONE
                binding.cardAreYouSafe.visibility = View.GONE

                binding.tvStatusTitle.text = getString(R.string.status_cancelled)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.text_secondary))
                binding.tvStatusSubtitle.text = "Incident cancelled via PIN. User confirmed safe."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_green_safe))

                binding.root.postDelayed({
                    viewModel.stateMachine.processEvent(com.durga.emergency.emergency.IncidentEvent.ResetToIdle)
                }, 2000)
            }
        }
    }

    private fun showPinCancellationDialog() {
        if (!viewModel.stateMachine.isPinCancellationPermitted()) {
            Toast.makeText(
                this,
                "Cancellation locked: Exceeded maximum incorrect PIN attempts. Please wait.",
                Toast.LENGTH_LONG
            ).show()
            return
        }

        val input = EditText(this).apply {
            inputType = android.text.InputType.TYPE_CLASS_NUMBER or android.text.InputType.TYPE_NUMBER_VARIATION_PASSWORD
            hint = "4-digit secret security PIN"
            textAlignment = View.TEXT_ALIGNMENT_CENTER
            textSize = 20f
        }

        MaterialAlertDialogBuilder(this)
            .setTitle(getString(R.string.prompt_enter_pin))
            .setMessage("Enter your 4-digit PIN to cancel the active emergency incident.")
            .setView(input)
            .setPositiveButton(getString(R.string.btn_submit_pin)) { _, _ ->
                val enteredPin = input.text.toString().trim()
                if (enteredPin.isNotEmpty()) {
                    viewModel.submitCancellationPin(enteredPin)
                } else {
                    Toast.makeText(this, "PIN cannot be empty", Toast.LENGTH_SHORT).show()
                }
            }
            .setNegativeButton("Back", null)
            .show()
    }

    private fun showEditContactDialog() {
        val dialogView = LayoutInflater.from(this).inflate(R.layout.dialog_edit_contact, null)
        val etUser = dialogView.findViewById<EditText>(R.id.etUserName)
        val etContact = dialogView.findViewById<EditText>(R.id.etContactName)
        val etPhone = dialogView.findViewById<EditText>(R.id.etContactPhone)

        etUser.setText(viewModel.userName.value)
        etPhone.setText(viewModel.contactPhone.value)

        MaterialAlertDialogBuilder(this)
            .setTitle("Profile & Primary Contact")
            .setView(dialogView)
            .setPositiveButton("Save") { _, _ ->
                viewModel.saveContactInfo(
                    etUser.text.toString().trim(),
                    etContact.text.toString().trim(),
                    etPhone.text.toString().trim()
                )
                Toast.makeText(this, "Profile updated", Toast.LENGTH_SHORT).show()
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun requestAppPermissions() {
        val permissions = mutableListOf(
            Manifest.permission.ACCESS_FINE_LOCATION,
            Manifest.permission.ACCESS_COARSE_LOCATION,
            Manifest.permission.RECORD_AUDIO
        )
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            permissions.add(Manifest.permission.POST_NOTIFICATIONS)
        }
        permissionLauncher.launch(permissions.toTypedArray())
    }

    private fun handleNearbyProximityAlert(event: EmergencyEvent?) {
        if (event != null) {
            binding.cardProximityAlert.visibility = View.VISIBLE

            val distMeters = if (event.nearestPoliceStationDistanceKm != null) {
                (event.nearestPoliceStationDistanceKm * 1000).toInt()
            } else {
                50
            }
            binding.tvProximityDistanceBadge.text = "~${distMeters}m AWAY"
            binding.tvProximityVictimName.text = "🚨 ${event.userName} (${event.emergencyContactPhone})"
            binding.tvProximityAiStatus.text = "Acoustic AI: ${event.distressClassification} (${(event.distressScore * 100).toInt()}%)"

            // View Map navigation
            binding.btnProximityViewMap.setOnClickListener {
                val lat = event.latitude
                val lon = event.longitude
                if (lat != null && lon != null) {
                    val gmmIntentUri = Uri.parse("geo:$lat,$lon?q=$lat,$lon(Emergency+Distress+Location)")
                    val mapIntent = Intent(Intent.ACTION_VIEW, gmmIntentUri)
                    try {
                        startActivity(mapIntent)
                    } catch (e: Exception) {
                        val browserIntent = Intent(Intent.ACTION_VIEW, Uri.parse("https://www.google.com/maps/search/?api=1&query=$lat,$lon"))
                        startActivity(browserIntent)
                    }
                } else {
                    Toast.makeText(this, "Victim GPS coordinates pending or unavailable.", Toast.LENGTH_SHORT).show()
                }
            }

            // Call 112
            binding.btnProximityCall112.setOnClickListener {
                startActivity(Intent(Intent.ACTION_DIAL, Uri.parse("tel:112")))
            }

            // Trigger alarm sound and vibration
            startProximityAlarm()
        } else {
            binding.cardProximityAlert.visibility = View.GONE
            stopProximityAlarm()
        }
    }

    private fun startProximityAlarm() {
        try {
            if (alarmRingtone == null) {
                var alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_ALARM)
                if (alertUri == null) {
                    alertUri = RingtoneManager.getDefaultUri(RingtoneManager.TYPE_NOTIFICATION)
                }
                alarmRingtone = RingtoneManager.getRingtone(applicationContext, alertUri)
            }
            if (alarmRingtone?.isPlaying == false) {
                alarmRingtone?.play()
            }

            vibrator?.let { vib ->
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vib.vibrate(VibrationEffect.createWaveform(longArrayOf(0, 500, 250, 500, 250, 500), -1))
                } else {
                    @Suppress("DEPRECATION")
                    vib.vibrate(longArrayOf(0, 500, 250, 500, 250, 500), -1)
                }
            }
        } catch (e: Exception) {
            Log.w("MainActivity", "Proximity alarm note: ${e.message}")
        }
    }

    private fun stopProximityAlarm() {
        try {
            if (alarmRingtone?.isPlaying == true) {
                alarmRingtone?.stop()
            }
            vibrator?.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showAccountDialog() {
        val message = """
            👤 Name: ${prefs.userName}
            📱 Phone: ${prefs.userPhone}
            🆔 User ID: ${prefs.userId}
            🌐 Server: ${prefs.serverBaseUrl}
            🔒 PIN: PBKDF2 Hashed
            🛡️ Status: Protected Active Citizen
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("DURGA Account & Device Info")
            .setMessage(message)
            .setPositiveButton("📡 Send Internet Test Push") { _, _ ->
                showSendTestNotificationDialog()
            }
            .setNegativeButton("Log Out") { _, _ ->
                prefs.clearSession()
                val intent = Intent(this, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
            .setNeutralButton("Switch Account") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java).apply {
                    putExtra("EXTRA_FORCE_RELOGIN", true)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun showSendTestNotificationDialog() {
        val input = EditText(this).apply {
            hint = "Target phone (default: any nearby device)"
            setText("+918888888888")
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Send Verified Internet Test Push")
            .setMessage("This will trigger a real FCM test notification through the trusted backend to the recipient's phone.")
            .setView(input)
            .setPositiveButton("SEND PUSH") { _, _ ->
                val targetPhone = input.text.toString().trim()
                Toast.makeText(this, "Dispatching test push request to backend...", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch {
                    val result = viewModel.sendTestPushNotification(targetPhone = targetPhone.ifEmpty { null })
                    if (result.isSuccess) {
                        val resp = result.getOrThrow()
                        val alertMsg = if (resp.status == "FCM_SUBMISSION_ACCEPTED") {
                            "✅ FCM Push Accepted by Firebase!\nMessage ID: ${resp.messageId}\nRecipient: ${resp.recipientUserId}"
                        } else {
                            "ℹ️ Provider Status: ${resp.status}\nNote: ${resp.note ?: "Queued in backend dispatch"}\nRecipient: ${resp.recipientUserId ?: "Eligible Device"}"
                        }
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Test Push Result")
                            .setMessage(alertMsg)
                            .setPositiveButton("OK", null)
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    override fun onDestroy() {
        super.onDestroy()
        audioPlayer.stopAudio()
        stopProximityAlarm()
    }
}

