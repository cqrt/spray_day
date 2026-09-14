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
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.offline.OfflineAreaDraft
import nz.mckenzie.sprayday.offline.OfflineAreaManager
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource
import nz.mckenzie.sprayday.ui.formatShortDate

/**
 * Choosing an offline area by hand: two taps on the map for the corners, the zoom
 * levels to cache, and a name to find it by later.
 *
 * The screen shows the cost of every choice as it is made - move a slider and the
 * tile count moves with it - because the difference between zoom 16 and 17 is the
 * difference between a minute and a quarter of an hour.
 */
class OfflineAreaPickerViewModel(
    private val manager: OfflineAreaManager,
    private val assetRepository: AssetRepository,
    private val locationSource: LocationSource,
    private val settings: SettingsRepository
) : ViewModel() {

    private val _draft = MutableStateFlow(
        OfflineAreaDraft(name = "Area " + formatShortDate(System.currentTimeMillis()))
    )
    val draft: StateFlow<OfflineAreaDraft> = _draft

    val apiKey: StateFlow<String> = settings.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Where the picker's map should open, so nobody starts on the wrong island. */
    private val _startBounds = MutableStateFlow<LatLngBounds?>(null)
    val startBounds: StateFlow<LatLngBounds?> = _startBounds

    /**
     * What the map draws: the box once both corners are picked, or a small marker where
     * the first corner is, so the first tap shows it landed.
     */
    val previewGeoJson: StateFlow<String> = _draft
        .map { draft ->
            val points = when {
                draft.isComplete -> outlineOf(draft.bounds!!)
                draft.firstCorner != null -> outlineOf(boxAround(draft.firstCorner!!))
                else -> emptyList()
            }
            if (points.isEmpty()) {
                AssetGeoJson.build(emptyList())
            } else {
                AssetGeoJson.build(
                    listOf(
                        AssetLine(
                            assetId = OUTLINE_ID,
                            name = "Area",
                            colorHex = OUTLINE_COLOUR,
                            points = points
                        )
                    )
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), AssetGeoJson.build(emptyList()))

    /** Progress of the download this screen started, once it is under way. */
    private val _activeId = MutableStateFlow<Long?>(null)
    val active: StateFlow<OfflineArea?> = manager.observeAreas()
        .map { areas -> areas.firstOrNull { it.id == _activeId.value } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _saved = MutableStateFlow(false)

    /**
     * True once the area is stored and its download has finished. A one-shot signal:
     * the screen consumes it as it leaves, so re-entering does not bounce straight
     * back out.
     */
    val saved: StateFlow<Boolean> = _saved

    fun consumeSaved() {
        _saved.value = false
    }

    init {
        viewModelScope.launch {
            // Open over the operator: their position, else the middle of their tracks.
            val fix = withTimeoutOrNull(LOCATION_TIMEOUT_MS) {
                runCatching { locationSource.currentLocation() }.getOrNull()
            }
            val centre = fix?.let { GeoPoint(it.lat, it.lng) }
                ?: assetRepository.assetBounds()?.let { bounds ->
                    GeoPoint(
                        lat = (bounds.minLat + bounds.maxLat) / 2.0,
                        lng = (bounds.minLng + bounds.maxLng) / 2.0
                    )
                }
            centre?.let { _startBounds.value = boxAround(it, degrees = START_FRAME_DEGREES) }
        }
    }

    /** A tap on the map: the first corner, then the opposite one. */
    fun tapAt(latitude: Double, longitude: Double) {
        _error.value = null
        _draft.value = _draft.value.withCorner(GeoPoint(latitude, longitude))
    }

    fun clearCorners() {
        _error.value = null
        _draft.value = _draft.value.withoutCorners()
    }

    fun setName(name: String) {
        _draft.value = _draft.value.copy(name = name)
    }

    fun setZoomRange(minZoom: Int, maxZoom: Int) {
        _draft.value = _draft.value.withZoomRange(minZoom, maxZoom)
    }

    /**
     * Stores the area and downloads it. Progress lands in the database as it goes, so
     * the card tracks it, and a download that is interrupted resumes from the tiles
     * already on disk.
     */
    fun download() {
        if (_working.value) return

        val current = _draft.value
        val plan = current.plan(name = current.name, fallbackName = defaultName())
        if (plan == null) {
            _error.value = "Tap two opposite corners on the map to choose the area."
            return
        }
        if (current.isTooBig) {
            _error.value = "That is ${current.tileCount} tiles, more than the " +
                "${OfflineAreaManager.MAX_TILES_PER_AREA} limit. Narrow the zoom range " +
                "or the area."
            return
        }

        viewModelScope.launch {
            _working.value = true
            _error.value = null
            try {
                // Read the key from settings now rather than trusting a cached flow:
                // tapping Download the instant the screen opens must not claim there
                // is no key.
                val key = settings.linzApiKey.first()
                if (key.isBlank()) {
                    _error.value = "Add a LINZ Basemaps key before downloading an area."
                    return@launch
                }

                val area = manager.createArea(plan)
                _activeId.value = area.id
                manager.download(area.id, key)
                _saved.value = true
            } catch (cancelled: CancellationException) {
                // Leaving the screen is not a failure: the tiles already fetched stay,
                // and the area can be resumed from the offline areas list.
                throw cancelled
            } catch (failure: Throwable) {
                _error.value = failure.message ?: "Download failed"
            } finally {
                _working.value = false
            }
        }
    }

    private fun defaultName(): String = "Area " + formatShortDate(System.currentTimeMillis())

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val LOCATION_TIMEOUT_MS = 5_000L

        /** How much of the world the picker opens on: about 4 km across. */
        private const val START_FRAME_DEGREES = 0.02

        private const val OUTLINE_ID = -1L
        private const val OUTLINE_COLOUR = "#FFFFFF"

        /** How large the marker is for a single tapped corner: roughly 60 m across. */
        private const val MARKER_DEGREES = 0.0003

        private fun boxAround(point: GeoPoint, degrees: Double = MARKER_DEGREES) = LatLngBounds(
            minLat = point.lat - degrees,
            minLng = point.lng - degrees,
            maxLat = point.lat + degrees,
            maxLng = point.lng + degrees
        )

        /** A box as a closed loop, which is all the map's line layer needs. */
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
                    OfflineAreaPickerViewModel(
                        manager = OfflineAreaManager(
                            store = TileServerHolder.store(appContext),
                            dao = database.offlineAreaDao()
                        ),
                        assetRepository = AssetRepository(database),
                        locationSource = FusedLocationSource(appContext),
                        settings = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
