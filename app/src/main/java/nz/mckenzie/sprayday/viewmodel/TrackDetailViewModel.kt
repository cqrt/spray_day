package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.ProductQuantityLine
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.TrackEntity
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.estimatedAreaSqm
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/** One spray in a track's history, with the amounts that went out. */
data class SprayHistoryEntry(
    val event: SprayEventEntity,
    val lines: List<ProductQuantityLine>
)

/**
 * Everything about one track: its line on the map, when it is next due, what it
 * has been given, and GPX export.
 */
class TrackDetailViewModel(
    private val trackId: Long,
    private val tracks: TrackRepository,
    private val sprays: SprayRepository,
    settingsRepository: SettingsRepository,
    private val context: Context
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    val track: StateFlow<TrackEntity?> = tracks.observeTrack(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val geometry: StateFlow<List<GeoPoint>> = tracks.observeTrackGeometry(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val bounds: StateFlow<LatLngBounds?> = geometry
        .map { points ->
            if (points.isEmpty()) {
                null
            } else {
                LatLngBounds(
                    minLat = points.minOf { it.lat },
                    minLng = points.minOf { it.lng },
                    maxLat = points.maxOf { it.lat },
                    maxLng = points.maxOf { it.lng }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val due: StateFlow<DueInfo?> = tracks.observeTracksWithDue()
        .map { list -> list.firstOrNull { it.track.id == trackId }?.due }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<SprayHistoryEntry>> = sprays.observeSprayEvents(trackId)
        .mapLatest { events ->
            events.map { event -> SprayHistoryEntry(event, sprays.getSprayEventProductLines(event.id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Estimated treated area, if the track records a swath width. */
    val areaSqm: Double?
        get() = track.value?.let { entity ->
            entity.swathWidthM?.let { width -> estimatedAreaSqm(entity.lengthM, width) }
        }

    fun exportGpx(uri: Uri) {
        viewModelScope.launch {
            try {
                val gpx = tracks.exportTrackGpx(trackId)
                    ?: error("That track no longer exists")
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(gpx.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open the destination file")
                _message.value = "Exported ${track.value?.name ?: "track"} as GPX"
            } catch (failure: Throwable) {
                _message.value = failure.message ?: "Export failed"
            }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { tracks.deleteTrack(trackId) }
                .onFailure { _message.value = it.message ?: "Could not delete the track" }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context, trackId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    TrackDetailViewModel(
                        trackId = trackId,
                        tracks = TrackRepository(database),
                        sprays = SprayRepository(database),
                        settingsRepository = SettingsRepository(appContext),
                        context = appContext
                    )
                }
            }
        }
    }
}
