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
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
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

    /**
     * The track being drawn: **a line, and the side tracks hanging off it**.
     *
     * `paths[0]` is the line and the rest are side tracks in the order they were drawn, which is the
     * shape the database holds (`asset_points.pathIndex`) and the shape
     * [nz.mckenzie.sprayday.domain.geo.AssetGeometry] speaks - so a draft is saved as the thing it is
     * rather than being flattened on the way in.
     */
    private val _paths = MutableStateFlow<List<List<GeoPoint>>>(emptyList())
    val paths: StateFlow<List<List<GeoPoint>>> = _paths

    /**
     * Which path the next tap adds to: 0 is the line, 1 and up are side tracks.
     *
     * A side track leaves the track **where the track currently ends**, so the junction is a vertex of
     * the line and the spur it starts is that same vertex - the join is exact, and nothing has to be
     * snapped or projected. The order is therefore: draw the line as far as the junction, press *Side
     * track*, tap the spur, press *Back to the track*, and carry on to the end.
     */
    private val _drawing = MutableStateFlow(0)

    /** True while a side track is being drawn, so the screen can offer the way back to the line. */
    val drawingSideTrack: StateFlow<Boolean> = _drawing
        .map { it > 0 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    private val _kind = MutableStateFlow(AssetKind.TRACK)
    val kind: StateFlow<AssetKind> = _kind

    private val _shape = MutableStateFlow(AssetShape.LINE)
    val shape: StateFlow<AssetShape> = _shape

    /** How many vertices the whole track has: the line and its side tracks together. */
    val pointCount: StateFlow<Int> = _paths
        .map { paths -> paths.sumOf { it.size } }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /** How many side tracks the track has, finished or in progress. */
    val sideTrackCount: StateFlow<Int> = _paths
        .map { paths -> (paths.size - 1).coerceAtLeast(0) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    /**
     * How long the track is: every path's own length, each counted once.
     *
     * This is the number that made the old way of drawing a spur wrong. Walk up a side track and back
     * down it as part of one line and its metres are in the line twice, so a 500 m track with a 50 m
     * spur read as 600 m of track. As its own path the spur adds its 50 m once, which is what it is.
     */
    val lengthM: StateFlow<Double> = _paths
        .map { paths -> AssetGeometry(paths).lengthM }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0.0)

    /** The in-progress track, drawn yellow so it is distinct from saved assets. */
    val draftGeoJson: StateFlow<String> = combine(_paths, _shape) { paths, shape ->
        AssetGeoJson.build(
            listOf(
                AssetLine(
                    assetId = DRAFT_ID,
                    name = "Draft",
                    colorHex = AssetColors.YELLOW,
                    points = paths.firstOrNull().orEmpty(),
                    sideTracks = paths.drop(1),
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

    val canSave: StateFlow<Boolean> = combine(_paths, _shape) { paths, shape -> canSave(paths, shape) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    /**
     * What is being drawn, which decides how many taps it takes: a line needs two
     * points to be a line at all, while a spot is complete with the one it is at.
     *
     * A side track that has one point is not a side track, and does not stop the line from being
     * saved: it is dropped rather than counted, because there is nothing there to keep.
     */
    private fun canSave(paths: List<List<GeoPoint>>, shape: AssetShape): Boolean =
        paths.firstOrNull().orEmpty().size >= if (shape == AssetShape.POINT) 1 else 2

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
     * Picks the shape. Switching to a spot keeps the last tap and drops the rest: a place is one
     * coordinate, and the most recent tap is the one that was meant - and a place has no side tracks,
     * because there is nothing for one to hang off.
     */
    fun chooseShape(chosen: AssetShape) {
        _shape.value = chosen
        if (chosen == AssetShape.POINT) {
            _paths.value = _paths.value.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { line -> listOf(listOf(line.last())) }
                .orEmpty()
            _drawing.value = 0
        }
        _message.value = null
    }

    fun addPoint(latitude: Double, longitude: Double) {
        val point = GeoPoint(lat = latitude, lng = longitude)
        // A spot moves to wherever it was last tapped; a line - or the side track being drawn - grows.
        if (_shape.value == AssetShape.POINT) {
            _paths.value = listOf(listOf(point))
            _message.value = null
            return
        }

        val paths = _paths.value
        if (paths.isEmpty()) {
            _paths.value = listOf(listOf(point))
        } else {
            val index = _drawing.value.coerceIn(0, paths.size - 1)
            _paths.value = paths.mapIndexed { at, path -> if (at == index) path + point else path }
        }
        _message.value = null
    }

    /**
     * Starts a side track where the track ends.
     *
     * The first vertex of the side track **is** the line's last vertex, not a copy of it: the two paths
     * meet at the same pair of numbers, which is what the storage and the rules read as a join rather
     * than as two lines that happen to be near each other. Pressing this with no line to hang off does
     * nothing, and the button is not offered until there is one.
     */
    fun startSideTrack() {
        if (_shape.value != AssetShape.LINE || _drawing.value > 0) return
        val line = _paths.value.firstOrNull().orEmpty()
        if (line.size < 2) {
            _message.value = "Draw the track a little further first: a side track leaves it somewhere."
            return
        }
        _paths.value = _paths.value + listOf(listOf(line.last()))
        _drawing.value = _paths.value.size - 1
        _message.value = "Tap along the side track, then press \"Back to the track\"."
    }

    /**
     * Back to the line.
     *
     * A side track that never got a second point is taken off rather than kept: it is one tap with
     * nothing on the map, and leaving it there would be a path nothing can draw or measure.
     */
    fun backToTheLine() {
        if (_drawing.value == 0) return
        _paths.value = _paths.value.filterIndexed { index, path -> index == 0 || path.size >= 2 }
        _drawing.value = 0
        _message.value = null
    }

    /**
     * Takes the last point off.
     *
     * The point that *started* a side track is the line's own vertex, so taking it off takes the side
     * track with it and hands the operator back to the line - which is what undoing it meant.
     */
    fun undo() {
        val paths = _paths.value
        if (paths.isEmpty()) return
        val index = _drawing.value.coerceIn(0, paths.size - 1)
        if (paths[index].size <= 1) {
            _paths.value = paths.filterIndexed { at, _ -> at != index }
            _drawing.value = 0
            return
        }
        _paths.value = paths.mapIndexed { at, points ->
            if (at == index) points.dropLast(1) else points
        }
    }

    fun clear() {
        _paths.value = emptyList()
        _drawing.value = 0
    }

    fun save(name: String) {
        val shape = _shape.value
        val kind = _kind.value
        // What is saved is the track as it stands: the line, and every side track that got far enough
        // to be one. A side track still being drawn is dropped rather than refused - a single tap on
        // the map is not something the operator meant to keep, and the line is what they came to save.
        val paths = _paths.value.filterIndexed { index, path -> index == 0 || path.size >= 2 }
        if (!canSave(paths, shape)) {
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
                    geometry = AssetGeometry(paths),
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
