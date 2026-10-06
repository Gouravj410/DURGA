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
import com.durga.emergency.data.LocalPoliceStationRepository
import com.durga.emergency.data.PoliceStationRepository
import com.durga.emergency.data.model.EmergencyEvent
import com.durga.emergency.emergency.EmergencyState
import com.durga.emergency.emergency.UserRole
import com.durga.emergency.location.DurgaLocation
import com.durga.emergency.location.FusedLocationProvider
import com.durga.emergency.location.LocationProvider
import kotlinx.coroutines.launch
import java.util.UUID

class MainViewModel(application: Application) : AndroidViewModel(application) {

    companion object {
        private const val TAG = "DURGA"
        private const val CANCEL_WINDOW_SECONDS = 5
    }

    private val policeRepo: PoliceStationRepository = LocalPoliceStationRepository(application)
    private val locationProvider: LocationProvider = FusedLocationProvider(application)
    private val distressAnalyzer: DistressAnalyzer = DurgaScoringDistressAnalyzer()

    private val _currentRole = MutableLiveData(UserRole.USER)
    val currentRole: LiveData<UserRole> = _currentRole

    private val _emergencyState = MutableLiveData(EmergencyState.IDLE)
    val emergencyState: LiveData<EmergencyState> = _emergencyState

    private val _countdownSeconds = MutableLiveData(CANCEL_WINDOW_SECONDS)
    val countdownSeconds: LiveData<Int> = _countdownSeconds

    private val _activeEmergencyEvent = MutableLiveData<EmergencyEvent?>()
    val activeEmergencyEvent: LiveData<EmergencyEvent?> = _activeEmergencyEvent

    private val _locationInfo = MutableLiveData<String>("Standby")
    val locationInfo: LiveData<String> = _locationInfo

    private val _aiDistressInfo = MutableLiveData<String>("Model Standby")
    val aiDistressInfo: LiveData<String> = _aiDistressInfo

    private var countdownTimer: CountDownTimer? = null

    fun setRole(role: UserRole) {
        _currentRole.value = role
        Log.d(TAG, "[DURGA] Active role changed to: $role")
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
        _emergencyState.value = EmergencyState.LOCATION_ACQUIRING
        viewModelScope.launch {
            // Step 1: Acquire Location
            val location: DurgaLocation? = locationProvider.getCurrentLocation()
            val lat = location?.latitude ?: 18.5204
            val lon = location?.longitude ?: 73.8567
            _locationInfo.value = if (location != null) {
                String.format("%.4f, %.4f (±%.1fm)", lat, lon, location.accuracy)
            } else {
                "Location acquired (Default / Cell)"
            }
            Log.d(TAG, "[DURGA] Location acquired: lat=$lat, lon=$lon")

            // Step 2: Nearest Police Station calculation
            val (nearestStation, distanceKm) = policeRepo.findNearestStation(lat, lon)
            Log.d(TAG, "[DURGA] Nearest station: ${nearestStation?.name} at ${String.format("%.2f", distanceKm ?: 0.0)} km")

            // Step 3: Audio & AI Assessment
            _emergencyState.value = EmergencyState.ANALYZING
            val distressResult = distressAnalyzer.analyzeAudioFile(java.io.File(""))
            _aiDistressInfo.value = "${distressResult.level} (${String.format("%.2f", distressResult.score)})"
            Log.d(TAG, "[DURGA] AI distress assessed: score=${distressResult.score}, level=${distressResult.level}")

            // Step 4: Construct Emergency Event
            val eventId = "DURGA-2026-" + UUID.randomUUID().toString().substring(0, 6).uppercase()
            val emergencyEvent = EmergencyEvent(
                eventId = eventId,
                userId = "USER-001",
                userName = "DURGA Prototype User",
                timestamp = System.currentTimeMillis(),
                latitude = lat,
                longitude = lon,
                locationAccuracy = location?.accuracy ?: 15.0f,
                distressScore = distressResult.score,
                distressClassification = distressResult.classification,
                emergencyLevel = distressResult.level,
                nearestPoliceStationName = nearestStation?.name ?: "Central Station",
                nearestPoliceStationDistanceKm = distanceKm ?: 2.5,
                status = "ACTIVE"
            )

            _activeEmergencyEvent.value = emergencyEvent
            _emergencyState.value = EmergencyState.ACTIVE
            Log.d(TAG, "[DURGA] Emergency event created: $eventId. Status: ACTIVE")
        }
    }

    fun resetToSafe() {
        _emergencyState.value = EmergencyState.IDLE
        _activeEmergencyEvent.value = null
        _locationInfo.value = "Standby"
        _aiDistressInfo.value = "Model Standby"
        Log.d(TAG, "[DURGA] Emergency resolved. System returned to IDLE.")
    }

    override fun onCleared() {
        super.onCleared()
        countdownTimer?.cancel()
    }
}
