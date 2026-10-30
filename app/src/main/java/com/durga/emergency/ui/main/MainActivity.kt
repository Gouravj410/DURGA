package com.durga.emergency.ui.main

import android.Manifest
import android.content.Context
import android.content.Intent
import android.hardware.Sensor
import android.hardware.SensorManager
import android.media.AudioAttributes
import android.media.MediaPlayer
import android.net.ConnectivityManager
import android.net.Network
import android.net.NetworkCapabilities
import android.net.NetworkRequest
import android.net.Uri
import android.os.Build
import android.os.Bundle
import android.app.KeyguardManager
import android.os.CountDownTimer
import android.os.VibrationEffect
import android.os.Vibrator
import android.util.Log
import android.view.LayoutInflater
import android.view.View
import android.view.WindowManager
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
import com.durga.emergency.data.api.DurgaApiClient
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import com.google.android.material.dialog.MaterialAlertDialogBuilder

class MainActivity : AppCompatActivity() {

    private var lastHandledNotificationEventId: String? = null
    private var lastHandledNotificationTimestamp: Long = 0L

    private lateinit var binding: ActivityMainBinding
    private val viewModel: MainViewModel by viewModels()
    private lateinit var prefs: PreferencesManager
    private lateinit var gestureRepo: GestureRepository

    private var sensorManager: SensorManager? = null
    private var accelerometer: Sensor? = null
    private var customGestureDetector: CustomGestureDetector? = null
    private lateinit var audioPlayer: DurgaAudioPlayer

