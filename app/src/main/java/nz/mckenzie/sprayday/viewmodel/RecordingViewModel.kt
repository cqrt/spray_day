package nz.mckenzie.sprayday.viewmodel

import android.Manifest
import android.content.Context
import android.content.pm.PackageManager
import androidx.core.content.ContextCompat
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.map.TrackGeoJson
import nz.mckenzie.sprayday.map.TrackLine
import nz.mckenzie.sprayday.tracking.TrackingService
import nz.mckenzie.sprayday.tracking.TrackingState

/**
 * Drives the recorder screen.
 *
 * The service owns the recording loop; this only starts/stops it and mirrors
 * [TrackingState] plus the growing geometry from the database, so leaving and
 * re-entering the screen (or restarting the app) loses nothing.
 */
class RecordingViewModel(
    private val recordings: RecordingRepository,
    settingsRepository: SettingsRepository,
    private val context: Context
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val tracking: StateFlow<TrackingState.State> = TrackingState.state

    private val sessionPoints = MutableStateFlow<List<GeoPoint>>(emptyList())

    /** The line as recorded so far, drawn red like any un-sprayed track. */
    val recordedGeoJson: StateFlow<String> = sessionPoints
        .map { points ->
            TrackGeoJson.build(
                listOf(
                    TrackLine(
                        trackId = RECORDING_ID,
                        name = "Recording",
                        colorHex = TrackColors.RED,
                        points = points
                    )
                )
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrackGeoJson.build(emptyList()))

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private var observingSession: Long? = null

    init {
        viewModelScope.launch {
            // Reattach to a session left running, e.g. the app was killed mid-spray.
            val unfinished = recordings.findUnfinishedSession() ?: return@launch
            TrackingState.begin(unfinished.id, unfinished.startedAtEpochMs)
            val status = runCatching { RecordingStatus.valueOf(unfinished.status) }
                .getOrDefault(RecordingStatus.RECORDING)
            TrackingState.setStatus(status)
            observe(unfinished.id)

            // A foreground service does not survive a process kill, so a session
            // still marked RECORDING has nothing collecting for it: start the
            // service again rather than silently leaving a dead session running.
            if (status == RecordingStatus.RECORDING && hasLocationPermission()) {
                TrackingService.start(context, unfinished.id)
                _message.value = "Resumed recording the track that was already in progress"
            } else {
                _message.value = "Reattached to the track that was already recording"
            }
        }
    }

    fun hasLocationPermission(): Boolean =
        ContextCompat.checkSelfPermission(context, Manifest.permission.ACCESS_FINE_LOCATION) ==
            PackageManager.PERMISSION_GRANTED

    fun onPermissionDenied() {
        _message.value = "Location permission is needed to record a track"
    }

    fun start() {
        if (!hasLocationPermission()) {
            onPermissionDenied()
            return
        }
        viewModelScope.launch {
            _message.value = null
            val sessionId = recordings.startRecording(name = defaultName())
            TrackingState.begin(sessionId, System.currentTimeMillis())
            observe(sessionId)
            TrackingService.start(context, sessionId)
        }
    }

    fun pause() = sessionIdOrNull()?.let { TrackingService.pause(context) }

    fun resume() = sessionIdOrNull()?.let { TrackingService.resume(context) }

    /** Closes the session with the distance the service accumulated, then stops it. */
    fun finish() {
        val sessionId = sessionIdOrNull() ?: return
        viewModelScope.launch {
            val distanceM = TrackingState.current.distanceM
            val points = TrackingState.current.pointCount
            recordings.finishRecording(sessionId, distanceM = distanceM)
            TrackingService.stop(context)
            TrackingState.clear()
            observingSession = null
            sessionPoints.value = emptyList()
            _message.value = "Saved a recording of $points points"
        }
    }

    private fun sessionIdOrNull(): Long? = TrackingState.current.sessionId

    private fun observe(sessionId: Long) {
        if (observingSession == sessionId) return
        observingSession = sessionId
        viewModelScope.launch {
            recordings.observePoints(sessionId).collect { sessionPoints.value = it }
        }
    }

    private fun defaultName(): String = "Spray run"

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val RECORDING_ID = -2L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    RecordingViewModel(
                        recordings = RecordingRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext),
                        context = appContext
                    )
                }
            }
        }
    }
}
