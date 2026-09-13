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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.offline.OfflineAreaManager
import nz.mckenzie.sprayday.offline.OfflineAreaPlan
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.offline.TileStoreSummary

/**
 * Drives the offline-area screen: shows what an area will cost before you
 * commit, then reports progress live and manages what is stored on the device.
 *
 * The list comes straight from the database, which is also where the download
 * writes its progress, so the screen updates without a polling loop here.
 */
class OfflineViewModel(
    private val manager: OfflineAreaManager,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Default area until track bounds decide it: a few km around a land start. */
    val plan: OfflineAreaPlan = OfflineAreaPlan.aroundCentre(
        name = "Spray area",
        centre = GeoPoint(-41.51, 173.96),
        radiusKm = 3.0
    )

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

        viewModelScope.launch {
            _working.value = true
            _error.value = null
            try {
                val id = existingId ?: manager.createArea(plan).id
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

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    OfflineViewModel(
                        manager = OfflineAreaManager(
                            // The same store the tile server serves from, so what
                            // is downloaded here is what the map reads offline.
                            store = TileServerHolder.store(appContext),
                            dao = SprayDayDatabase.get(appContext).offlineAreaDao()
                        ),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
