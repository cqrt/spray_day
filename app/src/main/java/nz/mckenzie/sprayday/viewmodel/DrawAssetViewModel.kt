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
import nz.mckenzie.sprayday.domain.geo.haversineMeters
import nz.mckenzie.sprayday.domain.geo.nearestPointOnPolyline
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.AssetGeoJson
import nz.mckenzie.sprayday.map.AssetHitTest
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
    private val locationSource: LocationSource,
    /**
     * The track being **changed**, or null when a new one is being drawn.
     *
     * The same screen does both because the work is the same: tapping vertices onto a map. What differs
     * is where the taps start from (the stored geometry, not nothing) and what Save does (replace this
     * track's geometry, not make a new one).
     */
    private val editingAssetId: Long? = null
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

    /** True when an existing track is being changed rather than a new one drawn. */
    val editing: StateFlow<Boolean> = MutableStateFlow(editingAssetId != null)

    /**
     * The name of the track being changed, for the screen's own title.
     *
     * Null while a new one is being drawn: that name is asked for at Save, and nowhere else.
     */
    private val _trackName = MutableStateFlow<String?>(null)
    val trackName: StateFlow<String?> = _trackName

    /**
     * Opens a track that is already drawn: its geometry, what it is, and a frame around it.
     *
     * The frame is the **track**, not the phone. Changing a line is not drawing where you are standing:
     * the operator is looking at a track drawn weeks ago, and may be nowhere near it - which is the
     * whole reason this screen can now be opened from the track's own page.
     */
    private suspend fun openForEditing(assetId: Long) {
        val asset = runCatching { assetRepository.getAsset(assetId) }.getOrNull()
        if (asset == null) {
            _message.value = "That track is not on the phone any more."
            return
        }
        _trackName.value = asset.name
        // Read with its shape, so a record written before the types existed opens as what it was:
        // a fenceline if it is a line, a place if it is a spot.
        _kind.value = AssetKind.fromStorage(asset.kind, AssetShape.fromStorage(asset.shape))
        _shape.value = AssetShape.fromStorage(asset.shape)

        val geometry = runCatching { assetRepository.getAssetGeometry(assetId) }
            .getOrDefault(AssetGeometry.NONE)
        _paths.value = geometry.paths
        _initialFrame.value = frameAround(geometry.points)
    }

    /**
     * A box to open the camera on, wide enough to be a frame rather than a point of maximum zoom.
     *
     * A place is one coordinate and a two-vertex line is a straight edge, so the box is widened by the
     * same 0.01° the phone's own frame uses - about a kilometre each way. Opening the map onto a
     * zero-area box would drop the operator at the deepest zoom the tiles have, looking at one pixel.
     */
    private fun frameAround(points: List<GeoPoint>): LatLngBounds? {
        if (points.isEmpty()) return null
        val pad = FRAME_PAD_DEGREES
        return LatLngBounds(
            minLat = points.minOf { it.lat } - pad,
            minLng = points.minOf { it.lng } - pad,
            maxLat = points.maxOf { it.lat } + pad,
            maxLng = points.maxOf { it.lng } + pad
        )
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
     * A side track leaves the track at **a vertex of the line**, and there are two ways to have one: draw the
     * line as far as the junction and press *Side track* - the way a track is drawn from scratch, one point
     * after another - or tap the track where the spur branches off, which puts a vertex in there and makes it
     * the junction (see [addPoint]). Either way the join is exact, because the side track's first vertex is
     * that line vertex rather than a point near it.
     */
    private val _drawing = MutableStateFlow(0)

    /**
     * The line vertex a side track will leave from, or null for the track's own end.
     *
     * Held as an index because a junction *is* a vertex of the line, and cleared whenever the line changes
     * shape underneath it (undo, clear, back to the track) - a stale index would hang a spur off the wrong
     * point, which is worse than falling back to the end.
     */
    private val _junction = MutableStateFlow<Int?>(null)

    /** Whether a tap has picked the place a side track leaves from, for the words under the map. */
    val junctionPicked: StateFlow<Boolean> = _junction
        .map { it != null }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

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
     * Opening on the phone, or on the track being changed - and **where this sits matters**.
     *
     * `viewModelScope` is `Dispatchers.Main.immediate`, and [openForEditing] writes half the fields in
     * this class. Put this block above them and a coroutine that answers without a round trip can run
     * before the constructor has finished - a null field and an NPE, which is what crashed the settings
     * screen in CI (see `SettingsViewModel`, v0.6.41). Below them, there is no window: the assignments
     * happen before the launch that can read them.
     */
    init {
        viewModelScope.launch {
            val editing = editingAssetId
            if (editing == null) {
                _initialFrame.value = locationSource.frameOnDevice()
            } else {
                openForEditing(editing)
            }
        }
    }

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
     * Picks what is being drawn, and with it the shape.
     *
     * There is no separate shape to pick: three kinds are lines and five are places, and the kind
     * decides which (see [AssetKind.shape]). Choosing a place keeps the last tap and drops the rest -
     * a place is one coordinate and the most recent tap is the one that was meant - and a place has no
     * side tracks, because there is nothing for one to hang off.
     */
    fun chooseKind(chosen: AssetKind) {
        _kind.value = chosen
        _shape.value = chosen.shape
        if (chosen.shape == AssetShape.POINT) {
            _paths.value = _paths.value.firstOrNull()?.takeIf { it.isNotEmpty() }
                ?.let { line -> listOf(listOf(line.last())) }
                .orEmpty()
            _drawing.value = 0
        }
        _message.value = null
    }

    /**
     * Adds a tap's point to what is being drawn.
     *
     * [tapRadiusM] is the map's fingertip, which answers *which* line the tap was about, and
     * [onTheLineRadiusM] is a drawn line's own width at that zoom, which answers *where on it*. Two
     * numbers because a tap here means one of two things, and the app has to be able to tell them
     * apart: away from the line it carries the line on, and on the line it puts the point **on** it,
     * where a side track may leave from.
     *
     * A spot moves to wherever it was last tapped; a line - or the side track being drawn - grows.
     */
    fun addPoint(
        latitude: Double,
        longitude: Double,
        tapRadiusM: Double = AssetHitTest.DEFAULT_TOLERANCE_M,
        onTheLineRadiusM: Double = AssetHitTest.DEFAULT_ON_THE_LINE_TOLERANCE_M
    ) {
        val point = GeoPoint(lat = latitude, lng = longitude)
        // A spot moves to wherever it was last tapped; a line - or the side track being drawn - grows.
        if (_shape.value == AssetShape.POINT) {
            _paths.value = listOf(listOf(point))
            _message.value = null
            return
        }

        val paths = _paths.value

        // A tap **on the track** says where a side track leaves it, rather than extending the line out to
        // wherever the finger landed. This is what makes a junction reachable in the middle of a track: the
        // point goes into the line there - a junction has to be a vertex of the line - and the next "Side
        // track" hangs the spur off it. Both tolerances are the map's own answers for the zoom the
        // operator is working at.
        //
        // Two things keep this from swallowing the taps that meant to carry the line on, which is what it
        // did from v0.6.34 to v0.6.40 - a point counted, and nothing drawn:
        //
        //   * the tap has to be within **the drawn line's own width** of it, not a fingertip's. While
        //     drawing, every tap is near the line; a fingertip of slack takes in the whole paddock beside
        //     it, and a point put *on* the line is a point that moves nothing;
        //   * a tap at the **end** of the line always carries it on. That is where drawing continues, and
        //     a junction there would be the same point a side track leaves from anyway
        //     ([startSideTrack] uses the end when nothing was picked), so nothing is lost by it.
        if (paths.isNotEmpty() && paths.first().size >= 2 && _drawing.value == 0) {
            val line = paths.first()
            val end = line.last()
            val atTheEnd = haversineMeters(point.lat, point.lng, end.lat, end.lng) <= tapRadiusM
            val onTheLine = nearestPointOnPolyline(point, line)
            if (!atTheEnd && onTheLine != null && onTheLine.distanceM <= onTheLineRadiusM) {
                _paths.value = paths.mapIndexed { at, path ->
                    if (at != 0) path else path.toMutableList().apply { add(onTheLine.indexAfter, onTheLine.point) }
                }
                _junction.value = onTheLine.indexAfter
                _message.value = "A side track will leave the track here. Press \"Side track\", or tap " +
                    "clear of the line to carry the line on."
                return
            }
        }

        if (paths.isEmpty()) {
            _paths.value = listOf(listOf(point))
        } else {
            val index = _drawing.value.coerceIn(0, paths.size - 1)
            _paths.value = paths.mapIndexed { at, path -> if (at == index) path + point else path }
        }
        _message.value = null
    }

    /**
     * Starts a side track from the place picked out on the line, or from where the track ends.
     *
     * The first vertex of the side track **is** the chosen line vertex, not a copy of it: the two paths meet
     * at the same pair of numbers, which is what the storage and the rules read as a join rather than as two
     * lines that happen to be near each other. Pressing this with no line to hang off does nothing, and the
     * button is not offered until there is one.
     */
    fun startSideTrack() {
        if (_shape.value != AssetShape.LINE || _drawing.value > 0) return
        val line = _paths.value.firstOrNull().orEmpty()
        if (line.size < 2) {
            _message.value = "Draw the track a little further first: a side track leaves it somewhere."
            return
        }
        // The junction the operator tapped on, or the end of the line: a junction that is no longer a vertex
        // of the line (an Undo can take it away) falls back to the end rather than hanging a spur off nothing.
        val at = _junction.value?.takeIf { it in line.indices } ?: line.lastIndex
        _paths.value = _paths.value + listOf(listOf(line[at]))
        _drawing.value = _paths.value.size - 1
        _junction.value = null
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
        // A junction is an index into the line, and an Undo moves the line underneath it: rather than guess
        // which vertex was meant, the pick goes and the next side track leaves the end again.
        _junction.value = null
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
        _junction.value = null
    }

    /**
     * What is saved is the track as it stands: the line, and every side track that got far enough
     * to be one. A side track still being drawn is dropped rather than refused - a single tap on
     * the map is not something the operator meant to keep, and the line is what they came to save.
     */
    private fun normalisedPaths(): List<List<GeoPoint>> =
        _paths.value.filterIndexed { index, path -> index == 0 || path.size >= 2 }

    private fun refuseToSave(): Boolean {
        if (canSave(normalisedPaths(), _shape.value)) return false
        _message.value = when (_shape.value) {
            AssetShape.POINT -> "Tap the map where it is, then save"
            AssetShape.LINE -> "Tap the map at least twice to draw a line"
        }
        return true
    }

    fun save(name: String) {
        val shape = _shape.value
        val kind = _kind.value
        if (refuseToSave()) return
        val paths = normalisedPaths()
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

    /**
     * Writes a changed track back, and nothing else.
     *
     * The geometry is **replaced**, the whole of it, because that is what the operator has just been
     * looking at: the paths they moved and the spurs they added are the track now. One write means a
     * length can never belong to a shape it did not come from - `replaceGeometry` stores the vertices
     * and the length they add up to in one transaction, exactly as it does for a track drawn from
     * scratch.
     *
     * The name, the kind and the block are not touched here: those are the details form's, and a screen
     * that draws lines has no business rewriting a name nobody typed on it.
     */
    fun saveChanges() {
        val assetId = editingAssetId ?: return
        if (refuseToSave()) return
        val paths = normalisedPaths()
        viewModelScope.launch {
            runCatching {
                assetRepository.replaceGeometry(assetId, AssetGeometry(paths))
            }.onSuccess {
                _savedAssetId.value = assetId
            }.onFailure { failure ->
                _message.value = failure.message ?: "Could not save the change"
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L
        private const val DRAFT_ID = -1L

        /** The same widening [nz.mckenzie.sprayday.tracking.frameOnDevice] uses, in degrees. */
        private const val FRAME_PAD_DEGREES = 0.01

        /**
         * [editingAssetId] opens an existing track to change it rather than a blank map to draw on.
         *
         * One factory for both because the screen is one screen; the difference is what it is handed to
         * start from, and the view model keeps it apart from there.
         */
        fun factory(context: Context, editingAssetId: Long? = null): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    DrawAssetViewModel(
                        assetRepository = AssetRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext),
                        locationSource = FusedLocationSource(appContext),
                        editingAssetId = editingAssetId
                    )
                }
            }
        }
    }
}
