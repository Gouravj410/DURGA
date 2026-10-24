package com.durga.emergency.ui.main

import android.app.Application
import android.os.CountDownTimer
import android.util.Log
import androidx.lifecycle.AndroidViewModel
import androidx.lifecycle.LiveData
import androidx.lifecycle.MutableLiveData
import androidx.lifecycle.viewModelScope
import com.durga.emergency.ai.DistressAnalyzer
import com.durga.emergency.ai.DurgaScoringDistressAnalyzer
import com.durga.emergency.audio.AudioRecorder
import com.durga.emergency.audio.DurgaPcmAudioRecorder
import com.durga.emergency.data.LocalPoliceStationRepository
import com.durga.emergency.data.PoliceStationRepository
import com.durga.emergency.data.PreferencesManager
import com.durga.emergency.data.api.DurgaApiClient
import com.durga.emergency.data.model.EmergencyEvent
import com.durga.emergency.emergency.IncidentEvent
import com.durga.emergency.emergency.IncidentState
import com.durga.emergency.emergency.IncidentStateMachine
import com.durga.emergency.emergency.TriggerSource
import com.durga.emergency.emergency.UserRole
import com.durga.emergency.location.DurgaLocation
import com.durga.emergency.location.FusedLocationProvider
import com.durga.emergency.location.LocationProvider
import com.durga.emergency.mesh.DirectMeshManager
import kotlinx.coroutines.Job
import kotlinx.coroutines.delay
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import java.io.File

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DURGA_VIEW_MODEL"
        private const val SAFETY_PROMPT_TIMEOUT_SECONDS = 10
    }

    private val prefs = PreferencesManager(application)
    private val apiClient = DurgaApiClient.getInstance(application)
    private val directMesh = DirectMeshManager.getInstance(application)
    private val policeRepo: PoliceStationRepository = LocalPoliceStationRepository(application)
    private val locationProvider: LocationProvider = FusedLocationProvider(application)
    private val audioRecorder: AudioRecorder = DurgaPcmAudioRecorder(application)
    private val distressAnalyzer: DistressAnalyzer = DurgaScoringDistressAnalyzer()

    private val _incidentState: MutableLiveData<IncidentState> = MutableLiveData(IncidentState.IDLE)
    val incidentState: LiveData<IncidentState> = _incidentState

    // Incident State Machine
    val stateMachine: IncidentStateMachine = IncidentStateMachine { from, to, event ->
        Log.d(TAG, "[FSM] State transition: $from -> $to (Triggered by ${event.javaClass.simpleName})")
        _incidentState.postValue(to)
    }

    private val _currentRole = MutableLiveData(prefs.selectedRole)
    val currentRole: LiveData<UserRole> = _currentRole

    private val _safetyPromptSeconds = MutableLiveData(SAFETY_PROMPT_TIMEOUT_SECONDS)
    val safetyPromptSeconds: LiveData<Int> = _safetyPromptSeconds

    private val _recordingSecondsRemaining = MutableLiveData(5)
    val recordingSecondsRemaining: LiveData<Int> = _recordingSecondsRemaining

    private val _activeEmergencyEvent = MutableLiveData<EmergencyEvent?>()
    val activeEmergencyEvent: LiveData<EmergencyEvent?> = _activeEmergencyEvent

    private val _locationInfo = MutableLiveData("Standby")
    val locationInfo: LiveData<String> = _locationInfo

    private val _aiDistressInfo = MutableLiveData("Model Standby")
    val aiDistressInfo: LiveData<String> = _aiDistressInfo

    private val _nearestStationInfo = MutableLiveData("Scanning...")
    val nearestStationInfo: LiveData<String> = _nearestStationInfo

    private val _userName = MutableLiveData(prefs.userName)
    val userName: LiveData<String> = _userName

    private val _contactPhone = MutableLiveData(prefs.contactPhone)
    val contactPhone: LiveData<String> = _contactPhone

    private val _pinAttemptsLeft = MutableLiveData(IncidentStateMachine.MAX_PIN_ATTEMPTS)
    val pinAttemptsLeft: LiveData<Int> = _pinAttemptsLeft

    private val _pinErrorMessage = MutableLiveData<String?>()
    val pinErrorMessage: LiveData<String?> = _pinErrorMessage

    // Nearby Community Mesh Proximity Alerts (Alerts Phone B when Phone A triggers SOS)
    private val _nearbyMeshAlertEvent = MutableLiveData<EmergencyEvent?>()
    val nearbyMeshAlertEvent: LiveData<EmergencyEvent?> = _nearbyMeshAlertEvent

    private var safetyTimer: CountDownTimer? = null
    private var locationStreamingJob: Job? = null
    private var proximityMonitoringJob: Job? = null

    init {
        // Authenticate development session with the backend (optional) and register device
        ensureBackendSession()
        startProximityMonitoring()
        startDirectMeshListener()
    }

    private fun startDirectMeshListener() {
        directMesh.startListening(
            myUserId = prefs.userId,
            onAlert = { event ->
                Log.i(TAG, "🚨 [DIRECT MESH ALERT RECEIVED] Victim: ${event.userName}, Phone: ${event.emergencyContactPhone}")
                _nearbyMeshAlertEvent.postValue(event)
            },
            onCancel = { cancelledUserId ->
                Log.i(TAG, "Direct mesh cancellation received from user $cancelledUserId")
                if (_nearbyMeshAlertEvent.value?.userId == cancelledUserId) {
                    _nearbyMeshAlertEvent.postValue(null)
                }
            }
        )
    }

    private fun ensureBackendSession() {
        viewModelScope.launch {
            try {
                withTimeoutOrNull(2500) {
                    val phone = prefs.userPhone
                    val name = prefs.userName

                    // Request test OTP and verify
                    val otpResult = apiClient.requestOtp(phone)
                    val testOtp = otpResult.getOrNull() ?: "123456"

                    val authResult = apiClient.verifyOtp(phone, testOtp, name, "CITIZEN")
                    if (authResult.isSuccess) {
                        val auth = authResult.getOrThrow()
                        prefs.userId = auth.userId
                        prefs.authToken = auth.token
                        Log.d(TAG, "Backend authenticated successfully as ${auth.userId} (${auth.name})")

                        // Acquire initial GPS location
                        val loc = locationProvider.getCurrentLocation()
                        apiClient.registerDevice(
                            fcmToken = "DURGA_DEVICE_TOKEN_${phone.takeLast(4)}",
                            latitude = loc?.latitude,
                            longitude = loc?.longitude,
                            accuracy = loc?.accuracy
                        )
                    } else {
                        Log.w(TAG, "Backend authentication note: ${authResult.exceptionOrNull()?.message}")
                    }
                }
            } catch (e: Exception) {
                Log.w(TAG, "Backend connection note: Operating in 100% autonomous mesh mode (${e.message})")
            }
        }
    }

    /**
     * Continuous 2.5-second polling loop against the central backend.
     * When any phone within 1.0 km radius triggers SOS, this immediately alerts this device.
     */
    fun startProximityMonitoring() {
        proximityMonitoringJob?.cancel()
        proximityMonitoringJob = viewModelScope.launch {
            while (true) {
                try {
                    // Only monitor if this phone is not currently in its own active emergency
                    val state = stateMachine.currentState
                    if (state == IncidentState.IDLE ||
                        state == IncidentState.CANCELLED ||
                        state == IncidentState.RESOLVED) {

                        val loc = locationProvider.getCurrentLocation()
                        val lat = loc?.latitude ?: 18.5204
                        val lon = loc?.longitude ?: 73.8567
                        val myUserId = prefs.userId

                        val result = apiClient.getNearbyIncidents(
                            latitude = lat,
                            longitude = lon,
                            radiusKm = 1.0,
                            excludeUserId = myUserId
                        )

                        if (result.isSuccess) {
                            val incidents = result.getOrNull() ?: emptyList()
                            val activeIncident = incidents.firstOrNull {
                                it.status in listOf("ACTIVE", "REPEAT_ACTIVE", "ACKNOWLEDGED", "RESPONDING")
                            }

                            if (activeIncident != null) {
                                if (_nearbyMeshAlertEvent.value?.eventId != activeIncident.eventId) {
                                    Log.i(TAG, "🚨 [PROXIMITY ALERT DETECTED] Incident: ${activeIncident.eventId} by ${activeIncident.userName}")
                                }
                                _nearbyMeshAlertEvent.postValue(activeIncident)
                            } else {
                                if (_nearbyMeshAlertEvent.value != null) {
                                    Log.i(TAG, "Nearby incident cleared / resolved on central backend.")
                                    _nearbyMeshAlertEvent.postValue(null)
                                }
                            }
                        }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Proximity monitoring loop note: ${e.message}")
                }
                delay(2500)
            }
        }
    }

    fun dismissProximityAlert() {
        _nearbyMeshAlertEvent.postValue(null)
    }

    /**
     * Triggered by in-app button, persistent notification, or 3-shake gesture.
     */
    fun triggerEmergency(source: TriggerSource = TriggerSource.MANUAL_SOS_BUTTON) {
        when (stateMachine.currentState) {
            IncidentState.IDLE, IncidentState.CANCELLED, IncidentState.RESOLVED -> {
                Log.d(TAG, "SOS Triggered from source: $source. Starting audio capture pipeline.")
                stateMachine.processEvent(IncidentEvent.TriggerSos(source))
                startAudioAndInferencePipeline(source)
            }
            IncidentState.ACTIVE, IncidentState.ACKNOWLEDGED, IncidentState.RESPONDING -> {
                Log.w(TAG, "Repeat SOS triggered during active incident! Locking cancellation.")
                triggerRepeatSos()
            }
            else -> {
                Log.d(TAG, "SOS trigger ignored in current state: ${stateMachine.currentState}")
            }
        }
    }

    /**
     * Bypasses AI analysis, locks ordinary cancellation, and escalates to REPEAT_ACTIVE.
     */
    fun triggerRepeatSos() {
        stateMachine.processEvent(IncidentEvent.RepeatSosTriggered)
        val eventId = stateMachine.currentIncidentId
        if (eventId != null) {
            viewModelScope.launch {
                apiClient.repeatSos(eventId)
            }
        }
    }

    private fun startAudioAndInferencePipeline(source: TriggerSource) {
        viewModelScope.launch {
            // STEP 1: Capture 5-second PCM Audio
            stateMachine.processEvent(IncidentEvent.TriggerSos(source)) // Ensure in TRIGGERED
            _recordingSecondsRemaining.value = 5

            val audioFile: File? = audioRecorder.recordAudio(durationMillis = 5000L) { remaining ->
                _recordingSecondsRemaining.postValue(remaining)
            }

            if (audioFile != null) {
                stateMachine.processEvent(IncidentEvent.AudioRecordCompleted(audioFile))
            } else {
                stateMachine.processEvent(IncidentEvent.AudioRecordFailed("Microphone busy or permission denied"))
            }

            // STEP 2: On-Device Acoustic Analysis
            val distressResult = distressAnalyzer.analyzeAudioFile(audioFile ?: File(""))
            _aiDistressInfo.value = "${distressResult.classification} (Score: ${(distressResult.score * 100).toInt()}%)"

            val isDistressDetected = distressResult.score >= 0.70f
            stateMachine.processEvent(
                IncidentEvent.DistressAnalysisCompleted(
                    isDistressDetected = isDistressDetected,
                    score = distressResult.score,
                    confidence = distressResult.confidence
                )
            )

            if (isDistressDetected) {
                // High distress -> Escalating directly
                executeEscalation(audioFile, distressResult.score, distressResult.classification, source)
            } else {
                // Low or uncertain distress -> Display "Are you safe?" prompt
                startSafetyPromptTimer(audioFile, distressResult.score, distressResult.classification, source)
            }
        }
    }

    private fun startSafetyPromptTimer(
        audioFile: File?,
        distressScore: Float,
        distressClass: String,
        source: TriggerSource
    ) {
        _safetyPromptSeconds.value = SAFETY_PROMPT_TIMEOUT_SECONDS
        safetyTimer?.cancel()
        safetyTimer = object : CountDownTimer((SAFETY_PROMPT_TIMEOUT_SECONDS * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                _safetyPromptSeconds.value = (millisUntilFinished / 1000).toInt() + 1
            }

            override fun onFinish() {
                _safetyPromptSeconds.value = 0
                // Rule 7: Do NOT escalate solely because safety prompt timed out!
                Log.i(TAG, "Safety prompt timed out. User did not confirm distress. Returning to IDLE.")
                stateMachine.processEvent(IncidentEvent.SafetyPromptTimedOut)
            }
        }.start()
    }

    /**
     * User confirms "I AM SAFE" on the prompt.
     */
    fun confirmUserSafe() {
        safetyTimer?.cancel()
        stateMachine.processEvent(IncidentEvent.UserConfirmedSafe)
        _locationInfo.value = "Standby"
        directMesh.broadcastEmergencyCancellation(prefs.userId, prefs.userName)
    }

    /**
     * User explicitly taps "I NEED HELP" on the prompt.
     */
    fun requestAssistanceManually(source: TriggerSource = TriggerSource.MANUAL_SOS_BUTTON) {
        safetyTimer?.cancel()
        stateMachine.processEvent(IncidentEvent.UserRequestedAssistance)
        viewModelScope.launch {
            executeEscalation(null, 1.0f, "Manual User Escalation Verified", source)
        }
    }

    private suspend fun executeEscalation(
        audioFile: File?,
        distressScore: Float,
        distressClass: String,
        source: TriggerSource
    ) {
        val location: DurgaLocation? = locationProvider.getCurrentLocation()
        val lat = location?.latitude ?: 18.5204
        val lon = location?.longitude ?: 73.8567
        val accuracy = location?.accuracy ?: 12.0f

        _locationInfo.value = String.format("%.4f, %.4f (±%.1fm)", lat, lon, accuracy)

        // Haversine nearest police station lookup
        val (nearestStation, distanceKm) = policeRepo.findNearestStation(lat, lon)
        val stationName = nearestStation?.name ?: "Central Police Station"
        _nearestStationInfo.value = "$stationName (~${String.format("%.2f", distanceKm ?: 0.0)} km)"

        // Call Backend API to create incident (optional cloud sync)
        val result = apiClient.createIncident(
            latitude = lat,
            longitude = lon,
            accuracy = accuracy,
            triggerSource = source.name,
            distressScore = distressScore,
            distressClass = distressClass
        )

        val incidentId = result.getOrNull()?.incidentId ?: ("INC-" + System.currentTimeMillis().toString().takeLast(6))

        // 1. Direct Phone-to-Phone Emergency Mesh Broadcast (ZERO API / ZERO SERVER)
        directMesh.startEmergencyBroadcast(
            incidentId = incidentId,
            userId = prefs.userId,
            userName = prefs.userName,
            userPhone = prefs.userPhone,
            latitude = lat,
            longitude = lon,
            distressScore = distressScore,
            distressClass = distressClass
        )

        // Upload audio evidence if available
        if (audioFile != null && audioFile.exists()) {
            apiClient.uploadAudio(incidentId, audioFile)
        }

        val event = EmergencyEvent(
            eventId = incidentId,
            userId = prefs.userId,
            userName = _userName.value ?: "Citizen A",
            timestamp = System.currentTimeMillis(),
            latitude = lat,
            longitude = lon,
            locationAccuracy = accuracy,
            distressScore = distressScore,
            distressClassification = distressClass,
            emergencyLevel = if (distressScore >= 0.7f) "HIGH" else "MEDIUM",
            audioUrl = "",
            emergencyContactPhone = _contactPhone.value ?: "+91-9876543210",
            nearestPoliceStationName = stationName,
            nearestPoliceStationDistanceKm = distanceKm,
            status = "ACTIVE"
        )

        _activeEmergencyEvent.value = event
        stateMachine.processEvent(IncidentEvent.IncidentEscalated(incidentId))

        // Start continuous live location streaming
        startLocationStreaming(incidentId)
    }

    private fun startLocationStreaming(incidentId: String) {
        locationStreamingJob?.cancel()
        locationStreamingJob = viewModelScope.launch {
            while (stateMachine.currentState == IncidentState.ACTIVE || stateMachine.currentState == IncidentState.REPEAT_ACTIVE) {
                delay(4000)
                val loc = locationProvider.getCurrentLocation()
                if (loc != null) {
                    _locationInfo.postValue(String.format("%.4f, %.4f (±%.1fm)", loc.latitude, loc.longitude, loc.accuracy))
                    apiClient.updateLocation(incidentId, loc.latitude, loc.longitude, loc.accuracy)
                }
            }
        }
    }

    /**
     * Submit PIN for cancellation.
     */
    fun submitCancellationPin(enteredPin: String) {
        if (!stateMachine.isPinCancellationPermitted()) {
            _pinErrorMessage.value = "Cancellation locked: Repeat SOS lockdown requires authorized responder."
            return
        }

        val storedPin = prefs.securityPin
        val isCorrect = enteredPin.trim() == storedPin.trim()

        if (isCorrect) {
            val incidentId = stateMachine.currentIncidentId
            if (incidentId != null) {
                viewModelScope.launch {
                    apiClient.cancelIncident(incidentId, enteredPin)
                }
            }
            // Broadcast cancellation to all adjacent phones over Direct Mesh
            directMesh.broadcastEmergencyCancellation(prefs.userId, prefs.userName)

            stateMachine.recordPinAttempt(true)
            locationStreamingJob?.cancel()
            _pinErrorMessage.value = null
            _activeEmergencyEvent.value = null
            Log.d(TAG, "Incident cancelled successfully with verified PIN.")
        } else {
            val accepted = stateMachine.recordPinAttempt(false)
            _pinAttemptsLeft.value = stateMachine.pinAttemptsRemaining
            if (stateMachine.isPinLocked) {
                _pinErrorMessage.value = "Too many incorrect attempts. Cancellation locked."
            } else {
                _pinErrorMessage.value = "Incorrect PIN. Attempts remaining: ${stateMachine.pinAttemptsRemaining}"
            }
        }
    }

    /**
     * Diagnostic Test Helper: Simulates receiving a Proximity Alert on this device
     * so you can immediately see and hear how adjacent alerting behaves even with 1 phone!
     */
    fun simulateTestProximityAlert() {
        val testEvent = EmergencyEvent(
            eventId = "INC-TEST-" + System.currentTimeMillis().toString().takeLast(4),
            userId = "USER-TEST-ADJACENT",
            userName = "Adjacent Citizen (Phone A)",
            timestamp = System.currentTimeMillis(),
            latitude = 18.5204,
            longitude = 73.8567,
            locationAccuracy = 5.0f,
            distressScore = 0.94f,
            distressClassification = "SCREAM_HIGH_DISTRESS",
            emergencyLevel = "HIGH",
            audioUrl = "",
            emergencyContactPhone = "+919876543210",
            nearestPoliceStationName = "Nearby Emergency Unit",
            nearestPoliceStationDistanceKm = 0.05,
            status = "ACTIVE"
        )
        _nearbyMeshAlertEvent.postValue(testEvent)
    }

    fun setSecurityPin(pin: String) {
        prefs.securityPin = pin
        viewModelScope.launch {
            apiClient.setUserPin(pin)
        }
    }

    fun setRole(role: UserRole) {
        prefs.selectedRole = role
        _currentRole.value = role
    }

    fun saveContactInfo(user: String, contact: String, phone: String) {
        prefs.userName = user
        prefs.contactName = contact
        prefs.contactPhone = phone
        _userName.value = user
        _contactPhone.value = phone
    }

    override fun onCleared() {
        super.onCleared()
        safetyTimer?.cancel()
        locationStreamingJob?.cancel()
        proximityMonitoringJob?.cancel()
        directMesh.stop()
    }
}
