package com.durga.emergency

import com.durga.emergency.emergency.EmergencyState
import com.durga.emergency.emergency.UserRole
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class EmergencyStateTest {

    @Test
    fun emergencyStates_containAllRequiredStateMachineStates() {
        val states = EmergencyState.values().map { it.name }

        assertTrue(states.contains("IDLE"))
        assertTrue(states.contains("TRIGGERED"))
        assertTrue(states.contains("VERIFYING"))
        assertTrue(states.contains("CANCELLED"))
        assertTrue(states.contains("RECORDING"))
        assertTrue(states.contains("ANALYZING"))
        assertTrue(states.contains("LOCATION_ACQUIRING"))
        assertTrue(states.contains("ESCALATING"))
        assertTrue(states.contains("ACTIVE"))
        assertTrue(states.contains("ACKNOWLEDGED"))
        assertTrue(states.contains("RESPONDING"))
        assertTrue(states.contains("RESOLVED"))
        assertTrue(states.contains("FAILED"))
    }

    @Test
    fun userRoles_containTripleRoleArchitecture() {
        val roles = UserRole.values().map { it.name }
        assertEquals(3, roles.size)
        assertTrue(roles.contains("USER"))
        assertTrue(roles.contains("EMERGENCY_CONTACT"))
        assertTrue(roles.contains("POLICE_RESPONDER"))
    }
}