    private var proximityMediaPlayer: MediaPlayer? = null
    private var vibrator: Vibrator? = null
    private var networkCallback: ConnectivityManager.NetworkCallback? = null
    private var preAlarmTimer: CountDownTimer? = null
    private var lastAlarmedIncidentId: String? = null

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
            Toast.makeText(this, "Notification permission denied. Background alerts will not appear on lock screen.", Toast.LENGTH_LONG).show()
        }
    }

    override fun onCreate(savedInstanceState: Bundle?) {
        super.onCreate(savedInstanceState)

        prefs = PreferencesManager(this)
        if (!prefs.isLoggedIn) {
            val loginIntent = Intent(this, LoginActivity::class.java).apply {
                intent?.extras?.let { putExtras(it) }
            }
            startActivity(loginIntent)
            finish()
            return
        }

        binding = ActivityMainBinding.inflate(layoutInflater)
        setContentView(binding.root)

        // Wake screen and display pre-alarm interface if device is locked
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O_MR1) {
            setShowWhenLocked(true)
            setTurnScreenOn(true)
            val km = getSystemService(Context.KEYGUARD_SERVICE) as? KeyguardManager
            km?.requestDismissKeyguard(this, null)
        } else {
            @Suppress("DEPRECATION")
            window.addFlags(
                WindowManager.LayoutParams.FLAG_SHOW_WHEN_LOCKED or
                WindowManager.LayoutParams.FLAG_DISMISS_KEYGUARD or
                WindowManager.LayoutParams.FLAG_TURN_SCREEN_ON or
                WindowManager.LayoutParams.FLAG_KEEP_SCREEN_ON
            )
        }

        audioPlayer = DurgaAudioPlayer(this)
        vibrator = getSystemService(Context.VIBRATOR_SERVICE) as? Vibrator

        setupSensors()
        setupListeners()
        observeViewModel()
        requestAppPermissions()
        setupNetworkMonitoring()

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
        if (intent == null) return

        if (intent.action == DurgaSafetyService.ACTION_TRIGGER_SOS) {
            startPreAlarmCountdown(TriggerSource.PERSISTENT_NOTIFICATION_ACTION)
            return
        }

        // Backwards-compatible identifier resolution
        val eventId = intent.getStringExtra("EXTRA_EVENT_ID")
            ?: intent.getStringExtra("EXTRA_INCIDENT_ID")
            ?: intent.getStringExtra("eventId")
            ?: intent.getStringExtra("incidentId")
            ?: ""

        val notificationType = intent.getStringExtra("EXTRA_NOTIFICATION_TYPE")
            ?: intent.getStringExtra("notificationType")
            ?: intent.getStringExtra("type")
            ?: ""

        val isTestNotification = intent.getBooleanExtra("EXTRA_IS_TEST_NOTIFICATION", false)
            || notificationType == "DURGA_TEST"
            || eventId.startsWith("TEST-")

        val isEmergencyNotification = notificationType == "DURGA_EMERGENCY"
            || intent.hasExtra("EXTRA_INCIDENT_ID")
            || intent.hasExtra("EXTRA_EVENT_ID")
            || intent.hasExtra("incidentId")
            || intent.hasExtra("eventId")

        if (isTestNotification) {
            val now = System.currentTimeMillis()
            if (eventId.isNotEmpty() && eventId == lastHandledNotificationEventId && now - lastHandledNotificationTimestamp < 3000) {
                return
            }
            lastHandledNotificationEventId = eventId
            lastHandledNotificationTimestamp = now

            MaterialAlertDialogBuilder(this)
                .setTitle("Push Notification Verified")
                .setMessage("Successfully received and opened DURGA Internet Push Notification.\n\nIncident ID: ${eventId.ifEmpty { "TEST-FCM-VERIFIED" }}\nTransport: Firebase Cloud Messaging (FCM HTTP v1)")
                .setPositiveButton("OK", null)
                .show()
            return
        }

        if (isEmergencyNotification) {
            if (eventId.isEmpty()) {
                Log.w("MainActivity", "Emergency notification tapped but incidentId/eventId was missing")
                Toast.makeText(
                    this,
                    "Emergency notification received, but incident identifier was missing.",
                    Toast.LENGTH_LONG
                ).show()
                return
            }

            val now = System.currentTimeMillis()
            if (eventId == lastHandledNotificationEventId && now - lastHandledNotificationTimestamp < 3000) {
                return
            }
            lastHandledNotificationEventId = eventId
            lastHandledNotificationTimestamp = now

            // Extract telemetry extras safely
            val latStr = intent.getStringExtra("EXTRA_LATITUDE") ?: intent.getStringExtra("latitude")
            val lat = latStr?.toDoubleOrNull() ?: if (intent.hasExtra("EXTRA_LATITUDE")) intent.getDoubleExtra("EXTRA_LATITUDE", 0.0).takeIf { it != 0.0 } else null

            val lonStr = intent.getStringExtra("EXTRA_LONGITUDE") ?: intent.getStringExtra("longitude")
            val lon = lonStr?.toDoubleOrNull() ?: if (intent.hasExtra("EXTRA_LONGITUDE")) intent.getDoubleExtra("EXTRA_LONGITUDE", 0.0).takeIf { it != 0.0 } else null

            val accuracyStr = intent.getStringExtra("EXTRA_ACCURACY") ?: intent.getStringExtra("accuracy") ?: intent.getStringExtra("locationAccuracy")
            val accuracy = accuracyStr?.toFloatOrNull() ?: if (intent.hasExtra("EXTRA_ACCURACY")) intent.getFloatExtra("EXTRA_ACCURACY", 15.0f) else null

            val scoreStr = intent.getStringExtra("EXTRA_DISTRESS_SCORE") ?: intent.getStringExtra("distressScore")
            val distressScore = scoreStr?.toFloatOrNull() ?: if (intent.hasExtra("EXTRA_DISTRESS_SCORE")) intent.getFloatExtra("EXTRA_DISTRESS_SCORE", 0.95f) else 0.95f

            val classification = intent.getStringExtra("EXTRA_CLASSIFICATION")
                ?: intent.getStringExtra("distressClassification")
                ?: intent.getStringExtra("classification")
                ?: "Acoustic Distress Confirmed"

            val nearestStation = intent.getStringExtra("EXTRA_NEAREST_STATION")
                ?: intent.getStringExtra("nearestStation")
                ?: intent.getStringExtra("nearestPoliceStation")
                ?: "Nearest Police Station"

            val distStr = intent.getStringExtra("EXTRA_DISTANCE_KM") ?: intent.getStringExtra("distanceKm")
            val distanceKm = distStr?.toDoubleOrNull() ?: if (intent.hasExtra("EXTRA_DISTANCE_KM")) intent.getDoubleExtra("EXTRA_DISTANCE_KM", 0.05) else 0.05
            val status = intent.getStringExtra("EXTRA_STATUS") ?: intent.getStringExtra("status") ?: "ACTIVE"

            viewModel.onEmergencyNotificationTapped(
                eventId = eventId,
                latitude = lat,
                longitude = lon,
                accuracy = accuracy,
                distressScore = distressScore,
                classification = classification,
                nearestStation = nearestStation,
                distanceKm = distanceKm,
                status = status
            )

            Toast.makeText(
                this,
                "Emergency Incident #$eventId Loaded",
                Toast.LENGTH_SHORT
            ).show()
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
                    startPreAlarmCountdown(TriggerSource.IN_APP_SHAKE_GESTURE)
                }
            }
        )
    }

    override fun onResume() {
        super.onResume()
        if (::gestureRepo.isInitialized) {
            val primary = gestureRepo.getPrimaryGesture()
            customGestureDetector?.config = primary
            customGestureDetector?.reset()

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
        customGestureDetector?.reset()
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

        // Fallback Cancellation during initial countdown or Pre-Alarm grace
        binding.btnCancelEmergency.setOnClickListener {
            if (preAlarmTimer != null) {
                cancelPreAlarmGrace()
            } else {
                showPinCancellationDialog()
            }
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
            val currentId = viewModel.nearbyMeshAlertEvent.value?.eventId
            stopProximityAlarm()
            binding.cardProximityAlert.visibility = View.GONE
            viewModel.dismissProximityAlert(currentId)
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
                binding.tvStatusSubtitle.text = "Sensors armed: Acoustic AI guard & rapid gesture trigger active"
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_green_safe))

                binding.layoutCountdown.visibility = View.GONE
                binding.cardAreYouSafe.visibility = View.GONE
                binding.layoutActiveIncidentActions.visibility = View.GONE
                binding.btnSos.isEnabled = true
            }

            IncidentState.TRIGGERED -> {
                binding.tvStatusTitle.text = getString(R.string.status_triggered)
                binding.tvStatusSubtitle.text = "Initiating distress capture & acoustic verification..."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
            }

            IncidentState.RECORDING -> {
                binding.tvStatusTitle.text = getString(R.string.status_recording)
                binding.tvStatusSubtitle.text = "Capturing 5-second acoustic distress evidence..."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
            }

            IncidentState.ANALYZING -> {
                binding.tvStatusTitle.text = getString(R.string.status_analyzing)
                binding.tvStatusSubtitle.text = "Evaluating acoustic spectrum for threat indicators..."
            }

            IncidentState.VERIFYING -> {
                binding.tvStatusTitle.text = getString(R.string.status_verifying)
                binding.tvStatusSubtitle.text = "Acoustic score uncertain. Confirm safety or request immediate dispatch."
                binding.cardAreYouSafe.visibility = View.VISIBLE
                binding.layoutActiveIncidentActions.visibility = View.GONE
            }

            IncidentState.ESCALATING -> {
                binding.cardAreYouSafe.visibility = View.GONE
                binding.tvStatusTitle.text = getString(R.string.status_escalating)
                binding.tvStatusSubtitle.text = "Acquiring satellite GPS & dispatching alert to 1.0 km radius..."
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
                binding.tvStatusSubtitle.text = "Emergency broadcast active: Nearby citizens and police alerted."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_red_bright))
            }

            IncidentState.REPEAT_ACTIVE -> {
                binding.cardAreYouSafe.visibility = View.GONE
                binding.layoutActiveIncidentActions.visibility = View.VISIBLE
                binding.btnRepeatSos.visibility = View.VISIBLE
                binding.btnCancelWithPin.visibility = View.VISIBLE
                binding.tvRepeatSosLockWarning.visibility = View.GONE

                binding.tvStatusTitle.text = getString(R.string.status_repeat_active)
                binding.tvStatusTitle.setTextColor(ContextCompat.getColor(this, R.color.durga_red_bright))
                binding.tvStatusSubtitle.text = "High-priority repeat SOS broadcasted. Enter PIN to cancel."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_red_primary))
            }

            IncidentState.ACKNOWLEDGED -> {
                binding.tvStatusTitle.text = getString(R.string.status_acknowledged)
                binding.tvStatusSubtitle.text = "Dispatcher acknowledged alert. Police response unit coordinating."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_amber_warning))
            }

            IncidentState.RESPONDING -> {
                binding.tvStatusTitle.text = getString(R.string.status_responding)
                binding.tvStatusSubtitle.text = "Police response unit en route to your coordinates."
                binding.viewStatusDot.setBackgroundColor(ContextCompat.getColor(this, R.color.durga_blue_bright))
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
                binding.tvStatusSubtitle.text = "Incident cancelled via PIN verification. User safe."
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
            val victimLabel = if (event.emergencyContactPhone.isNotBlank() && event.emergencyContactPhone != "PROXIMITY_ALERT") {
                "Distress Signal: ${event.userName} (${event.emergencyContactPhone})"
            } else {
                "Distress Signal: ${event.userName}"
            }
            binding.tvProximityVictimName.text = victimLabel
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

            // Trigger alarm sound and vibration (only once per incident to prevent loops)
            if (event.eventId != lastAlarmedIncidentId) {
                lastAlarmedIncidentId = event.eventId
                startProximityAlarm()
            }
        } else {
            binding.cardProximityAlert.visibility = View.GONE
            stopProximityAlarm()
        }
    }

    private fun startPreAlarmCountdown(source: TriggerSource) {
        val currentState = viewModel.incidentState.value ?: IncidentState.IDLE
        if (currentState != IncidentState.IDLE && currentState != IncidentState.CANCELLED && currentState != IncidentState.RESOLVED) {
            // Already in active distress lifecycle
            viewModel.triggerEmergency(source)
            return
        }

        preAlarmTimer?.cancel()
        binding.layoutCountdown.visibility = View.VISIBLE
        binding.tvCountdownTitle.text = "EMERGENCY GESTURE DETECTED"
        binding.tvCountdownSub.text = "Dispatching emergency alerts in 5 seconds... Tap Cancel if accidental."
        binding.tvCountdownNumber.text = "5"

        // Warning haptic feedback
        if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
            vibrator?.vibrate(VibrationEffect.createOneShot(200, VibrationEffect.DEFAULT_AMPLITUDE))
        } else {
            @Suppress("DEPRECATION")
            vibrator?.vibrate(200)
        }

        preAlarmTimer = object : CountDownTimer(5000L, 1000L) {
            override fun onTick(millisUntilFinished: Long) {
                val sec = (millisUntilFinished / 1000L) + 1
                binding.tvCountdownNumber.text = "$sec"
                if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.O) {
                    vibrator?.vibrate(VibrationEffect.createOneShot(100, VibrationEffect.DEFAULT_AMPLITUDE))
                } else {
                    @Suppress("DEPRECATION")
                    vibrator?.vibrate(100)
                }
            }

            override fun onFinish() {
                binding.layoutCountdown.visibility = View.GONE
                preAlarmTimer = null
                Log.i("MainActivity", "Pre-alarm grace window elapsed. Initiating emergency dispatch from $source")
                viewModel.triggerEmergency(source)
            }
        }.start()
    }

    private fun cancelPreAlarmGrace() {
        if (preAlarmTimer != null) {
            preAlarmTimer?.cancel()
            preAlarmTimer = null
            binding.layoutCountdown.visibility = View.GONE
            customGestureDetector?.reset()
            Toast.makeText(this, "Emergency trigger cancelled. False alarm prevented.", Toast.LENGTH_SHORT).show()
        }
    }

    private fun setupNetworkMonitoring() {
        val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager ?: return
        val request = NetworkRequest.Builder()
            .addCapability(NetworkCapabilities.NET_CAPABILITY_INTERNET)
            .build()

        networkCallback = object : ConnectivityManager.NetworkCallback() {
            override fun onAvailable(network: Network) {
                runOnUiThread {
                    Log.i("MainActivity", "Internet connection restored. Synchronizing backend session...")
                    viewModel.ensureBackendSession()
                    com.durga.emergency.utils.FcmTokenHelper.syncRegistrationToken(this@MainActivity)
                    viewModel.startProximityMonitoring()
                }
            }

            override fun onLost(network: Network) {
                runOnUiThread {
                    Log.w("MainActivity", "Internet connection lost.")
                }
            }
        }
        try {
            cm.registerNetworkCallback(request, networkCallback!!)
        } catch (e: Exception) {
            Log.w("MainActivity", "Network monitoring registration note: ${e.message}")
        }
    }

    private fun startProximityAlarm() {
        try {
            if (proximityMediaPlayer == null) {
                proximityMediaPlayer = MediaPlayer.create(applicationContext, R.raw.durga_emergency_siren).apply {
                    isLooping = true
                    if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.LOLLIPOP) {
                        setAudioAttributes(
                            AudioAttributes.Builder()
                                .setContentType(AudioAttributes.CONTENT_TYPE_SONIFICATION)
                                .setUsage(AudioAttributes.USAGE_ALARM)
                                .build()
                        )
                    }
                }
            }
            if (proximityMediaPlayer?.isPlaying == false) {
                proximityMediaPlayer?.start()
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
            Log.w("MainActivity", "Proximity siren note: ${e.message}")
        }
    }

    private fun stopProximityAlarm() {
        try {
            if (proximityMediaPlayer?.isPlaying == true) {
                proximityMediaPlayer?.stop()
            }
            proximityMediaPlayer?.release()
            proximityMediaPlayer = null
            vibrator?.cancel()
        } catch (e: Exception) {
            // ignore
        }
    }

    private fun showAccountDialog() {
        val message = """
            Citizen Identity: ${prefs.userName}
            Registered Mobile: ${prefs.userPhone}
            Citizen ID: ${prefs.userId}
            Central Gateway: ${prefs.serverBaseUrl}
            Security Verification: 4-Digit PBKDF2 Hashed PIN
            Protection Status: Active Monitored Citizen
        """.trimIndent()

        MaterialAlertDialogBuilder(this)
            .setTitle("Citizen Account & Security Profile")
            .setMessage(message)
            .setPositiveButton("Verify Push Delivery") { _, _ ->
                showSendTestNotificationDialog()
            }
            .setNegativeButton("Sign Out") { _, _ ->
                performLogout()
            }
            .setNeutralButton("Switch Identity") { _, _ ->
                val intent = Intent(this, LoginActivity::class.java).apply {
                    putExtra("EXTRA_FORCE_RELOGIN", true)
                }
                startActivity(intent)
            }
            .show()
    }

    private fun showSendTestNotificationDialog() {
        val input = EditText(this).apply {
            hint = "Target phone (default: nearby recipient)"
            setText("+918888888888")
            inputType = android.text.InputType.TYPE_CLASS_PHONE
        }

        MaterialAlertDialogBuilder(this)
            .setTitle("Dispatch Verification Push")
            .setMessage("Transmit an authenticated diagnostic test alert through the secure gateway to verify push delivery.")
            .setView(input)
            .setPositiveButton("Send Diagnostic") { _, _ ->
                val targetPhone = input.text.toString().trim()
                Toast.makeText(this, "Dispatching test push request...", Toast.LENGTH_SHORT).show()

                lifecycleScope.launch {
                    val result = viewModel.sendTestPushNotification(targetPhone = targetPhone.ifEmpty { null })
                    if (result.isSuccess) {
                        val resp = result.getOrThrow()
                        val alertMsg = if (resp.status == "FCM_SUBMISSION_ACCEPTED") {
                            "Push notification accepted by Firebase Cloud Messaging.\n\nMessage ID: ${resp.messageId}\nRecipient: ${resp.recipientUserId}"
                        } else {
                            "Provider Status: ${resp.status}\nNote: ${resp.note ?: "Queued in backend dispatch"}\nRecipient: ${resp.recipientUserId ?: "Eligible Device"}"
                        }
                        MaterialAlertDialogBuilder(this@MainActivity)
                            .setTitle("Delivery Status")
                            .setMessage(alertMsg)
                            .setPositiveButton("Dismiss", null)
                            .show()
                    } else {
                        Toast.makeText(this@MainActivity, "Failed: ${result.exceptionOrNull()?.message}", Toast.LENGTH_LONG).show()
                    }
                }
            }
            .setNegativeButton("Cancel", null)
            .show()
    }

    private fun performLogout() {
        val deviceId = prefs.deviceId
        val fcmToken = prefs.fcmToken
        val currentAuthToken = prefs.authToken

        lifecycleScope.launch {
            try {
                if (!currentAuthToken.isNullOrEmpty()) {
                    withContext(Dispatchers.IO) {
                        val apiClient = DurgaApiClient.getInstance(applicationContext)
                        apiClient.revokeDeviceToken(deviceId, fcmToken, currentAuthToken)
                        Log.i("MainActivity", "Backend device token revoked successfully on logout")
                    }
                }
            } catch (e: Exception) {
                Log.w("MainActivity", "Device token revocation on backend note: ${e.message}")
            } finally {
                prefs.clearSession()
                val intent = Intent(this@MainActivity, LoginActivity::class.java).apply {
                    flags = Intent.FLAG_ACTIVITY_NEW_TASK or Intent.FLAG_ACTIVITY_CLEAR_TASK
                }
                startActivity(intent)
                finish()
            }
        }
    }

    override fun onDestroy() {
        super.onDestroy()
        preAlarmTimer?.cancel()
        preAlarmTimer = null
        audioPlayer.stopAudio()
        stopProximityAlarm()
        try {
            networkCallback?.let {
                val cm = getSystemService(Context.CONNECTIVITY_SERVICE) as? ConnectivityManager
                cm?.unregisterNetworkCallback(it)
            }
        } catch (e: Exception) {
            // ignore
        }
    }
}

