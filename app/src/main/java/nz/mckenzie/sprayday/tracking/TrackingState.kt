package nz.mckenzie.sprayday.tracking

import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.asStateFlow
import nz.mckenzie.sprayday.domain.recording.RecordingStatus

/**
 * Live state of the recorder, shared between the foreground service and the UI.
 *
 * One copy of this for the whole app, on purpose: only one recorder is ever
 * running, and having the screen ask the service to hand over its state would
 * buy nothing. What really happened is always the database's record - this is
 * here only so the screen can watch the work while it goes on.
 */
object TrackingState {

    data class State(
        val sessionId: Long? = null,
        val status: RecordingStatus? = null,
        val pointCount: Int = 0,
        val distanceM: Double = 0.0,
        val startedAtEpochMs: Long? = null,
        val lastAccuracyM: Float? = null,
        val rejectedFixes: Int = 0
    ) {
        val isActive: Boolean
            get() = sessionId != null && status != RecordingStatus.FINISHED
    }

    private val _state = MutableStateFlow(State())
    val state: StateFlow<State> = _state.asStateFlow()

    val current: State get() = _state.value

    /**
     * Starts mirroring a session.
     *
     * [pointCount] and [distanceM] are for a session that already has something in it:
     * reattaching to a recording that is still going must not say the operator has just
     * started, because the coverage beside those numbers is measured from the whole
     * pass - and it is the coverage that is right.
     */
    fun begin(
        sessionId: Long,
        startedAtEpochMs: Long,
        pointCount: Int = 0,
        distanceM: Double = 0.0
    ) {
        _state.value = State(
            sessionId = sessionId,
            status = RecordingStatus.RECORDING,
            pointCount = pointCount,
            distanceM = distanceM,
            startedAtEpochMs = startedAtEpochMs
        )
    }

    fun setStatus(status: RecordingStatus) {
        _state.value = _state.value.copy(status = status)
    }

    fun onAcceptedFix(pointCount: Int, distanceM: Double, accuracyM: Float?) {
        _state.value = _state.value.copy(
            pointCount = pointCount,
            distanceM = distanceM,
            lastAccuracyM = accuracyM
        )
    }

    fun onRejectedFixes(total: Int) {
        _state.value = _state.value.copy(rejectedFixes = total)
    }

    fun clear() {
        _state.value = State()
    }
}
