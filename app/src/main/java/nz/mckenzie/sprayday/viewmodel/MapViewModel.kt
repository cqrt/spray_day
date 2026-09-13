package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.TrackWithDue
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.map.TrackGeoJson
import nz.mckenzie.sprayday.map.TrackLine

/**
 * Feeds the map: the due-status of every track plus the GeoJSON MapLibre draws.
 */
class MapViewModel(
    private val trackRepository: TrackRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val linzApiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    val tracksWithDue: StateFlow<List<TrackWithDue>> = trackRepository.observeTracksWithDue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val geometryByTrack = MutableStateFlow<Map<Long, List<GeoPoint>>>(emptyMap())

    /** Bounding box of all planned track geometry, used to frame the camera. */
    val trackBounds: StateFlow<LatLngBounds?> = geometryByTrack
        .map { geometry ->
            val points = geometry.values.flatten()
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

    val trackGeoJson: StateFlow<String> = combine(tracksWithDue, geometryByTrack) { tracks, geometry ->
        TrackGeoJson.build(
            tracks.map { item ->
                TrackLine(
                    trackId = item.track.id,
                    name = item.track.name,
                    colorHex = TrackColors.forStatus(item.due.status),
                    points = geometry[item.track.id].orEmpty()
                )
            }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        TrackGeoJson.build(emptyList())
    )

    init {
        viewModelScope.launch {
            tracksWithDue.collect { tracks ->
                geometryByTrack.value = tracks.associate { item ->
                    item.track.id to trackRepository.getTrackGeometry(item.track.id)
                }
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    MapViewModel(
                        trackRepository = TrackRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
