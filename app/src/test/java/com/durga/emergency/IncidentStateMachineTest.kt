package com.durga.emergency

import com.durga.emergency.emergency.IncidentEvent
import com.durga.emergency.emergency.IncidentState
import com.durga.emergency.emergency.IncidentStateMachine
import com.durga.emergency.emergency.TriggerSource
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import java.io.File

class IncidentStateMachineTest {

    private lateinit var stateMachine: IncidentStateMachine

    @Before
    fun setUp() {
        stateMachine = IncidentStateMachine()
    }

    @Test
    fun testInitialStateIsIdle() {
        assertEquals(IncidentState.IDLE, stateMachine.currentState)
        assertTrue(stateMachine.isPinCancellationPermitted().not())
    }

    @Test
    fun testNormalTriggerToEscalationWhenDistressDetected() {
        // 1. Trigger
        val triggered = stateMachine.processEvent(IncidentEvent.TriggerSos(TriggerSource.MANUAL_SOS_BUTTON))
        assertTrue(triggered)
        assertEquals(IncidentState.TRIGGERED, stateMachine.currentState)

        // 2. Audio captured
        val audioDone = stateMachine.processEvent(IncidentEvent.AudioRecordCompleted(File("test.wav")))
        assertTrue(audioDone)
        assertEquals(IncidentState.ANALYZING, stateMachine.currentState)

        // 3. High distress detected -> Should advance to ESCALATING
        val distressDone = stateMachine.processEvent(
            IncidentEvent.DistressAnalysisCompleted(isDistressDetected = true, score = 0.88f, confidence = 0.92f)
        )
        assertTrue(distressDone)
        assertEquals(IncidentState.ESCALATING, stateMachine.currentState)

        // 4. Backend incident created -> ACTIVE
        val escalated = stateMachine.processEvent(IncidentEvent.IncidentEscalated("INC-1001"))
        assertTrue(escalated)
        assertEquals(IncidentState.ACTIVE, stateMachine.currentState)
        assertEquals("INC-1001", stateMachine.currentIncidentId)
        assertTrue(stateMachine.isPinCancellationPermitted())
    }

    @Test
    fun testUncertainDistressShowsAreYouSafeAndDoesNotEscalateOnTimeout() {
        // Trigger and complete audio
        stateMachine.processEvent(IncidentEvent.TriggerSos(TriggerSource.PERSISTENT_NOTIFICATION_ACTION))
        stateMachine.processEvent(IncidentEvent.AudioRecordCompleted(File("test.wav")))

        // Uncertain/low distress -> Must transition to VERIFYING
        val distressResult = stateMachine.processEvent(
            IncidentEvent.DistressAnalysisCompleted(isDistressDetected = false, score = 0.35f, confidence = 0.40f)
        )
        assertTrue(distressResult)
        assertEquals(IncidentState.VERIFYING, stateMachine.currentState)

        // Non-negotiable policy: If safety prompt times out, do NOT escalate solely due to timeout!
        val timedOut = stateMachine.processEvent(IncidentEvent.SafetyPromptTimedOut)
        assertTrue(timedOut)
        assertEquals(IncidentState.IDLE, stateMachine.currentState)
    }

    @Test
    fun testInitialIncidentCancelledWithCorrectPin() {
        // Drive to ACTIVE state
        stateMachine.processEvent(IncidentEvent.TriggerSos(TriggerSource.MANUAL_SOS_BUTTON))
        stateMachine.processEvent(IncidentEvent.AudioRecordCompleted(null))
        stateMachine.processEvent(IncidentEvent.DistressAnalysisCompleted(true, 0.9f, 0.9f))
        stateMachine.processEvent(IncidentEvent.IncidentEscalated("INC-1002"))
        assertEquals(IncidentState.ACTIVE, stateMachine.currentState)

        // Verify PIN cancellation is allowed
        assertTrue(stateMachine.isPinCancellationPermitted())

        // Correct PIN
        val pinSuccess = stateMachine.recordPinAttempt(isCorrect = true)
        assertTrue(pinSuccess)
        assertEquals(IncidentState.CANCELLED, stateMachine.currentState)
        assertFalse(stateMachine.isPinCancellationPermitted())
    }

    @Test
    fun testRepeatSosDisablesOrdinaryCancellationAndRequiresResponder() {
        // Drive to ACTIVE state
        stateMachine.processEvent(IncidentEvent.TriggerSos(TriggerSource.MANUAL_SOS_BUTTON))
        stateMachine.processEvent(IncidentEvent.AudioRecordCompleted(null))
        stateMachine.processEvent(IncidentEvent.DistressAnalysisCompleted(true, 0.9f, 0.9f))
        stateMachine.processEvent(IncidentEvent.IncidentEscalated("INC-1003"))
        assertEquals(IncidentState.ACTIVE, stateMachine.currentState)

        // User hits Repeat SOS
        val repeatTriggered = stateMachine.processEvent(IncidentEvent.RepeatSosTriggered)
        assertTrue(repeatTriggered)
        assertEquals(IncidentState.REPEAT_ACTIVE, stateMachine.currentState)

        // Verification: Ordinary PIN cancellation is strictly disabled!
        assertFalse(stateMachine.isPinCancellationPermitted())
        val pinAttempt = stateMachine.recordPinAttempt(isCorrect = true)
        assertFalse(pinAttempt)
        assertEquals(IncidentState.REPEAT_ACTIVE, stateMachine.currentState)

        // Only an authorized responder can resolve
        val responderResolved = stateMachine.processEvent(
            IncidentEvent.ResponderResolved("RESP-999", "On-site verification complete. Citizen safe.")
        )
        assertTrue(responderResolved)
        assertEquals(IncidentState.RESOLVED, stateMachine.currentState)
    }

    @Test
    fun testPinThrottlingLocksAfterThreeIncorrectAttempts() {
        // Drive to ACTIVE state
        stateMachine.processEvent(IncidentEvent.TriggerSos(TriggerSource.MANUAL_SOS_BUTTON))
        stateMachine.processEvent(IncidentEvent.AudioRecordCompleted(null))
        stateMachine.processEvent(IncidentEvent.DistressAnalysisCompleted(true, 0.9f, 0.9f))
        stateMachine.processEvent(IncidentEvent.IncidentEscalated("INC-1004"))
        assertEquals(IncidentState.ACTIVE, stateMachine.currentState)

        // Attempt 1: Fail
        assertFalse(stateMachine.recordPinAttempt(isCorrect = false))
        assertEquals(2, stateMachine.pinAttemptsRemaining)
        assertFalse(stateMachine.isPinLocked)

        // Attempt 2: Fail
        assertFalse(stateMachine.recordPinAttempt(isCorrect = false))
        assertEquals(1, stateMachine.pinAttemptsRemaining)
        assertFalse(stateMachine.isPinLocked)

        // Attempt 3: Fail -> Should be locked
        assertFalse(stateMachine.recordPinAttempt(isCorrect = false))
        assertEquals(0, stateMachine.pinAttemptsRemaining)
        assertTrue(stateMachine.isPinLocked)
        assertFalse(stateMachine.isPinCancellationPermitted())
    }
}
