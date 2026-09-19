package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import android.net.Uri
import androidx.lifecycle.ViewModel
import androidx.lifecycle.ViewModelProvider
import androidx.lifecycle.viewModelScope
import androidx.lifecycle.viewmodel.initializer
import androidx.lifecycle.viewmodel.viewModelFactory
import kotlinx.coroutines.ExperimentalCoroutinesApi
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.combine
import kotlinx.coroutines.flow.map
import kotlinx.coroutines.flow.mapLatest
import kotlinx.coroutines.flow.stateIn
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.ProductQuantityLine
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.estimatedAreaSqm
import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/** One spray in an asset's history, with the amounts that went out. */
data class SprayHistoryEntry(
    val event: SprayEventEntity,
    val lines: List<ProductQuantityLine>
)

/**
 * What the edit form starts from: the asset, and the group it is in.
 *
 * The two arrive together rather than as separate states on purpose. A form that
 * opened with the group still loading would save a blank name over a real one, and
 * silently take the asset out of its block.
 */
data class AssetEditDraft(
    val asset: AssetEntity,
    val groupName: String
)

/**
 * Everything about one asset: its line on the map, when it is next due, what it
 * has been given, and GPX export.
 *
 * It is also where the spray history can be corrected - one entry at a time, or the
 * whole record cleared - which is the same act as setting the asset's colour, since
 * the colour is worked out from that history.
 */
