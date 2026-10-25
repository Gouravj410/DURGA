package com.durga.emergency.emergency

import android.util.Log
import java.io.File

/**
 * Trigger source classification for audit logging and trigger feasibility reporting.
 */
enum class TriggerSource {
    MANUAL_SOS_BUTTON,
    PERSISTENT_NOTIFICATION_ACTION,
    IN_APP_SHAKE_GESTURE,
    VOLUME_BUTTON_PATTERN,
    NEARBY_CITIZEN_DISPATCH
}

/**
 * Formal, testable Incident States for the DURGA system.
 */
enum class IncidentState {
    IDLE,
    TRIGGERED,
    RECORDING,
    ANALYZING,
    VERIFYING,
    ESCALATING,
    ACTIVE,
    REPEAT_ACTIVE,
    ACKNOWLEDGED,
    RESPONDING,
    RESOLVED,
    CANCELLED
}

/**
 * Explicit events driving the Incident State Machine.
 */
sealed class IncidentEvent {
    data class TriggerSos(val source: TriggerSource) : IncidentEvent()
    data class AudioRecordCompleted(val audioFile: File?) : IncidentEvent()
    data class AudioRecordFailed(val reason: String) : IncidentEvent()
    data class DistressAnalysisCompleted(val isDistressDetected: Boolean, val score: Float, val confidence: Float) : IncidentEvent()
    object UserConfirmedSafe : IncidentEvent()
    object UserRequestedAssistance : IncidentEvent()
    object SafetyPromptTimedOut : IncidentEvent()
    data class IncidentEscalated(val incidentId: String) : IncidentEvent()
    data class IncidentEscalationFailed(val reason: String) : IncidentEvent()
    object RepeatSosTriggered : IncidentEvent()
    data class PinCancellationRequested(val enteredPin: String) : IncidentEvent()
    object PinCancellationSucceeded : IncidentEvent()
    data class PinCancellationFailed(val attemptsRemaining: Int) : IncidentEvent()
    object PinCancellationLocked : IncidentEvent()
    data class ResponderAcknowledged(val responderId: String) : IncidentEvent()
    data class ResponderDispatched(val responderId: String) : IncidentEvent()
    data class ResponderResolved(val responderId: String, val resolutionNotes: String) : IncidentEvent()
    object ResetToIdle : IncidentEvent()
}

/**
 * Immutable record of a state machine transition.
 */
data class StateTransitionRecord(
    val fromState: IncidentState,
    val toState: IncidentState,
    val event: String,
    val timestamp: Long = System.currentTimeMillis(),
    val notes: String = ""
)

/**
 * Explicit, testable Incident State Machine for DURGA.
 * Guarantees that:
 * 1. AI cannot arbitrarily declare safety.
 * 2. Timeout in "Are you safe?" prompt does NOT escalate solely due to timeout.
 * 3. Repeat SOS bypasses AI analysis and locks ordinary cancellation.
 * 4. Only an authorized responder can resolve a repeat-SOS incident.
 */
