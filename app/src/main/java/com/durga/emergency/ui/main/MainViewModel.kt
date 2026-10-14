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
import com.durga.emergency.data.EmergencyRepository
import com.durga.emergency.data.FirestoreEmergencyRepository
import com.durga.emergency.data.LocalPoliceStationRepository
import com.durga.emergency.data.PoliceStationRepository
import com.durga.emergency.data.PreferencesManager
import com.durga.emergency.data.model.EmergencyEvent
import com.durga.emergency.emergency.EmergencyState
import com.durga.emergency.emergency.UserRole
import com.durga.emergency.location.DurgaLocation
import com.durga.emergency.location.FusedLocationProvider
import com.durga.emergency.location.LocationProvider
import kotlinx.coroutines.flow.collectLatest
import kotlinx.coroutines.launch
import java.io.File
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DURGA"
        private const val CANCEL_WINDOW_SECONDS = 5
    }

    private val prefs = PreferencesManager(application)
    private val firestoreRepo: FirestoreEmergencyRepository = FirestoreEmergencyRepository.getInstance(application)
    private val emergencyRepo: EmergencyRepository = firestoreRepo
    private val policeRepo: PoliceStationRepository = LocalPoliceStationRepository(application)
    private val locationProvider: LocationProvider = FusedLocationProvider(application)
    private val audioRecorder: AudioRecorder = DurgaPcmAudioRecorder(application)
    private val distressAnalyzer: DistressAnalyzer = DurgaScoringDistressAnalyzer()

    private val _currentRole = MutableLiveData(prefs.selectedRole)
    val currentRole: LiveData<UserRole> = _currentRole

    private val _emergencyState = MutableLiveData(EmergencyState.IDLE)
    val emergencyState: LiveData<EmergencyState> = _emergencyState

    private val _countdownSeconds = MutableLiveData(CANCEL_WINDOW_SECONDS)
    val countdownSeconds: LiveData<Int> = _countdownSeconds

    private val _recordingSecondsRemaining = MutableLiveData(5)
    val recordingSecondsRemaining: LiveData<Int> = _recordingSecondsRemaining

    private val _activeEmergencyEvent = MutableLiveData<EmergencyEvent?>()
    val activeEmergencyEvent: LiveData<EmergencyEvent?> = _activeEmergencyEvent

    private val _allEmergencyEvents = MutableLiveData<List<EmergencyEvent>>(emptyList())
    val allEmergencyEvents: LiveData<List<EmergencyEvent>> = _allEmergencyEvents

    private val _locationInfo = MutableLiveData("Standby")
    val locationInfo: LiveData<String> = _locationInfo

    private val _aiDistressInfo = MutableLiveData("Model Standby")
    val aiDistressInfo: LiveData<String> = _aiDistressInfo

    private val _userName = MutableLiveData(prefs.userName)
    val userName: LiveData<String> = _userName

    private val _contactName = MutableLiveData(prefs.contactName)
    val contactName: LiveData<String> = _contactName

    private val _contactPhone = MutableLiveData(prefs.contactPhone)
    val contactPhone: LiveData<String> = _contactPhone

    val isFirstLaunch: Boolean
        get() = prefs.isFirstLaunch

    fun setFirstLaunchCompleted() {
        prefs.isFirstLaunch = false
    }

    private var countdownTimer: CountDownTimer? = null

    private val _nearbyMeshAlertEvent = MutableLiveData<EmergencyEvent?>()
    val nearbyMeshAlertEvent: LiveData<EmergencyEvent?> = _nearbyMeshAlertEvent

    init {
        // Observe real-time repository events (Firestore + local fallback)
        viewModelScope.launch {
            emergencyRepo.activeEvents.collectLatest { events ->
                _allEmergencyEvents.value = events
                val latest = events.firstOrNull()
                _activeEmergencyEvent.value = latest

                // If an incoming event is active from another citizen in the mesh, alert the user
                if (latest != null && latest.status == "ACTIVE" && latest.userId != "USER-001") {
                    _nearbyMeshAlertEvent.value = latest
                }
            }
        }
    }

    /**
     * Simulates an incoming proximity mesh emergency from another citizen nearby.
     */
    fun simulateNearbyMeshAlert() {
        val currentLat = _activeEmergencyEvent.value?.latitude ?: 18.5204
        val currentLon = _activeEmergencyEvent.value?.longitude ?: 73.8567
        val nearbyEvent = EmergencyEvent(
            eventId = "MESH-" + UUID.randomUUID().toString().substring(0, 6).uppercase(),
            userId = "CITIZEN-NEARBY-77",
            userName = "Priya Deshmukh (Nearby Citizen)",
            timestamp = System.currentTimeMillis(),
            latitude = currentLat + 0.0031, // ~350 meters away
            longitude = currentLon + 0.0022,
            locationAccuracy = 10.0f,
            distressScore = 0.91f,
            distressClassification = "High Distress (High-Intensity Screaming detected)",
            emergencyLevel = "CRITICAL",
            audioUrl = _activeEmergencyEvent.value?.audioUrl ?: "",
            emergencyContactPhone = "+91-9876543210",
            nearestPoliceStationName = "Central Police Station",
            nearestPoliceStationDistanceKm = 1.15,
            status = "ACTIVE"
        )
        _nearbyMeshAlertEvent.value = nearbyEvent
        viewModelScope.launch {
            emergencyRepo.publishEmergencyEvent(nearbyEvent)
        }
        Log.d(TAG, "[DURGA MESH] Simulated incoming nearby citizen emergency: ${nearbyEvent.eventId}")
    }

    fun dismissNearbyMeshAlert() {
        _nearbyMeshAlertEvent.value = null
    }

    fun setRole(role: UserRole) {
        prefs.selectedRole = role
        _currentRole.value = role
        Log.d(TAG, "[DURGA] Active role set to: $role")
    }

    fun saveContactInfo(user: String, contact: String, phone: String) {
        prefs.userName = user
        prefs.contactName = contact
        prefs.contactPhone = phone
        _userName.value = user
        _contactName.value = contact
        _contactPhone.value = phone
        Log.d(TAG, "[DURGA] Saved contact info: User=$user, Contact=$contact, Phone=$phone")
    }

    /**
     * Triggered by physical shake gesture or SOS button.
     */
    fun triggerEmergency() {
        if (_emergencyState.value != EmergencyState.IDLE && _emergencyState.value != EmergencyState.CANCELLED) {
            Log.d(TAG, "[DURGA] Emergency already active or verifying. Ignoring trigger.")
            return
        }

        Log.d(TAG, "[DURGA] Gesture/Button detected. Starting 5s cancellation verification window.")
        _emergencyState.value = EmergencyState.VERIFYING
        _countdownSeconds.value = CANCEL_WINDOW_SECONDS

        countdownTimer?.cancel()
        countdownTimer = object : CountDownTimer((CANCEL_WINDOW_SECONDS * 1000).toLong(), 1000) {
            override fun onTick(millisUntilFinished: Long) {
                val secondsRemaining = (millisUntilFinished / 1000).toInt() + 1
                _countdownSeconds.value = secondsRemaining
            }

            override fun onFinish() {
                _countdownSeconds.value = 0
                Log.d(TAG, "[DURGA] 5-second window expired without cancellation. Escalating emergency.")
                executeEmergencyPipeline()
            }
        }.start()
    }

    /**
     * User explicitly cancels the emergency within the 5-second safety window.
     */
    fun cancelEmergency() {
        countdownTimer?.cancel()
        countdownTimer = null
        _emergencyState.value = EmergencyState.CANCELLED
        Log.d(TAG, "[DURGA] Emergency CANCELLED by user. Confirmed safe.")

        viewModelScope.launch {
            kotlinx.coroutines.delay(2000)
            if (_emergencyState.value == EmergencyState.CANCELLED) {
                _emergencyState.value = EmergencyState.IDLE
            }
        }
    }

    private fun executeEmergencyPipeline() {
        viewModelScope.launch {
            // ========================================================
            // STEP 1: MICROPHONE RECORDING (Phase 7: Microphone)
            // ========================================================
            _emergencyState.value = EmergencyState.RECORDING
            Log.d(TAG, "[DURGA] Phase 7: Microphone activated. Capturing 5-second distress sample...")
            val audioFile: File? = audioRecorder.recordAudio(durationMillis = 5000L) { remaining ->
                _recordingSecondsRemaining.value = remaining
            }

            // ========================================================
            // STEP 2: AI AUDIO DISTRESS ANALYSIS (Phase 8: AI Analysis)
            // ========================================================
            _emergencyState.value = EmergencyState.ANALYZING
            Log.d(TAG, "[DURGA] Phase 8: Analyzing acoustic features (RMS, Peak, ZCR, Vocal energy)...")
            val distressResult = distressAnalyzer.analyzeAudioFile(audioFile ?: File(""))
            _aiDistressInfo.value = "${distressResult.level} (${String.format("%.2f", distressResult.score)})"
            Log.d(TAG, "[DURGA] Distress assessment complete: ${distressResult.classification}")

            // ========================================================
            // STEP 3: GPS LOCATION ACQUISITION (Phase 9: Location)
            // ========================================================
            _emergencyState.value = EmergencyState.LOCATION_ACQUIRING
            val location: DurgaLocation? = locationProvider.getCurrentLocation()
            val lat = location?.latitude ?: 18.5204
            val lon = location?.longitude ?: 73.8567
            _locationInfo.value = if (location != null) {
                String.format("%.4f, %.4f (±%.1fm)", lat, lon, location.accuracy)
            } else {
                "Location acquired (Default / Cell)"
            }
            Log.d(TAG, "[DURGA] Location acquired: lat=$lat, lon=$lon")

            // ========================================================
            // STEP 4: NEAREST POLICE STATION LOOKUP (Phase 10: Police)
            // ========================================================
            val (nearestStation, distanceKm) = policeRepo.findNearestStation(lat, lon)
            Log.d(TAG, "[DURGA] Nearest station: ${nearestStation?.name} at ${String.format("%.2f", distanceKm ?: 0.0)} km")

            // ========================================================
            // STEP 5: AUDIO UPLOAD TO CLOUD / STORAGE (Phase 12)
            // ========================================================
            val eventId = "DURGA-2026-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            val audioReference = if (audioFile != null) {
                firestoreRepo.uploadAudioFile(audioFile, eventId)
            } else ""

            // ========================================================
            // STEP 6: CONSTRUCT & PUBLISH EMERGENCY EVENT (Phase 3 & 11)
            // ========================================================
            val emergencyEvent = EmergencyEvent(
                eventId = eventId,
                userId = "USER-001",
                userName = _userName.value ?: "Citizen A",
                timestamp = System.currentTimeMillis(),
                latitude = lat,
                longitude = lon,
                locationAccuracy = location?.accuracy ?: 15.0f,
                distressScore = distressResult.score,
                distressClassification = distressResult.classification,
                emergencyLevel = distressResult.level,
                audioUrl = audioReference,
                emergencyContactPhone = _contactPhone.value ?: "+91-9876543210",
                nearestPoliceStationName = nearestStation?.name ?: "Central Station",
                nearestPoliceStationDistanceKm = distanceKm ?: 2.5,
                status = "ACTIVE"
            )

            // Publish to Firestore & local real-time stream
            firestoreRepo.publishEmergencyEvent(emergencyEvent)

            _activeEmergencyEvent.value = emergencyEvent
            _emergencyState.value = EmergencyState.ACTIVE
            Log.d(TAG, "[DURGA] Emergency event created: $eventId. Status: ACTIVE")
        }
    }

    /**
     * Responder status management: ACKNOWLEDGED, RESPONDING, RESOLVED
     */
    fun updateIncidentStatus(eventId: String, newStatus: String) {
        viewModelScope.launch {
            emergencyRepo.updateEventStatus(eventId, newStatus)
            Log.d(TAG, "[DURGA] Updated incident $eventId status to $newStatus")
            if (newStatus == "RESOLVED") {
                _emergencyState.value = EmergencyState.RESOLVED
                kotlinx.coroutines.delay(1500)
                _emergencyState.value = EmergencyState.IDLE
            }
        }
    }

    fun resetToSafe() {
        _emergencyState.value = EmergencyState.IDLE
        _activeEmergencyEvent.value?.let { event ->
            viewModelScope.launch {
                emergencyRepo.resolveEmergency(event.eventId)
            }
        }
        _locationInfo.value = "Standby"
        _aiDistressInfo.value = "Model Standby"
        Log.d(TAG, "[DURGA] Emergency resolved. System returned to IDLE.")
    }

    override fun onCleared() {
        super.onCleared()
        countdownTimer?.cancel()
    }
}
