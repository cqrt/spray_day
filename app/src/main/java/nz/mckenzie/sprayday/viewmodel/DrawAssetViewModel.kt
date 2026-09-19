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
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetLine
import nz.mckenzie.sprayday.tracking.FusedLocationSource
import nz.mckenzie.sprayday.tracking.LocationSource
import nz.mckenzie.sprayday.tracking.frameOnDevice

/**
 * Draws a new asset by tapping the map.
 *
 * Two shapes, because two things get created here. A line - a track, a road, the
 * fencelines along them - is tapped point by point, and its running length is shown so
 * the operator can sanity-check it before saving. A spot, which is what most
 * infrastructure is, is one tap: a trough or a table is somewhere you stop, not
 * something you travel along.
 *
 * The draft is held in memory only - nothing is persisted until Save, so an
 * abandoned sketch leaves no trace in the database.
 */
class DrawAssetViewModel(
    private val assetRepository: AssetRepository,
    settingsRepository: SettingsRepository,
    /**
     * Where the phone is, for the first frame: drawing happens where you are standing,
     * so opening on a neutral view of the country would be twenty minutes of panning.
     */
    private val locationSource: LocationSource
) : ViewModel() {

    /** Needed to render the basemap behind the drawing. */
    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Which map to draw the line on: the operator's choice in Settings. */
    val basemap: StateFlow<Basemap> = settingsRepository.basemap
        .stateIn(viewModelScope, SharingStarted.Eagerly, Basemap.DEFAULT)

    private val _initialFrame = MutableStateFlow<LatLngBounds?>(null)

    /** The frame for the camera when the map opens: around the phone, if it knows. */
    val initialFrame: StateFlow<LatLngBounds?> = _initialFrame

    init {
        viewModelScope.launch {
            _initialFrame.value = locationSource.frameOnDevice()
        }
    }

    private val _points = MutableStateFlow<List<GeoPoint>>(emptyList())
    val points: StateFlow<List<GeoPoint>> = _points

    private val _kind = MutableStateFlow(AssetKind.TRACK)
    val kind: StateFlow<AssetKind> = _kind

    private val _shape = MutableStateFlow(AssetShape.LINE)
    val shape: StateFlow<AssetShape> = _shape

    val lengthM: StateFlow<Double> = _points
        .map { polylineLengthMeters(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0.0)

    /** The in-progress line, drawn yellow so it is distinct from saved assets. */
    val draftGeoJson: StateFlow<String> = combine(_points, _shape) { draft, shape ->
        AssetGeoJson.build(
            listOf(
                AssetLine(
                    assetId = DRAFT_ID,
                    name = "Draft",
                    colorHex = AssetColors.YELLOW,
                    points = draft,
                    shape = shape
                )
            )
        )
    }.stateIn(
        viewModelScope,
        SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS),
        AssetGeoJson.build(emptyList())
    )

    private val _savedAssetId = MutableStateFlow<Long?>(null)

    /**
     * The track just saved, or null when there is nothing to act on.
     *
     * This is a **one-shot signal**, not state: the screen navigates away and
     * then calls [consumeSaveResult]. Without consuming it, a view model that
     * outlives the screen (which is exactly what a ViewModel does) would fire
     * the navigation again the next time the screen is opened - which is what
     * made the draw screen flash and close on the second use.
     */
    val savedAssetId: StateFlow<Long?> = _savedAssetId

    /** Call once the save has been acted on, so it cannot fire twice. */
    fun consumeSaveResult() {
        _savedAssetId.value = null
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val canSave: StateFlow<Boolean> = combine(_points, _shape) { draft, shape -> canSave(draft, shape) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * What is being drawn, which decides how many taps it takes: a line needs two
     * points to be a line at all, while a spot is complete with the one it is at.
     */
    private fun canSave(points: List<GeoPoint>, shape: AssetShape): Boolean =
        points.size >= if (shape == AssetShape.POINT) 1 else 2

    /**
     * Picks what is being drawn.
     *
     * Only infrastructure is offered a spot, so choosing any other kind puts the shape
     * back to a line rather than leaving a table's shape on a road.
     */
    fun chooseKind(chosen: AssetKind) {
        _kind.value = chosen
        if (chosen != AssetKind.INFRASTRUCTURE) _shape.value = AssetShape.LINE
        _message.value = null
    }

    /**
     * Picks the shape. Switching to a spot keeps the last tap and drops the rest: a
     * place is one coordinate, and the most recent tap is the one that was meant.
     */
    fun chooseShape(chosen: AssetShape) {
        _shape.value = chosen
        if (chosen == AssetShape.POINT && _points.value.size > 1) {
            _points.value = listOf(_points.value.last())
        }
        _message.value = null
    }

    fun addPoint(latitude: Double, longitude: Double) {
        val point = GeoPoint(lat = latitude, lng = longitude)
        // A spot moves to wherever it was last tapped; a line grows.
        _points.value = when (_shape.value) {
            AssetShape.POINT -> listOf(point)
            AssetShape.LINE -> _points.value + point
        }
        _message.value = null
    }

    fun undo() {
        _points.value = _points.value.dropLast(1)
    }

    fun clear() {
        _points.value = emptyList()
    }

    fun save(name: String) {
        val draft = _points.value
        val shape = _shape.value
        val kind = _kind.value
        if (!canSave(draft, shape)) {
            _message.value = when (shape) {
                AssetShape.POINT -> "Tap the map where it is, then save"
                AssetShape.LINE -> "Tap the map at least twice to draw a line"
            }
            return
        }
        viewModelScope.launch {
            runCatching {
                assetRepository.createAsset(
                    name = name.trim().ifBlank { "New ${AssetPhrase.kind(kind)}" },
                    geometry = draft,
                    kind = kind,
                    shape = shape
                )
            }.onSuccess { assetId ->
                _savedAssetId.value = assetId
            }.onFailure { failure ->
                _message.value = failure.message ?: "Could not save it"
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val DRAFT_ID = -1L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    DrawAssetViewModel(
                        assetRepository = AssetRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext),
                        locationSource = FusedLocationSource(appContext)
                    )
                }
            }
        }
    }
}
