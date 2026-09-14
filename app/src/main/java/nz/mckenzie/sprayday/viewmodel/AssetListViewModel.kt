package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import android.net.Uri
import android.provider.OpenableColumns
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
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.SprayDayDatabase

/**
 * The track library: every planned track with its due status, plus GPX import.
 */
class AssetListViewModel(
    private val assetRepository: AssetRepository,
    private val context: Context
) : ViewModel() {

    val assetsWithDue: StateFlow<List<AssetWithDue>> = assetRepository.observeAssetsWithDue()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _busy = MutableStateFlow(false)
    val busy: StateFlow<Boolean> = _busy

    fun clearMessage() {
        _message.value = null
    }

    fun delete(assetId: Long) {
        viewModelScope.launch {
            runCatching { assetRepository.deleteAsset(assetId) }
                .onFailure { _message.value = it.message ?: "Could not delete the track" }
        }
    }

    /**
     * Imports a GPX file chosen through the system file picker.
     *
     * Failures are surfaced as messages rather than crashes: a malformed or
     * empty GPX is a normal thing for an operator to pick by mistake.
     */
    fun importGpx(uri: Uri) {
        viewModelScope.launch {
            _busy.value = true
            try {
                val text = context.contentResolver.openInputStream(uri)?.use { input ->
                    input.readBytes().toString(Charsets.UTF_8)
                } ?: throw IllegalStateException("Could not open the selected file")

                val name = displayName(uri)
                    ?.substringBeforeLast('.')
                    ?.takeIf { it.isNotBlank() }
                    ?: "Imported track"

                val assetId = assetRepository.importAssetGpx(name = name, gpx = text)
                val points = assetRepository.getAssetGeometry(assetId).size
                _message.value = "Imported \"$name\" with $points points"
            } catch (failure: Throwable) {
                _message.value = failure.message ?: "Import failed"
            } finally {
                _busy.value = false
            }
        }
    }

    private fun displayName(uri: Uri): String? = runCatching {
        context.contentResolver
            .query(uri, arrayOf(OpenableColumns.DISPLAY_NAME), null, null, null)
            ?.use { cursor -> if (cursor.moveToFirst()) cursor.getString(0) else null }
    }.getOrNull()

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    AssetListViewModel(
                        assetRepository = AssetRepository(SprayDayDatabase.get(appContext)),
                        context = appContext
                    )
                }
            }
        }
    }
}
