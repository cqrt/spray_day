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

    /** The kilometres around the phone "Near me" looks, as typed; nonsense falls back to the default. */
    private val _radiusKm = MutableStateFlow(DEFAULT_RADIUS_KM.toInt().toString())
    val radiusKm: StateFlow<String> = _radiusKm

    /**
     * The source references of DOC tracks already on the phone.
     *
     * Read at each search and again after an import, so the list can mark a track it already has and
     * refuse to tick it - the one thing that keeps a second search from doubling the work.
     */
    private val _imported = MutableStateFlow<Set<String>>(emptySet())
    val imported: StateFlow<Set<String>> = _imported

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

    fun onRadiusChange(value: String) {
        _radiusKm.value = value
    }

    /** Tick or untick a track, unless it is one already on the phone - that one is not tickable. */
    fun toggle(track: DocTrack) {
        if (track.sourceRef in _imported.value) return
        _selected.value = if (track.objectId in _selected.value) {
            _selected.value - track.objectId
        } else {
            _selected.value + track.objectId
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
        val radius = _radiusKm.value.trim().toDoubleOrNull()?.takeIf { it > 0.0 } ?: DEFAULT_RADIUS_KM

        viewModelScope.launch {
            _busy.value = true
            _message.value = null
            when (val result = source.search(DocTrackQuery(nameContains = name, near = near, radiusKm = radius))) {
                is DocTracksResult.Found -> {
                    _results.value = result.tracks
                    _selected.value = emptySet()
                    _searched.value = true
                    // Read once per search, so every row can say whether it is already on the phone.
                    _imported.value = assets.existingSourceRefs()
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

    /** Import every ticked track as its own asset, named as DOC names it, once each. */
    fun importSelected() {
        val picked = _results.value.filter {
            it.objectId in _selected.value && it.sourceRef !in _imported.value
        }
        if (picked.isEmpty()) return

        viewModelScope.launch {
            _busy.value = true
            val imported = picked.count { track ->
                runCatching {
                    assets.createAsset(
                        name = track.name,
                        geometry = AssetGeometry(track.reading.paths),
                        sourceRef = track.sourceRef
                    )
                }.isSuccess
            }
            _selected.value = emptySet()
            // The tracks just imported are on the phone now: re-read so they read as already here.
            _imported.value = assets.existingSourceRefs()
            _message.value = if (imported == picked.size) {
                "Imported $imported DOC ${if (imported == 1) "track" else "tracks"}."
            } else {
                "Imported $imported of ${picked.size} DOC tracks."
            }
            _busy.value = false
        }
    }

    companion object {
        /** How far around the phone a "Near me" search looks, until the operator says otherwise. */
        private const val DEFAULT_RADIUS_KM = 25.0

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
