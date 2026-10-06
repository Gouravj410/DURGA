package com.durga.emergency.data

import com.durga.emergency.data.model.EmergencyEvent
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow

interface EmergencyRepository {
    val activeEvents: StateFlow<List<EmergencyEvent>>
    suspend fun publishEmergencyEvent(event: EmergencyEvent)
    suspend fun updateEventStatus(eventId: String, newStatus: String)
    suspend fun resolveEmergency(eventId: String)
    fun getLatestEvent(): EmergencyEvent?
}

/**
 * Shared in-memory event registry with persistent ready interface for Firebase Firestore.
 */
class InMemoryEmergencyRepository private constructor() : EmergencyRepository {

    companion object {
        @Volatile
        private var INSTANCE: InMemoryEmergencyRepository? = null

        fun getInstance(): InMemoryEmergencyRepository {
            return INSTANCE ?: synchronized(this) {
                INSTANCE ?: InMemoryEmergencyRepository().also { INSTANCE = it }
            }
        }
    }

    private val _eventsFlow = MutableStateFlow<List<EmergencyEvent>>(emptyList())
    override val activeEvents: StateFlow<List<EmergencyEvent>> = _eventsFlow.asStateFlow()

    override suspend fun publishEmergencyEvent(event: EmergencyEvent) {
        val currentList = _eventsFlow.value.toMutableList()
        currentList.removeAll { it.eventId == event.eventId }
        currentList.add(0, event) // Place latest on top
        _eventsFlow.value = currentList
    }

    override suspend fun updateEventStatus(eventId: String, newStatus: String) {
        val currentList = _eventsFlow.value.toMutableList()
        val index = currentList.indexOfFirst { it.eventId == eventId }
        if (index != -1) {
            val updated = currentList[index].copy(status = newStatus)
            currentList[index] = updated
            _eventsFlow.value = currentList
        }
    }

    override suspend fun resolveEmergency(eventId: String) {
        updateEventStatus(eventId, "RESOLVED")
    }

    override fun getLatestEvent(): EmergencyEvent? {
        return _eventsFlow.value.firstOrNull()
    }
}
