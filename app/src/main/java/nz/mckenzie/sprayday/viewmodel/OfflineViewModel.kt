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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.doc.ArcGisDocTracks
import nz.mckenzie.sprayday.doc.DocTrackDownloader
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.offline.OfflineAreaManager
import nz.mckenzie.sprayday.offline.OfflineDocTrackStore
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.offline.TileStoreSummary

/**
 * The offline screen: the two caches, and what can be done with what is in them.
 *
 * Choosing an area is **not** done here any more - it is the map picker, reached from either tab -
 * so this manages only what is already stored: the imagery areas (resume, delete, clear) and the DOC
 * tracks (count, clear). The two caches are independent, so they are managed independently, and the
 * screen shows one tab each.
 */
class OfflineViewModel(
    private val manager: OfflineAreaManager,
    private val settings: SettingsRepository,
    private val docStore: OfflineDocTrackStore
) : ViewModel() {

    val apiKey: StateFlow<String> = settings.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** The imagery areas on the device. Chosen elsewhere; managed here. */
    val stored: StateFlow<List<OfflineArea>> = manager.observeAreas()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _working = MutableStateFlow(false)
    val working: StateFlow<Boolean> = _working

    private val _summary = MutableStateFlow(TileStoreSummary(0, 0))
    val summary: StateFlow<TileStoreSummary> = _summary

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    /** How many DOC tracks are kept for offline use, live from the database. */
    val docTrackCount: StateFlow<Int> = docStore.observeCount()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), 0)

    init {
        refreshSummary()

        // Keep "what is on the device" honest when an area is downloaded from the picker: its
        // download left this figure stale. Refreshed when the set of areas changes or one finishes -
        // not on every progress write, which would walk the tile directory once a second.
        viewModelScope.launch {
            var previous = emptyList<OfflineArea>()
            manager.observeAreas().collect { areas ->
                val somethingFinished = areas.any { area ->
                    area.isComplete && previous.none { it.id == area.id && it.isComplete }
                }
                val setChanged = areas.size != previous.size
                previous = areas
                if (somethingFinished || setChanged) refreshSummary()
            }
        }
    }

    /** Continues an imagery area that stopped short; tiles already held are skipped. */
    fun resume(areaId: Long) {
        if (_working.value) return
        viewModelScope.launch {
            _working.value = true
            _error.value = null
            try {
                // Read the key now rather than from a cached flow: the button can be tapped before
                // the first read has landed.
                val key = settings.linzApiKey.first()
                if (key.isBlank()) {
                    _error.value = "Add a LINZ Basemaps key before downloading an area."
                    return@launch
                }
                manager.download(areaId, key)
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

    /** Forgets an imagery area. The tiles stay: they are shared, and this frees no space. */
    fun delete(areaId: Long) {
        viewModelScope.launch {
            runCatching { manager.deleteArea(areaId) }
            refreshSummary()
        }
    }

    /** Reclaims the disk space held by every downloaded tile. */
    fun clearTiles() {
        viewModelScope.launch {
            runCatching { manager.clearTiles() }
            refreshSummary()
        }
    }

    /** Forgets every downloaded DOC track. Costs nothing but the download. */
    fun clearDocTracks() {
        viewModelScope.launch {
            runCatching { docStore.clear() }
        }
    }

    private fun refreshSummary() {
        viewModelScope.launch {
            _summary.value = runCatching { manager.summary() }.getOrDefault(TileStoreSummary(0, 0))
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    OfflineViewModel(
                        manager = OfflineAreaManager(
                            // The same store the tile server serves from, so what
                            // is downloaded here is what the map reads offline.
                            store = TileServerHolder.imageryStore(appContext),
                            dao = database.offlineAreaDao()
                        ),
                        settings = SettingsRepository(appContext),
                        // DOC's tracks, downloaded by the picker into the same cache the browsers
                        // fall back to when the service cannot be reached.
                        docStore = OfflineDocTrackStore(
                            dao = database.offlineDocTrackDao(),
                            downloader = DocTrackDownloader(ArcGisDocTracks())
                        )
                    )
                }
            }
        }
    }
}
