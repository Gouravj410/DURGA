package com.durga.emergency

import com.durga.emergency.data.InMemoryEmergencyRepository
import com.durga.emergency.data.model.EmergencyEvent
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Before
import org.junit.Test

class EmergencyRepositoryTest {

    private lateinit var repository: InMemoryEmergencyRepository

    @Before
    fun setUp() {
        repository = InMemoryEmergencyRepository.getInstance()
        repository.clear()
    }

    @Test
    fun publishEmergencyEvent_addsEventToHeadOfList() = runBlocking {
        val event1 = EmergencyEvent(eventId = "TEST-001", status = "ACTIVE")
        val event2 = EmergencyEvent(eventId = "TEST-002", status = "ACTIVE")

        repository.publishEmergencyEvent(event1)
        repository.publishEmergencyEvent(event2)

        val latest = repository.getLatestEvent()
        assertNotNull(latest)
        assertEquals("TEST-002", latest?.eventId)
        assertEquals(2, repository.activeEvents.value.size)
    }

    @Test
    fun updateEventStatus_updatesStatusProperly() = runBlocking {
        val event = EmergencyEvent(eventId = "TEST-003", status = "ACTIVE")
        repository.publishEmergencyEvent(event)

        repository.updateEventStatus("TEST-003", "RESPONDING")
        val updated = repository.activeEvents.value.first { it.eventId == "TEST-003" }
        assertEquals("RESPONDING", updated.status)
    }

    @Test
    fun resolveEmergency_marksStatusResolved() = runBlocking {
        val event = EmergencyEvent(eventId = "TEST-004", status = "ACTIVE")
        repository.publishEmergencyEvent(event)

        repository.resolveEmergency("TEST-004")
        val resolved = repository.activeEvents.value.first { it.eventId == "TEST-004" }
        assertEquals("RESOLVED", resolved.status)
    }
}
