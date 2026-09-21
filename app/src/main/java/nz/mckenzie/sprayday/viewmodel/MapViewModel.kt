package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.distinctUntilChanged
import kotlinx.coroutines.flow.flowOn
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.AssetSprayCoverage
import nz.mckenzie.sprayday.data.MinuteTicker
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetCoverageStretches
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetHitTest
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.map.AssetStretch
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource
import nz.mckenzie.sprayday.tracking.LocationUnavailable
import nz.mckenzie.sprayday.tracking.frameOnDevice

/**
 * A camera move the operator asked for, carrying a counter.
 *
 * The counter is what makes a second tap do anything at all: two requests to put the camera
 * in the same place are an equal value, and a state flow whose value has not changed does not
 * emit - so tapping "where am I" twice in a row would otherwise move the map once.
 */
data class RecentreRequest(val bounds: LatLngBounds, val count: Int)

/**
 * Feeds the map: the due-status of every track plus the GeoJSON MapLibre draws.
 */
class MapViewModel(
    private val assetRepository: AssetRepository,
    private val settingsRepository: SettingsRepository,
    private val locationSource: LocationSource,
    /**
     * The due-status clock. A parameter so a test can tick it, rather than having to
     * wait a real minute to find out what happens on the next tick.
     */
    private val dueNow: Flow<Long> = MinuteTicker.minutes(),
    /** Injected so a test can count how often a line is re-read from the database. */
    private val loadGeometry: suspend (Long) -> AssetGeometry = assetRepository::getAssetGeometry,
    /** Injected for the same reason: the sprays the map reads to colour part of a line. */
    private val loadCoverage: suspend (Long) -> AssetSprayCoverage = assetRepository::getSprayCoverage
) : ViewModel() {

    val linzApiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), "")

    /** Which map to draw under the work: the operator's choice in Settings. */
    val basemap: StateFlow<Basemap> = settingsRepository.basemap
        .stateIn(viewModelScope, SharingStarted.Eagerly, Basemap.DEFAULT)

    /**
     * The layers of the work this map is not drawing.
     *
     * A stored choice rather than a screen's, because it is the same map every time it is opened:
     * an operator who has cleared the roads off it once does not want them back tomorrow. What is
     * held here is what is *hidden*, so the map an install has never touched draws everything -
     * see [AssetLayer].
     */
    val hiddenLayers: StateFlow<Set<AssetLayer>> = settingsRepository.hiddenMapLayers
        .stateIn(viewModelScope, SharingStarted.Eagerly, emptySet())

    val assetsWithDue: StateFlow<List<AssetWithDue>> = assetRepository.observeAssetsWithDue(dueNow)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val geometryByTrack = MutableStateFlow<Map<Long, AssetGeometry>>(emptyMap())

    /** The sprays that could colour part of a line, per asset. */
    private val coverageByTrack = MutableStateFlow<Map<Long, AssetSprayCoverage>>(emptyMap())

    /**
     * The clock the map colours against, read as a value.
     *
     * A stretch's colour is worked out while the GeoJSON is being built, and the only thing
     * that changes it is a *day* passing - green to yellow - so a value up to a minute behind
     * the tick that caused the redraw cannot change what any stretch is coloured.
     */
    private val clock: StateFlow<Long> = dueNow.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        System.currentTimeMillis()
    )

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

    /**
     * The camera move the operator asks for with the locate button.
     *
     * Where the phone *is* is not this view model's business any more: the marker belongs to
     * the map, so that every map in the app draws one rather than only the screens that thought
     * to collect a stream. See [nz.mckenzie.sprayday.tracking.DevicePosition] and
     * [nz.mckenzie.sprayday.map.BasemapView]. What is left here is what this screen alone asks
     * of the location: the frame for a first look, and this - the move that answers a tap.
     */
    private val _recentre = MutableStateFlow<RecentreRequest?>(null)
    val recentre: StateFlow<RecentreRequest?> = _recentre

    private val _locationNotice = MutableStateFlow<String?>(null)

    /**
     * Why there is no dot, for the case where the operator asks and the phone cannot say.
     *
     * The alternative is a button that does nothing visible, which reads as a broken button
     * rather than as an app that is not allowed to know where it is.
     */
    val locationNotice: StateFlow<String?> = _locationNotice

    fun clearLocationNotice() {
        _locationNotice.value = null
    }

    /** The permission was refused, so there will be no marker: the screen says why, once. */
    fun onLocationRefused() {
        _locationNotice.value = LocationUnavailable.MESSAGE
    }

    /**
     * Puts the camera back on the operator.
     *
     * Asked for rather than followed, because this map is also for reading the work: a camera
     * that keeps swinging back to the phone is a camera you cannot pan across a block.
     */
    fun recentreOnDevice() {
        viewModelScope.launch {
            val frame = locationSource.frameOnDevice()
            if (frame == null) {
                _locationNotice.value = LocationUnavailable.MESSAGE
                return@launch
            }
            _locationNotice.value = null
            _recentre.value = RecentreRequest(frame, (_recentre.value?.count ?: 0) + 1)
        }
    }

    val assetGeoJson: StateFlow<String> = combine(
        assetsWithDue,
        geometryByTrack,
        coverageByTrack
    ) { tracks, geometry, coverage ->
        val now = clock.value
        AssetGeoJson.build(
            tracks.map { item ->
                val assetGeometry = geometry[item.asset.id] ?: AssetGeometry.NONE
                val shape = AssetShape.fromStorage(item.asset.shape)
                AssetLine(
                    assetId = item.asset.id,
                    name = item.asset.name,
                    colorHex = AssetColors.forStatus(item.due.status),
                    points = assetGeometry.line,
                    sideTracks = assetGeometry.sideTracks,
                    kind = AssetKind.fromStorage(item.asset.kind),
                    shape = shape,
                    stretches = stretchesFor(item, assetGeometry.paths, coverage[item.asset.id], shape, now)
                )
            }
        )
    }.flowOn(Dispatchers.Default).stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AssetGeoJson.build(emptyList())
    )

    /**
     * The stretches a track is drawn as, or nothing at all when it is all in one state.
     *
     * Nothing at all is the common case and it costs nothing to detect: an asset with no
     * recent spray has no part worth walking, and the whole line is coloured by its traffic
     * light as it always was. A place has no length to cut up, and a path needs two points
     * before there is anything to walk along.
     */
    private fun stretchesFor(
        item: AssetWithDue,
        paths: List<List<GeoPoint>>,
        coverage: AssetSprayCoverage?,
        shape: AssetShape,
        nowEpochMs: Long
    ): List<AssetStretch> {
        if (shape != AssetShape.LINE || paths.none { it.size >= 2 }) return emptyList()
        if (coverage == null) return emptyList()
        if (coverage.passes.isEmpty() && coverage.lastWithoutRecordingAtEpochMs == null) return emptyList()

        return AssetCoverageStretches.of(
            planned = paths,
            passes = coverage.passes,
            lastWithoutRecordingAtEpochMs = coverage.lastWithoutRecordingAtEpochMs,
            intervalDays = item.asset.intervalDays,
            leadDays = AssetEntity.DEFAULT_LEAD_DAYS,
            nowEpochMs = nowEpochMs,
            passesRequired = item.asset.passesRequired,
            separationM = item.asset.passSeparationM
        )
    }

    init {
        viewModelScope.launch {
            // One decision, in order: the work if there is any, otherwise the phone.
            val fromTracks = runCatching { assetRepository.assetBounds() }.getOrNull()
            _initialFrame.value = fromTracks ?: locationSource.frameOnDevice()
        }

        viewModelScope.launch {
            // The clock ticks every minute and re-emits the same tracks, so the work is
            // keyed on what would actually change the drawn lines: which tracks exist, how
            // long each line is (which is what a redraw changes), and how many times each
            // has been sprayed (which is what changes the colour of part of a line).
            // Re-reading every line from the database once a minute was work on a map that
            // had not changed.
            assetsWithDue
                .map { tracks ->
                    tracks.map { TrackKey(it.asset.id, it.asset.lengthM, it.sprayCount) }
                        .sortedBy { it.assetId }
                }
                .distinctUntilChanged()
                .collect { keys ->
                    geometryByTrack.value = keys.associate { it.assetId to loadGeometry(it.assetId) }
                    coverageByTrack.value = keys.associate { it.assetId to loadCoverage(it.assetId) }
                }
        }
    }

    /**
     * What makes the map re-read from the database: which tracks exist, how long each line
     * is, and how many times each has been sprayed.
     *
     * The count is in there because a spray does not change a line's geometry and does
     * change what part of it is drawn in which colour - without it, the first half of a
     * track would keep looking the way it did before the pass that sprayed it.
     */
    private data class TrackKey(val assetId: Long, val lengthM: Double, val sprayCount: Int)

    /** The track under a tap on the map, or null when the tap was not on one. */
    fun assetAt(lat: Double, lng: Double, radiusM: Double = AssetHitTest.DEFAULT_TOLERANCE_M): Long? =
        AssetHitTest.nearest(geometryByTrack.value, lat, lng, radiusM)

    /**
     * Hides or shows one layer of the work, leaving the others as they are.
     *
     * Written straight through to the preference rather than held here and saved later: a map has
     * no Save button, and a choice that is forgotten when the app is closed is worse than no
     * choice at all. The repository does the read and the write together, so two taps in a row
     * accumulate rather than the second undoing the first.
     */
    fun setLayerHidden(layer: AssetLayer, hidden: Boolean) {
        viewModelScope.launch { settingsRepository.hideMapLayer(layer, hidden) }
    }

    /** Puts every layer back on the map: one switch to undo the lot. */
    fun showEveryLayer() {
        viewModelScope.launch { settingsRepository.setHiddenMapLayers(emptySet()) }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

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