class AssetDetailViewModel(
    private val assetId: Long,
    private val assetRepository: AssetRepository,
    private val sprays: SprayRepository,
    private val recordingsRepository: RecordingRepository,
    settingsRepository: SettingsRepository,
    private val context: Context
) : ViewModel() {

    val apiKey: StateFlow<String> = settingsRepository.linzApiKey
        .stateIn(viewModelScope, SharingStarted.Eagerly, "")

    /**
     * Which map to draw under the work: the operator's choice in Settings.
     *
     * Read here rather than handed in per screen, so a map added later cannot quietly ignore it.
     */
    val basemap: StateFlow<Basemap> = settingsRepository.basemap
        .stateIn(viewModelScope, SharingStarted.Eagerly, Basemap.DEFAULT)

    val track: StateFlow<AssetEntity?> = assetRepository.observeAsset(assetId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /** The asset and its group, ready for the edit form. Null until both are known. */
    val editDraft: StateFlow<AssetEditDraft?> = combine(
        assetRepository.observeAsset(assetId),
        assetRepository.observeGroupName(assetId)
    ) { asset, groupName ->
        asset?.let { AssetEditDraft(asset = it, groupName = groupName.orEmpty()) }
    }.stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The block this asset is in, for the line on the detail screen.
     *
     * Its own flow rather than a read of [editDraft], which is the edit form's state: the
     * screen showing it should not depend on a form having been prepared, and a field that
     * exists to be typed into is a poor place to keep something that is only being read.
     */
    val groupName: StateFlow<String?> = assetRepository.observeGroupName(assetId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    /**
     * The blocks that already exist, for the field on the edit form to offer.
     *
     * Held here rather than looked up when the form opens so the list is live: a block started
     * on another asset while this form is open is offered here too.
     */
    val existingBlocks: StateFlow<List<String>> = assetRepository.observeBlockNames()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val geometry: StateFlow<List<GeoPoint>> = assetRepository.observeAssetGeometry(assetId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    val bounds: StateFlow<LatLngBounds?> = geometry
        .map { points ->
            if (points.isEmpty()) {
                null
            } else {
                LatLngBounds(
                    minLat = points.minOf { it.lat },
                    minLng = points.minOf { it.lng },
                    maxLat = points.maxOf { it.lat },
                    maxLng = points.maxOf { it.lng }
                )
            }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    val due: StateFlow<DueInfo?> = assetRepository.observeAssetsWithDue()
        .map { list -> list.firstOrNull { it.asset.id == assetId }?.due }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    @OptIn(ExperimentalCoroutinesApi::class)
    val history: StateFlow<List<SprayHistoryEntry>> = sprays.observeSprayEvents(assetId)
        .mapLatest { events ->
            events.map { event -> SprayHistoryEntry(event, sprays.getSprayEventProductLines(event.id)) }
        }
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    /**
     * Every GPS recording made for this asset. The evidence behind the sprays, and the
     * way to check a coverage figure after the fact.
     */
    val recordings: StateFlow<List<RecordedSessionEntity>> =
        recordingsRepository.observeSessionsForTrack(assetId)
            .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    /** Estimated treated area, if the asset records a swath width. */
    val areaSqm: Double?
        get() = track.value?.let { entity ->
            entity.swathWidthM?.let { width ->
                // Both passes of a line sprayed twice go on the ground, so the area counts them:
                // see estimatedAreaSqm.
                estimatedAreaSqm(entity.lengthM, width, entity.passesRequired)
            }
        }

    fun exportGpx(uri: Uri) {
        viewModelScope.launch {
            try {
                val gpx = assetRepository.exportAssetGpx(assetId)
                    ?: error("That track no longer exists")
                context.contentResolver.openOutputStream(uri, "wt")?.use { output ->
                    output.write(gpx.toByteArray(Charsets.UTF_8))
                } ?: error("Could not open the destination file")
                _message.value = "Exported ${track.value?.name ?: "track"} as GPX"
            } catch (failure: Throwable) {
                _message.value = failure.message ?: "Export failed"
            }
        }
    }

    /**
     * Takes one spray off the asset's history.
     *
     * The repository puts the asset's last-sprayed date back to whatever is left, so this is
     * also what re-colours the line: remove the only spray and the asset reads red again.
     */
    fun deleteSpray(eventId: Long) {
        viewModelScope.launch {
            runCatching { sprays.deleteSprayEvent(eventId) }
                .onSuccess { removed ->
                    _message.value = if (removed) "Spray removed" else "That spray was already gone"
                }
                .onFailure { _message.value = it.message ?: "Could not remove the spray" }
        }
    }

    /**
     * Clears the whole spray history, for starting an asset's record again.
     *
     * The asset, its line and its recordings are left alone: what goes is every spray recorded
     * against it, and with them the colour the line was earning from them.
     */
    fun clearSprayHistory() {
        viewModelScope.launch {
            runCatching { sprays.deleteSprayHistory(assetId) }
                .onSuccess { removed ->
                    _message.value = when (removed) {
                        0 -> "There was no spray history to clear"
                        1 -> "Spray history cleared \u00b7 1 spray removed"
                        else -> "Spray history cleared \u00b7 $removed sprays removed"
                    }
                }
                .onFailure { _message.value = it.message ?: "Could not clear the spray history" }
        }
    }

    fun delete() {
        viewModelScope.launch {
            runCatching { assetRepository.deleteAsset(assetId) }
                .onFailure { _message.value = it.message ?: "Could not delete the track" }
        }
    }

    /**
     * Stores edits made on this screen.
     *
     * The fields were checked by [nz.mckenzie.sprayday.ui.AssetEdits] before this is
     * called, so anything arriving here is already fit to store - which also means the
     * interval set here is what the traffic light uses from now on. The group name
     * turns into a group row if it is new, and clears the group when it is blank.
     */
    fun save(asset: AssetEntity, groupName: String?) {
        viewModelScope.launch {
            runCatching { assetRepository.saveAssetEdits(asset, groupName) }
                .onSuccess { _message.value = "Saved" }
                .onFailure { _message.value = it.message ?: "Could not save the track" }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context, assetId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    AssetDetailViewModel(
                        assetId = assetId,
                        assetRepository = AssetRepository(database),
                        sprays = SprayRepository(database),
                        recordingsRepository = RecordingRepository(database),
                        settingsRepository = SettingsRepository(appContext),
                        context = appContext
                    )
                }
            }
        }
    }
}
