package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.doc.ArcGisDocTracks
import nz.mckenzie.sprayday.doc.DocTracksResult
import nz.mckenzie.sprayday.doc.DocTracksSource
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.DocTrackQuery
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.DevicePosition

/**
 * The DOC Tracks browser: search DOC's track network, tick the ones you work, import them.
 *
 * Searching happens at DOC's service, not on the phone - the whole dataset is 3,255 tracks and the
 * operator wants a handful - so a search is a name and/or a place, and what comes back is a page of
 * tracks. Importing one is the same `createAsset` every drawn or imported track goes through, so a
 * DOC track is a track like any other the moment it lands: it can be edited, sprayed, given a block.
 */
class DocTracksViewModel(
    private val assets: AssetRepository,
    private val source: DocTracksSource
) : ViewModel() {

    private val _name = MutableStateFlow("")
    val name: StateFlow<String> = _name

    private val _nearMe = MutableStateFlow(false)
    val nearMe: StateFlow<Boolean> = _nearMe

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _results = MutableStateFlow<List<DocTrack>>(emptyList())
    val results: StateFlow<List<DocTrack>> = _results

    private val _selected = MutableStateFlow<Set<Long>>(emptySet())
    val selected: StateFlow<Set<Long>> = _selected

    /** Whether a search has been made at all, so the screen can tell "no results" from "not yet". */
    private val _searched = MutableStateFlow(false)
    val searched: StateFlow<Boolean> = _searched

    /** The newest fix, so "Near me" searches from where the phone is. Null until there is one. */
    private val fix = MutableStateFlow<GeoPoint?>(null)

    init {
        viewModelScope.launch {
            DevicePosition.updates(viewModelScope).collect { position -> fix.value = position }
        }
    }

    fun onNameChange(value: String) {
        _name.value = value
    }

    fun onNearMeChange(value: Boolean) {
        _nearMe.value = value
    }

    /** Tick or untick a track by its service key. */
    fun toggle(objectId: Long) {
        _selected.value = if (objectId in _selected.value) {
            _selected.value - objectId
        } else {
            _selected.value + objectId
        }
    }

    fun search() {
        val name = _name.value.trim()
        val near = if (_nearMe.value) fix.value else null
        if (name.isEmpty() && near == null) {
            _message.value = if (_nearMe.value) {
                "Waiting for a location fix. Try again in a moment."
            } else {
                "Type part of a track's name, or turn on Near me."
            }
            return
        }

        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            when (val result = source.search(DocTrackQuery(nameContains = name, near = near, radiusKm = RADIUS_KM))) {
                is DocTracksResult.Found -> {
                    _results.value = result.tracks
                    _selected.value = emptySet()
                    _searched.value = true
                    _message.value = when {
                        result.tracks.isEmpty() -> null
                        // The service hands back a page, not the whole answer; say so rather than
                        // letting a capped search look like a complete one.
                        result.tracks.size >= DocTrackQuery.DEFAULT_LIMIT ->
                            "Showing the first ${result.tracks.size} matches. Narrow the search to see the rest."
                        else -> null
                    }
                }
                is DocTracksResult.Failed -> {
                    _results.value = emptyList()
                    _searched.value = true
                    _message.value = result.message
                }
            }
            _busy.value = false
        }
    }

    /** Import every ticked track as its own asset, named as DOC names it. */
    fun importSelected() {
        val picked = _results.value.filter { it.objectId in _selected.value }
        if (picked.isEmpty()) return

        viewModelScope.launch {
            _busy.value = true
            val imported = picked.count { track ->
                runCatching {
                    assets.createAsset(name = track.name, geometry = AssetGeometry(track.reading.paths))
                }.isSuccess
            }
            _selected.value = emptySet()
            _message.value = if (imported == picked.size) {
                "Imported $imported DOC ${if (imported == 1) "track" else "tracks"}."
            } else {
                "Imported $imported of ${picked.size} DOC tracks."
            }
            _busy.value = false
        }
    }

    companion object {
        private const val RADIUS_KM = 25.0

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    DocTracksViewModel(
                        assets = AssetRepository(SprayDayDatabase.get(appContext)),
                        source = ArcGisDocTracks()
                    )
                }
            }
        }
    }
}
