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
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.map.TrackColors
import nz.mckenzie.sprayday.map.TrackGeoJson
import nz.mckenzie.sprayday.map.TrackLine

/**
 * Draws a new track by tapping the map.
 *
 * The draft is held in memory only - nothing is persisted until Save, so an
 * abandoned sketch leaves no trace in the database.
 */
class DrawTrackViewModel(
    private val tracks: TrackRepository,
    settingsRepository: SettingsRepository
) : ViewModel() {

    /** Needed to render the basemap behind the drawing. */
    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    private val _points = MutableStateFlow<List<GeoPoint>>(emptyList())
    val points: StateFlow<List<GeoPoint>> = _points

    val lengthM: StateFlow<Double> = _points
        .map { polylineLengthMeters(it) }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0.0)

    /** The in-progress line, drawn yellow so it is distinct from saved tracks. */
    val draftGeoJson: StateFlow<String> = _points
        .map { draft ->
            TrackGeoJson.build(
                listOf(TrackLine(trackId = DRAFT_ID, name = "Draft", colorHex = TrackColors.YELLOW, points = draft))
            )
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), TrackGeoJson.build(emptyList()))

    private val _savedTrackId = MutableStateFlow<Long?>(null)

    /**
     * The track just saved, or null when there is nothing to act on.
     *
     * This is a **one-shot signal**, not state: the screen navigates away and
     * then calls [consumeSaveResult]. Without consuming it, a view model that
     * outlives the screen (which is exactly what a ViewModel does) would fire
     * the navigation again the next time the screen is opened - which is what
     * made the draw screen flash and close on the second use.
     */
    val savedTrackId: StateFlow<Long?> = _savedTrackId

    /** Call once the save has been acted on, so it cannot fire twice. */
    fun consumeSaveResult() {
        _savedTrackId.value = null
    }

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    val canSave: StateFlow<Boolean> = _points
        .map { it.size >= 2 }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), false)

    fun addPoint(latitude: Double, longitude: Double) {
        _points.value = _points.value + GeoPoint(lat = latitude, lng = longitude)
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
        if (draft.size < 2) {
            _message.value = "Tap the map at least twice to draw a track"
            return
        }
        viewModelScope.launch {
            runCatching {
                tracks.createTrack(
                    name = name.trim().ifBlank { "New track" },
                    geometry = draft
                )
            }.onSuccess { trackId ->
                _savedTrackId.value = trackId
            }.onFailure { failure ->
                _message.value = failure.message ?: "Could not save the track"
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
                    DrawTrackViewModel(
                        tracks = TrackRepository(SprayDayDatabase.get(appContext)),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
