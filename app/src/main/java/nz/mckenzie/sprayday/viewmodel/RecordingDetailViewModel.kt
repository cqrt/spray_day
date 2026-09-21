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
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.RecordingBreak
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.geo.splitAtBreaks
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.map.AssetStretch

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
    /** The stretches of the pass that were paused for, which are not ground it drove. */
    val breaks: List<RecordingBreak> = emptyList(),
    val assetId: Long?,
    val assetName: String?,
    val plannedGeometry: AssetGeometry,
    /** Fraction of the planned line covered, or null when there is no plan to compare. */
    val coverage: Double?
) {
    val durationMs: Long get() = endedAtEpochMs?.let { (it - startedAtEpochMs).coerceAtLeast(0L) } ?: 0L

    val isFinished: Boolean get() = status == RecordingStatus.FINISHED

    val hasPlan: Boolean get() = plannedGeometry.isLine
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
    private val assetRepository: AssetRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Which map to draw the pass on: the operator's choice in Settings. */
    val basemap: StateFlow<Basemap> = settingsRepository.basemap
        .stateIn(viewModelScope, SharingStarted.Eagerly, Basemap.DEFAULT)

    private val _detail = MutableStateFlow<RecordingDetail?>(null)
    val detail: StateFlow<RecordingDetail?> = _detail

    /** The recorded line in red over the planned line in grey. */
    val geoJson: StateFlow<String> = _detail
        .map { detail ->
            val points = detail?.points.orEmpty()
            val breaks = detail?.breaks.orEmpty()
            AssetGeoJson.build(
                listOf(
                    AssetLine(
                        assetId = PLANNED_ID,
                        name = detail?.assetName ?: "Planned",
                        colorHex = AssetColors.UNKNOWN,
                        points = detail?.plannedGeometry?.line.orEmpty(),
                        sideTracks = detail?.plannedGeometry?.sideTracks.orEmpty()
                    ),
                    AssetLine(
                        assetId = RECORDED_ID,
                        name = detail?.name ?: "Recording",
                        colorHex = AssetColors.RED,
                        points = points,
                        // Broken where the pass was paused, and drawn straight through the gaps
                        // where the fixes only went missing: the recording is the evidence, and
                        // what the operator stopped for is part of it.
                        stretches = points.splitAtBreaks(breaks).map { piece ->
                            AssetStretch(colorHex = AssetColors.RED, points = piece)
                        }
                    )
                )
            )
        }
        .stateIn(
            viewModelScope,
            SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
            AssetGeoJson.build(emptyList())
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
            val breaks = recordings.getBreaks(sessionId)
            val planned = session.assetId?.let { assetId ->
                runCatching { assetRepository.getAssetGeometry(assetId) }
                    .getOrDefault(AssetGeometry.NONE)
            } ?: AssetGeometry.NONE
            val assetName = session.assetId?.let { assetId ->
                runCatching { assetRepository.getAsset(assetId)?.name }.getOrNull()
            }

            val coverage = withContext(Dispatchers.Default) {
                if (planned.isLine && points.isNotEmpty()) {
                    Coverage.coveredFraction(planned, points, breaks = breaks)
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
                breaks = breaks,
                assetId = session.assetId,
                assetName = assetName,
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
                        assetRepository = AssetRepository(database),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