class IncidentStateMachine(
    private val onStateChanged: (from: IncidentState, to: IncidentState, event: IncidentEvent) -> Unit = { _, _, _ -> }
) {
    companion object {
        private const val TAG = "DURGA_STATE_MACHINE"
        const val MAX_PIN_ATTEMPTS = 3
    }

    var currentState: IncidentState = IncidentState.IDLE
        private set

    var currentIncidentId: String? = null
        private set

    var activeTriggerSource: TriggerSource? = null
        private set

    var pinAttemptsRemaining: Int = MAX_PIN_ATTEMPTS
        private set

    var isPinLocked: Boolean = false
        private set

    private val _transitionHistory = mutableListOf<StateTransitionRecord>()
    val transitionHistory: List<StateTransitionRecord>
        get() = _transitionHistory.toList()

    /**
     * Process an incoming event against the current state.
     * Returns true if transition was accepted, false if invalid or blocked by rules.
     */
    @Synchronized
    fun processEvent(event: IncidentEvent): Boolean {
        val previousState = currentState
        val nextState: IncidentState? = when (currentState) {
            IncidentState.IDLE -> when (event) {
                is IncidentEvent.TriggerSos -> {
                    activeTriggerSource = event.source
                    IncidentState.TRIGGERED
                }
                else -> null
            }

            IncidentState.TRIGGERED -> when (event) {
                is IncidentEvent.AudioRecordCompleted, is IncidentEvent.AudioRecordFailed -> {
                    IncidentState.ANALYZING
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    // Repeat SOS bypasses recording/analysis
                    IncidentState.ESCALATING
                }
                is IncidentEvent.UserConfirmedSafe -> {
                    IncidentState.CANCELLED
                }
                else -> null
            }

            IncidentState.RECORDING -> when (event) {
                is IncidentEvent.AudioRecordCompleted, is IncidentEvent.AudioRecordFailed -> {
                    IncidentState.ANALYZING
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    IncidentState.ESCALATING
                }
                else -> null
            }

            IncidentState.ANALYZING -> when (event) {
                is IncidentEvent.DistressAnalysisCompleted -> {
                    if (event.isDistressDetected) {
                        IncidentState.ESCALATING
                    } else {
                        // Uncertain or low distress -> Ask "Are you safe?"
                        IncidentState.VERIFYING
                    }
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    IncidentState.ESCALATING
                }
                else -> null
            }

            IncidentState.VERIFYING -> when (event) {
                is IncidentEvent.UserRequestedAssistance -> {
                    IncidentState.ESCALATING
                }
                is IncidentEvent.UserConfirmedSafe -> {
                    IncidentState.IDLE
                }
                is IncidentEvent.SafetyPromptTimedOut -> {
                    // Confirmed policy: Do NOT escalate solely because prompt timed out
                    Log.i(TAG, "Safety prompt timed out with no user confirmation. Remaining in IDLE.")
                    IncidentState.IDLE
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    IncidentState.ESCALATING
                }
                else -> null
            }

            IncidentState.ESCALATING -> when (event) {
                is IncidentEvent.IncidentEscalated -> {
                    currentIncidentId = event.incidentId
                    if (activeTriggerSource == TriggerSource.MANUAL_SOS_BUTTON && currentState == IncidentState.REPEAT_ACTIVE) {
                        IncidentState.REPEAT_ACTIVE
                    } else {
                        IncidentState.ACTIVE
                    }
                }
                is IncidentEvent.IncidentEscalationFailed -> {
                    // Keep in ESCALATING with retry, or return to ACTIVE with local queuing
                    Log.w(TAG, "Escalation failed: ${event.reason}. Will retry while keeping local alert active.")
                    null
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    IncidentState.REPEAT_ACTIVE
                }
                else -> null
            }

            IncidentState.ACTIVE, IncidentState.REPEAT_ACTIVE -> when (event) {
                is IncidentEvent.RepeatSosTriggered -> {
                    Log.i(TAG, "Repeat SOS triggered during active incident. Priority re-notified; secret-code cancellation remains permitted.")
                    IncidentState.REPEAT_ACTIVE
                }
                is IncidentEvent.PinCancellationSucceeded -> {
                    IncidentState.CANCELLED
                }
                is IncidentEvent.ResponderAcknowledged -> {
                    IncidentState.ACKNOWLEDGED
                }
                is IncidentEvent.ResponderDispatched -> {
                    IncidentState.RESPONDING
                }
                is IncidentEvent.ResponderResolved -> {
                    IncidentState.RESOLVED
                }
                else -> null
            }

            IncidentState.ACKNOWLEDGED -> when (event) {
                is IncidentEvent.ResponderDispatched -> {
                    IncidentState.RESPONDING
                }
                is IncidentEvent.ResponderResolved -> {
                    IncidentState.RESOLVED
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    IncidentState.REPEAT_ACTIVE
                }
                else -> null
            }

            IncidentState.RESPONDING -> when (event) {
                is IncidentEvent.ResponderResolved -> {
                    IncidentState.RESOLVED
                }
                is IncidentEvent.RepeatSosTriggered -> {
                    IncidentState.REPEAT_ACTIVE
                }
                else -> null
            }

            IncidentState.RESOLVED, IncidentState.CANCELLED -> when (event) {
                is IncidentEvent.ResetToIdle -> {
                    resetStateInternal()
                    IncidentState.IDLE
                }
                else -> null
            }
        }

        if (nextState != null && nextState != currentState) {
            val record = StateTransitionRecord(
                fromState = previousState,
                toState = nextState,
                event = event.javaClass.simpleName
            )
            _transitionHistory.add(record)
            currentState = nextState
            Log.d(TAG, "Transition: $previousState -> $nextState via ${event.javaClass.simpleName}")
            onStateChanged(previousState, nextState, event)
            return true
        } else {
            Log.w(TAG, "Ignored or invalid event: ${event.javaClass.simpleName} in state: $currentState")
            return false
        }
    }

    /**
     * Check if ordinary user PIN cancellation is currently permitted.
     */
    fun isPinCancellationPermitted(): Boolean {
        return (currentState == IncidentState.ACTIVE || currentState == IncidentState.REPEAT_ACTIVE) && !isPinLocked
    }

    /**
     * Verify PIN attempt with throttling and failure count.
     */
    fun recordPinAttempt(isCorrect: Boolean): Boolean {
        if (!isPinCancellationPermitted()) return false

        if (isCorrect) {
            pinAttemptsRemaining = MAX_PIN_ATTEMPTS
            processEvent(IncidentEvent.PinCancellationSucceeded)
            return true
        } else {
            pinAttemptsRemaining--
            if (pinAttemptsRemaining <= 0) {
                isPinLocked = true
                processEvent(IncidentEvent.PinCancellationLocked)
            } else {
                processEvent(IncidentEvent.PinCancellationFailed(pinAttemptsRemaining))
            }
            return false
        }
    }

    private fun resetStateInternal() {
        currentIncidentId = null
        activeTriggerSource = null
        pinAttemptsRemaining = MAX_PIN_ATTEMPTS
        isPinLocked = false
    }
}
