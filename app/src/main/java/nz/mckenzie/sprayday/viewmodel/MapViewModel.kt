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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.TrackTicker
import nz.mckenzie.sprayday.data.TrackWithDue
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.map.TrackGeoJson
import nz.mckenzie.sprayday.map.TrackHitTest
import nz.mckenzie.sprayday.map.TrackLine
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource

/**
 * Feeds the map: the due-status of every track plus the GeoJSON MapLibre draws.
 */
class MapViewModel(
    private val trackRepository: TrackRepository,
    settingsRepository: SettingsRepository,
    private val locationSource: LocationSource,
    /**
     * The due-status clock. A parameter so a test can tick it, rather than having to
     * wait a real minute to find out what happens on the next tick.
     */
    dueNow: Flow<Long> = TrackTicker.minutes(),
    /** Injected so a test can count how often a line is re-read from the database. */
    private val loadGeometry: suspend (Long) -> List<GeoPoint> = trackRepository::getTrackGeometry
) : ViewModel() {

    val linzApiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    val tracksWithDue: StateFlow<List<TrackWithDue>> = trackRepository.observeTracksWithDue(dueNow)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val geometryByTrack = MutableStateFlow<Map<Long, List<GeoPoint>>>(emptyMap())

    /** Bounding box of all planned track geometry, used to frame the camera. */
    private val trackBounds: StateFlow<LatLngBounds?> = geometryByTrack
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

    /**
     * A frame around the device's own position, for the first run - before there is
     * any track to show.
     */
    private val startBounds = MutableStateFlow<LatLngBounds?>(null)

    /** Set once the tracks have actually been read, as opposed to not read yet. */
    private val tracksLoaded = MutableStateFlow(false)

    /**
     * What the camera should frame when the map opens, in order of preference: the
     * operator's own tracks, then where the device is, then nothing at all (in which
     * case the map keeps its neutral country-wide view).
     *
     * Tracks that exist but whose geometry is still loading return null rather than
     * the device position, so the camera does not first fly to a 1 km box around the
     * phone and then refuse to move to the work.
     */
    val initialFrame: StateFlow<LatLngBounds?> =
        combine(trackBounds, startBounds, tracksWithDue, tracksLoaded) { tracks, location, allTracks, loaded ->
            when {
                tracks != null -> tracks
                // Before the tracks have been read at all, an empty list means "not yet",
                // not "none". Treating the two the same framed the camera on the phone
                // whenever a location fix arrived first, and the map then stayed there:
                // one frame is applied, once.
                !loaded -> null
                allTracks.isNotEmpty() -> null
                else -> location
            }
        }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

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
            // Reading the repository directly is what tells us the difference between
            // "no tracks" and "not asked yet": the state flow above starts with an empty
            // list either way.
            trackRepository.observeTracksWithDue(dueNow).first()
            tracksLoaded.value = true
        }

        viewModelScope.launch {
            // Where the device is, for the first run. Null when location is not
            // permitted, which is fine: the tracks or the country-wide default answer
            // the same question.
            val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                runCatching { locationSource.currentLocation() }.getOrNull()
            }
            if (fix != null) {
                startBounds.value = LatLngBounds(
                    minLat = fix.lat - START_FRAME_DEGREES,
                    minLng = fix.lng - START_FRAME_DEGREES,
                    maxLat = fix.lat + START_FRAME_DEGREES,
                    maxLng = fix.lng + START_FRAME_DEGREES
                )
            }
        }

        viewModelScope.launch {
            // The clock ticks every minute and re-emits the same tracks, so the work is
            // keyed on what would actually change the drawn lines: which tracks exist,
            // and how long each line is (which is what a redraw changes). Re-reading
            // every line from the database once a minute was work on a map that had not
            // changed.
            tracksWithDue
                .map { tracks -> tracks.map { it.track.id to it.track.lengthM }.sortedBy { it.first } }
                .distinctUntilChanged()
                .collect { keys ->
                    geometryByTrack.value = keys.associate { (trackId, _) ->
                        trackId to loadGeometry(trackId)
                    }
                }
        }
    }

    /** The track under a tap on the map, or null when the tap was not on one. */
    fun trackAt(lat: Double, lng: Double, radiusM: Double = TrackHitTest.DEFAULT_TOLERANCE_M): Long? =
        TrackHitTest.nearest(geometryByTrack.value, lat, lng, radiusM)

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
                        trackRepository = TrackRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext),
                        locationSource = FusedLocationSource(appContext)
                    )
                }
            }
        }
    }
}
