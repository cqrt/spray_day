package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.MinuteTicker
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetHitTest
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource

/**
 * Feeds the map: the due-status of every track plus the GeoJSON MapLibre draws.
 */
class MapViewModel(
    private val assetRepository: AssetRepository,
    settingsRepository: SettingsRepository,
    private val locationSource: LocationSource,
    /**
     * The due-status clock. A parameter so a test can tick it, rather than having to
     * wait a real minute to find out what happens on the next tick.
     */
    dueNow: Flow<Long> = MinuteTicker.minutes(),
    /** Injected so a test can count how often a line is re-read from the database. */
    private val loadGeometry: suspend (Long) -> List<GeoPoint> = assetRepository::getAssetGeometry
) : ViewModel() {

    val linzApiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    val assetsWithDue: StateFlow<List<AssetWithDue>> = assetRepository.observeAssetsWithDue(dueNow)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val geometryByTrack = MutableStateFlow<Map<Long, List<GeoPoint>>>(emptyMap())

    /**
     * The frame for the camera when the map opens: the operator's own tracks, else
     * where the device is, else nothing (the map keeps its neutral country-wide view).
     *
     * Decided once, by asking the database for the tracks' bounds and only falling back
     * to a fix if there are none. It used to be a combination of live flows, which lost
     * a race: the tracks flow starts empty, so a location fix arriving first won the
     * one-and-only frame and the map stayed on the phone instead of the work.
     */
    private val _initialFrame = MutableStateFlow<LatLngBounds?>(null)
    val initialFrame: StateFlow<LatLngBounds?> = _initialFrame

    val assetGeoJson: StateFlow<String> = combine(assetsWithDue, geometryByTrack) { tracks, geometry ->
        AssetGeoJson.build(
            tracks.map { item ->
                AssetLine(
                    assetId = item.track.id,
                    name = item.track.name,
                    colorHex = AssetColors.forStatus(item.due.status),
                    points = geometry[item.track.id].orEmpty()
                )
            }
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AssetGeoJson.build(emptyList())
    )

    init {
        viewModelScope.launch {
            // One decision, in order: the work if there is any, otherwise the phone.
            val fromTracks = runCatching { assetRepository.assetBounds() }.getOrNull()
            _initialFrame.value = fromTracks ?: deviceBounds()
        }

        viewModelScope.launch {
            // The clock ticks every minute and re-emits the same tracks, so the work is
            // keyed on what would actually change the drawn lines: which tracks exist,
            // and how long each line is (which is what a redraw changes). Re-reading
            // every line from the database once a minute was work on a map that had not
            // changed.
            assetsWithDue
                .map { tracks -> tracks.map { it.track.id to it.track.lengthM }.sortedBy { it.first } }
                .distinctUntilChanged()
                .collect { keys ->
                    geometryByTrack.value = keys.associate { (assetId, _) ->
                        assetId to loadGeometry(assetId)
                    }
                }
        }
    }

    /** The track under a tap on the map, or null when the tap was not on one. */
    fun assetAt(lat: Double, lng: Double, radiusM: Double = AssetHitTest.DEFAULT_TOLERANCE_M): Long? =
        AssetHitTest.nearest(geometryByTrack.value, lat, lng, radiusM)

    /** A small frame around the device, for a first run with nothing drawn yet. */
    private suspend fun deviceBounds(): LatLngBounds? {
        val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
            runCatching { locationSource.currentLocation() }.getOrNull()
        } ?: return null

        return LatLngBounds(
            minLat = fix.lat - START_FRAME_DEGREES,
            minLng = fix.lng - START_FRAME_DEGREES,
            maxLat = fix.lat + START_FRAME_DEGREES,
            maxLng = fix.lng + START_FRAME_DEGREES
        )
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        /** Long enough for a balanced-power fix, short enough not to hold the map up. */
        private const val LOCATION_TIMEOUT_MS = 5_000L

        /** Half-width of the frame put around the device's position: about 1 km. */
        private const val START_FRAME_DEGREES = 0.01

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    MapViewModel(
                        assetRepository = AssetRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext),
                        locationSource = FusedLocationSource(appContext)
                    )
                }
            }
        }
    }
}
