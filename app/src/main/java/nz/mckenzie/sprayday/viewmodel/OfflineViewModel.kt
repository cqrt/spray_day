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
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.offline.OfflineArea
import nz.mckenzie.sprayday.offline.OfflineAreaManager
import nz.mckenzie.sprayday.offline.OfflineAreaPlan

/**
 * Drives the offline-area screen: shows what an area will cost before you
 * commit, then reports live download progress and manages stored areas.
 */
class OfflineViewModel(
    private val manager: OfflineAreaManager,
    settingsRepository: SettingsRepository
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /** Default area until track bounds are wired up: a few km around a land start. */
    val plan: OfflineAreaPlan = OfflineAreaPlan.aroundCentre(
        name = "Spray area",
        centre = GeoPoint(-41.51, 173.96),
        radiusKm = 3.0
    )

    private val _active = MutableStateFlow<OfflineArea?>(null)
    val active: StateFlow<OfflineArea?> = _active

    private val _stored = MutableStateFlow<List<OfflineArea>>(emptyList())
    val stored: StateFlow<List<OfflineArea>> = _stored

    private val _error = MutableStateFlow<String?>(null)
    val error: StateFlow<String?> = _error

    private val _warning = MutableStateFlow<String?>(null)
    val warning: StateFlow<String?> = _warning

    init {
        refresh()
    }

    fun refresh() {
        viewModelScope.launch {
            _stored.value = runCatching { manager.listAreas() }
                .getOrElse { emptyList() }
        }
    }

    fun download() {
        val key = apiKey.value
        if (key.isBlank()) {
            _error.value = "Add a LINZ Basemaps key before downloading an area."
            return
        }
        if (_active.value?.isComplete == false) return

        viewModelScope.launch {
            _error.value = null
            _warning.value = null
            try {
                val started = manager.startArea(plan, key)
                _active.value = started

                // Live progress, plus a bounded wait so a stalled download fails
                // loudly instead of spinning forever.
                val progress = viewModelScope.launch {
                    manager.progress(started.id)?.collect { _active.value = it }
                }
                try {
                    val finished = manager.awaitComplete(started.id, DOWNLOAD_TIMEOUT_MS)
                    if (finished.stalled) {
                        // Stop MapLibre retrying a resource that will never arrive.
                        manager.pauseArea(started.id)
                        _active.value = finished
                        _warning.value = "Downloaded ${finished.completedResources} of " +
                            "${finished.requiredResources} files; " +
                            "${finished.missingResources} were not available from LINZ. " +
                            "The area is usable offline."
                    }
                } finally {
                    progress.cancel()
                }
                refresh()
            } catch (failure: Throwable) {
                _error.value = failure.message ?: "Download failed"
            }
        }
    }

    fun delete(areaId: Long) {
        viewModelScope.launch {
            runCatching { manager.deleteArea(areaId) }
            if (_active.value?.id == areaId) _active.value = null
            refresh()
        }
    }

    companion object {
        private const val DOWNLOAD_TIMEOUT_MS = 15 * 60 * 1000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    OfflineViewModel(
                        manager = OfflineAreaManager(appContext),
                        settingsRepository = SettingsRepository(appContext)
                    )
                }
            }
        }
    }
}
