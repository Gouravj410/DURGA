package com.durga.emergency.data

import android.content.Context
import android.net.Uri
import android.util.Log
import com.durga.emergency.data.model.EmergencyEvent
import com.google.firebase.FirebaseApp
import com.google.firebase.firestore.FirebaseFirestore
import com.google.firebase.firestore.Query
import com.google.firebase.storage.FirebaseStorage
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import kotlinx.coroutines.launch
import kotlinx.coroutines.suspendCancellableCoroutine
import kotlinx.coroutines.withContext
import java.io.File
import kotlin.coroutines.resume

class FirestoreEmergencyRepository(private val context: Context) : EmergencyRepository {

    companion object {
        private const val TAG = "DURGA_FIRESTORE"
        private const val COLLECTION_EMERGENCY_EVENTS = "emergencyEvents"

        @Volatile
        private var instance: FirestoreEmergencyRepository? = null

        fun getInstance(context: Context): FirestoreEmergencyRepository {
            return instance ?: synchronized(this) {
                instance ?: FirestoreEmergencyRepository(context.applicationContext).also {
                    instance = it
                }
            }
        }
    }

    private val localFallbackRepo = InMemoryEmergencyRepository.getInstance()
    private val _activeEvents = MutableStateFlow<List<EmergencyEvent>>(emptyList())
    override val activeEvents: StateFlow<List<EmergencyEvent>> = _activeEvents.asStateFlow()

    private var firestore: FirebaseFirestore? = null
    private var storage: FirebaseStorage? = null

    init {
        initFirebase()
    }

    private fun initFirebase() {
        try {
            if (FirebaseApp.getApps(context).isEmpty()) {
                FirebaseApp.initializeApp(context)
            }
            firestore = FirebaseFirestore.getInstance()
            storage = FirebaseStorage.getInstance()
            listenToRemoteEvents()
            Log.d(TAG, "Firebase initialized successfully.")
        } catch (e: Exception) {
            Log.w(TAG, "Firebase initialization warning: ${e.message}. Operating in resilient local-first mode.")
            observeFallbackRepo()
        }
    }

    private fun observeFallbackRepo() {
        CoroutineScope(Dispatchers.Default).launch {
            localFallbackRepo.activeEvents.collect { fallbackList ->
                if (_activeEvents.value.isEmpty() || firestore == null) {
                    _activeEvents.value = fallbackList
                }
            }
        }
    }

    private fun listenToRemoteEvents() {
        firestore?.collection(COLLECTION_EMERGENCY_EVENTS)
            ?.orderBy("timestamp", Query.Direction.DESCENDING)
            ?.addSnapshotListener { snapshot, error ->
                if (error != null) {
                    Log.w(TAG, "Firestore snapshot listener error: ${error.message}. Maintaining local events.")
                    return@addSnapshotListener
                }

                if (snapshot != null) {
                    val events = snapshot.documents.mapNotNull { doc ->
                        try {
                            doc.toObject(EmergencyEvent::class.java)
                        } catch (e: Exception) {
                            Log.e(TAG, "Error deserializing event: ${doc.id}", e)
                            null
                        }
                    }
                    if (events.isNotEmpty()) {
                        _activeEvents.value = events
                        Log.d(TAG, "Firestore synced ${events.size} live emergency events across devices.")
                    }
                }
            }
    }

    override suspend fun publishEmergencyEvent(event: EmergencyEvent) {
        withContext(Dispatchers.IO) {
            // Always publish to local memory first for instantaneous UI update
            localFallbackRepo.publishEmergencyEvent(event)
            val currentList = _activeEvents.value.toMutableList()
            currentList.removeAll { it.eventId == event.eventId }
            currentList.add(0, event)
            _activeEvents.value = currentList

            // Push to Cloud Firestore
            firestore?.let { db ->
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        db.collection(COLLECTION_EMERGENCY_EVENTS)
                            .document(event.eventId)
                            .set(event)
                            .addOnSuccessListener {
                                Log.d(TAG, "EmergencyEvent ${event.eventId} pushed to Firestore.")
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Firestore push warning: ${e.message}. Event stored in local pipeline.")
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Failed to sync to Firestore: ${e.message}")
                }
            } ?: run {
                Log.d(TAG, "Firestore unavailable, event retained in local store.")
            }
        }
    }

    override suspend fun updateEventStatus(eventId: String, newStatus: String) {
        withContext(Dispatchers.IO) {
            localFallbackRepo.updateEventStatus(eventId, newStatus)
            val currentList = _activeEvents.value.map {
                if (it.eventId == eventId) it.copy(status = newStatus) else it
            }
            _activeEvents.value = currentList

            firestore?.let { db ->
                try {
                    suspendCancellableCoroutine<Unit> { continuation ->
                        db.collection(COLLECTION_EMERGENCY_EVENTS)
                            .document(eventId)
                            .update("status", newStatus)
                            .addOnSuccessListener {
                                Log.d(TAG, "Firestore status updated for $eventId -> $newStatus")
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                            .addOnFailureListener { e ->
                                Log.w(TAG, "Firestore status update failed: ${e.message}")
                                if (continuation.isActive) continuation.resume(Unit)
                            }
                    }
                } catch (e: Exception) {
                    Log.w(TAG, "Error updating status in Firestore: ${e.message}")
                }
            } ?: Unit
        }
    }

    override suspend fun resolveEmergency(eventId: String) {
        updateEventStatus(eventId, "RESOLVED")
    }

    override fun getLatestEvent(): EmergencyEvent? {
        return _activeEvents.value.firstOrNull() ?: localFallbackRepo.getLatestEvent()
    }

    /**
     * Phase 12: Audio Upload to Firebase Storage.
     * Uploads recorded audio sample and returns download URL or local file path.
     */
    suspend fun uploadAudioFile(audioFile: File, eventId: String): String = withContext(Dispatchers.IO) {
        if (!audioFile.exists() || audioFile.length() == 0L) {
            return@withContext ""
        }

        val storageRef = storage?.reference?.child("emergency_recordings/$eventId.wav")
        if (storageRef != null) {
            try {
                val uri = Uri.fromFile(audioFile)
                val downloadUrl = suspendCancellableCoroutine<String?> { continuation ->
                    storageRef.putFile(uri)
                        .continueWithTask { task ->
                            if (!task.isSuccessful) {
                                task.exception?.let { throw it }
                            }
                            storageRef.downloadUrl
                        }
                        .addOnSuccessListener { url ->
                            Log.d(TAG, "Audio uploaded to Firebase Storage: $url")
                            if (continuation.isActive) continuation.resume(url.toString())
                        }
                        .addOnFailureListener { e ->
                            Log.w(TAG, "Firebase Storage upload fallback: ${e.message}")
                            if (continuation.isActive) continuation.resume(null)
                        }
                }
                if (!downloadUrl.isNullOrEmpty()) {
                    return@withContext downloadUrl
                }
            } catch (e: Exception) {
                Log.w(TAG, "Storage upload exception: ${e.message}. Using local file URI.")
            }
        }

        return@withContext audioFile.absolutePath
    }
}
