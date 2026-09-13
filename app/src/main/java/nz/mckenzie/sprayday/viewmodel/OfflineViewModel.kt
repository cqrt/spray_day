package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.CancellationException
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.map.TrackGeoJson
import nz.mckenzie.sprayday.map.TrackLine
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.offline.OfflineAreaManager
import nz.mckenzie.sprayday.offline.OfflineAreaPlan
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.offline.TileStoreSummary
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource

/**
 * Where the area offered for download came from. The screen says which, because a
 * download used to appear with no clue where it was - naming it "Spray area" while
 * sitting on a hard-coded patch of Marlborough.
 */
enum class AreaSource { MY_LOCATION, MY_TRACKS, UNKNOWN }

/**
 * Drives the offline-area screen: shows exactly which area would be cached and what
 * it would cost, then reports progress live and manages what is stored on the device.
 *
 * The area is centred on the operator, not on a guess: their current location if the
 * app may know it, otherwise the middle of their own tracks. With neither there is
 * nothing sensible to offer, and the screen says so instead of quietly caching
 * somewhere they have never been.
 */
class OfflineViewModel(
    private val manager: OfflineAreaManager,
    private val tracks: TrackRepository,
    private val locationSource: LocationSource,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _plan = MutableStateFlow<OfflineAreaPlan?>(null)

    /** The area that would be downloaded, or null when we cannot tell where to put it. */
    val plan: StateFlow<OfflineAreaPlan?> = _plan

    private val _areaSource = MutableStateFlow(AreaSource.UNKNOWN)
    val areaSource: StateFlow<AreaSource> = _areaSource

    /** The outline of the area, drawn on the preview map so it is unmistakable. */
    val previewGeoJson: StateFlow<String> = _plan
        .map { area ->
            if (area == null) {
                TrackGeoJson.build(emptyList())
            } else {
                TrackGeoJson.build(
                    listOf(
                        TrackLine(
                            trackId = AREA_OUTLINE_ID,
                            name = area.name,
                            colorHex = AREA_OUTLINE_COLOUR,
                            points = outlineOf(area.bounds)
                        )
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrackGeoJson.build(emptyList()))

    val stored: StateFlow<List<OfflineArea>> = manager.observeAreas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /** The area this session is working on, so the screen can feature it. */
    private val _activeId = MutableStateFlow<Long?>(null)
    val activeId: StateFlow<Long?> = _activeId

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    private val _summary = MutableStateFlow(TileStoreSummary(0, 0))
    val summary: StateFlow<TileStoreSummary> = _summary

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    init {
        refreshSummary()
        resolveArea()
    }

    /** Decides where the area goes: where the device is, else where the tracks are. */
    private fun resolveArea() {
        viewModelScope.launch {
            val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                runCatching { locationSource.currentLocation() }.getOrNull()
            }
            if (fix != null) {
                setArea(GeoPoint(fix.lat, fix.lng), AreaSource.MY_LOCATION)
                return@launch
            }

            val bounds = runCatching { tracks.trackBounds() }.getOrNull()
            if (bounds != null) {
                setArea(
                    centre = GeoPoint(
                        lat = (bounds.minLat + bounds.maxLat) / 2.0,
                        lng = (bounds.minLng + bounds.maxLng) / 2.0
                    ),
                    source = AreaSource.MY_TRACKS
                )
                return@launch
            }

            _areaSource.value = AreaSource.UNKNOWN
            _error.value = "I do not know where you are yet, so there is nothing sensible " +
                "to cache. Allow location access by recording a track, or import a track, " +
                "and the right area will be offered here."
        }
    }

    private fun setArea(centre: GeoPoint, source: AreaSource) {
        // The area first, then where it came from: anything observing these two must
        // never see a source without the area it describes.
        _plan.value = OfflineAreaPlan.aroundCentre(
            name = when (source) {
                AreaSource.MY_LOCATION -> "Around my location"
                else -> "Around my tracks"
            },
            centre = centre,
            radiusKm = DEFAULT_RADIUS_KM
        )
        _areaSource.value = source
        _error.value = null
    }

    fun download() = runDownload(existingId = null)

    /** Continues an area that stopped short; tiles already held are skipped. */
    fun resume(areaId: Long) = runDownload(existingId = areaId)

    fun delete(areaId: Long) {
        viewModelScope.launch {
            runCatching { manager.deleteArea(areaId) }
            if (_activeId.value == areaId) _activeId.value = null
            refreshSummary()
        }
    }

    /** Reclaims the disk space held by every downloaded tile. */
    fun clearTiles() {
        viewModelScope.launch {
            runCatching { manager.clearTiles() }
            _activeId.value = null
            refreshSummary()
        }
    }

    private fun runDownload(existingId: Long?) {
        val key = requireKey() ?: return
        if (_working.value) return

        val area = _plan.value
        if (existingId == null && area == null) {
            _error.value = "There is no area to download yet."
            return
        }

        viewModelScope.launch {
            _working.value = true
            _error.value = null
            try {
                val id = existingId ?: manager.createArea(area!!).id
                _activeId.value = id
                manager.download(id, key)
            } catch (cancelled: CancellationException) {
                // Leaving the screen is not a failure; the download resumes later.
                throw cancelled
            } catch (failure: Throwable) {
                _error.value = failure.message ?: "Download failed"
            } finally {
                _working.value = false
                refreshSummary()
            }
        }
    }

    private fun requireKey(): String? {
        val key = apiKey.value
        if (key.isBlank()) {
            _error.value = "Add a LINZ Basemaps key before downloading an area."
            return null
        }
        return key
    }

    private fun refreshSummary() {
        viewModelScope.launch {
            _summary.value = runCatching { manager.summary() }.getOrDefault(TileStoreSummary(0, 0))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val LOCATION_TIMEOUT_MS = 5_000L

        /** A block big enough to spray from, small enough to be quick to download. */
        const val DEFAULT_RADIUS_KM = 3.0

        private const val AREA_OUTLINE_ID = -1L

        /** White reads over aerial imagery at any zoom, where a due-status colour would not. */
        private const val AREA_OUTLINE_COLOUR = "#FFFFFF"

        /** The area as a closed loop, which is all the map's line layer needs. */
        private fun outlineOf(bounds: LatLngBounds): List<GeoPoint> = listOf(
            GeoPoint(bounds.minLat, bounds.minLng),
            GeoPoint(bounds.maxLat, bounds.minLng),
            GeoPoint(bounds.maxLat, bounds.maxLng),
            GeoPoint(bounds.minLat, bounds.maxLng),
            GeoPoint(bounds.minLat, bounds.minLng)
        )

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    OfflineViewModel(
                        manager = OfflineAreaManager(
                            // The same store the tile server serves from, so what
                            // is downloaded here is what the map reads offline.
                            store = TileServerHolder.store(appContext),
                            dao = database.offlineAreaDao()
                        ),
                        tracks = TrackRepository(database),
                        locationSource = FusedLocationSource(appContext),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
