package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.map.TrackGeoJson
import nz.mckenzie.sprayday.map.TrackLine

/** One recording, in full: what was driven, and how much of the plan it covered. */
data class RecordingDetail(
    val id: Long,
    val name: String,
    val startedAtEpochMs: Long,
    val endedAtEpochMs: Long?,
    val status: RecordingStatus,
    /** Distance as recorded by the service at the time. */
    val recordedDistanceM: Double,
    /** Distance computed from the stored geometry, which is the same thing re-derived. */
    val geometryDistanceM: Double,
    val points: List<GeoPoint>,
    val trackId: Long?,
    val trackName: String?,
    val plannedGeometry: List<GeoPoint>,
    /** Fraction of the planned line covered, or null when there is no plan to compare. */
    val coverage: Double?
) {
    val durationMs: Long get() = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L

    val isFinished: Boolean get() = status == RecordingStatus.FINISHED

    val hasPlan: Boolean get() = plannedGeometry.size >= 2
}

/**
 * Reviews one recording against the track it was made for.
 *
 * This is where the GPS evidence behind a spray can be looked at: the line that
 * was driven, the line that was planned, and how much of the plan it covered.
 * Coverage is recomputed here rather than stored, so it stays honest even if the
 * planned geometry was edited afterwards.
 */
class RecordingDetailViewModel(
    private val sessionId: Long,
    private val recordings: RecordingRepository,
    private val tracks: TrackRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _detail = MutableStateFlow<RecordingDetail?>(null)
    val detail: StateFlow<RecordingDetail?> = _detail

    /** The recorded line in red over the planned line in grey. */
    val geoJson: StateFlow<String> = _detail
        .map { detail ->
            TrackGeoJson.build(
                listOf(
                    TrackLine(
                        trackId = PLANNED_ID,
                        name = detail?.trackName ?: "Planned",
                        colorHex = TrackColors.UNKNOWN,
                        points = detail?.plannedGeometry.orEmpty()
                    ),
                    TrackLine(
                        trackId = RECORDED_ID,
                        name = detail?.name ?: "Recording",
                        colorHex = TrackColors.RED,
                        points = detail?.points.orEmpty()
                    )
                )
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            TrackGeoJson.build(emptyList())
        )

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _deleted = MutableStateFlow(false)

    /**
     * True once the recording has been deleted. A one-shot signal: the screen
     * consumes it as it navigates away, otherwise a reused view model would bounce
     * the operator straight back out on their next visit.
     */
    val deleted: StateFlow<Boolean> = _deleted

    fun consumeDeleted() {
        _deleted.value = false
    }

    init {
        load()
    }

    private fun load() {
        viewModelScope.launch {
            val session = recordings.getSession(sessionId)
            if (session == null) {
                _message.value = "That recording is no longer on the device"
                return@launch
            }

            val points = recordings.getPoints(sessionId)
            val planned = session.trackId?.let { trackId ->
                runCatching { tracks.getTrackGeometry(trackId) }.getOrDefault(emptyList())
            }.orEmpty()
            val trackName = session.trackId?.let { trackId ->
                runCatching { tracks.getTrack(trackId)?.name }.getOrNull()
            }

            val coverage = withContext(Dispatchers.Default) {
                if (planned.size >= 2 && points.isNotEmpty()) {
                    Coverage.coveredFraction(planned, points)
                } else {
                    null
                }
            }

            _detail.value = RecordingDetail(
                id = session.id,
                name = session.name,
                startedAtEpochMs = session.startedAtEpochMs,
                endedAtEpochMs = session.endedAtEpochMs,
                status = runCatching { RecordingStatus.valueOf(session.status) }
                    .getOrDefault(RecordingStatus.FINISHED),
                recordedDistanceM = session.distanceM,
                geometryDistanceM = polylineLengthMeters(points),
                points = points,
                trackId = session.trackId,
                trackName = trackName,
                plannedGeometry = planned,
                coverage = coverage
            )
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { recordings.deleteRecording(sessionId) }
                .onSuccess { _deleted.value = true }
                .onFailure { failure ->
                    _message.value = failure.message ?: "Could not delete the recording"
                }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val RECORDED_ID = -2L
        private const val PLANNED_ID = -3L

        fun factory(context: Context, sessionId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    RecordingDetailViewModel(
                        sessionId = sessionId,
                        recordings = RecordingRepository(database),
                        tracks = TrackRepository(database),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
