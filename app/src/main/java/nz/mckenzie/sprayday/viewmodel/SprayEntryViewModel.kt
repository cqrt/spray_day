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
import nz.mckenzie.sprayday.data.SprayProductQuantity
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.TrackEntity
import nz.mckenzie.sprayday.ui.formatQuantityMl
import nz.mckenzie.sprayday.ui.parsePositiveAmount
import nz.mckenzie.sprayday.ui.parseQuantityMl

/**
 * Captures what is about to be sprayed: the products and how much of each.
 *
 * Rows are pre-filled from what the track was last given, so the third spray of
 * the season is a confirmation rather than a retype - which is the whole point
 * of tracking set tracks three times a year.
 */
class SprayEntryViewModel(
    private val trackId: Long,
    private val tracks: TrackRepository,
    private val sprays: SprayRepository
) : ViewModel() {

    data class ProductRow(
        val productId: Long,
        val name: String,
        val rateText: String?,
        val quantityText: String
    )

    val track: StateFlow<TrackEntity?> = tracks.observeTrack(trackId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _rows = MutableStateFlow<List<ProductRow>>(emptyList())
    val rows: StateFlow<List<ProductRow>> = _rows

    private val _waterLitres = MutableStateFlow("")
    val waterLitres: StateFlow<String> = _waterLitres

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes

    private val _rememberDefaults = MutableStateFlow(true)
    val rememberDefaults: StateFlow<Boolean> = _rememberDefaults

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _saved = MutableStateFlow(false)
    val saved: StateFlow<Boolean> = _saved

    init {
        viewModelScope.launch {
            val defaults = sprays.getTrackDefaultLines(trackId)
                .associate { line -> line.productId to line.defaultQuantityMl }
            val typed = mutableMapOf<Long, String>()

            sprays.observeProducts().collect { products ->
                // Keep anything the operator has already typed across refreshes.
                _rows.value.forEach { row -> typed[row.productId] = row.quantityText }
                _rows.value = products.map { product ->
                    ProductRow(
                        productId = product.id,
                        name = product.name,
                        rateText = product.rateText,
                        quantityText = typed[product.id]
                            ?: defaults[product.id]?.let { formatQuantityMl(it) }
                            ?: ""
                    )
                }
            }
        }
    }

    fun updateQuantity(productId: Long, text: String) {
        _rows.value = _rows.value.map { row ->
            if (row.productId == productId) row.copy(quantityText = text) else row
        }
    }

    fun setWaterLitres(text: String) {
        _waterLitres.value = text
    }

    fun setNotes(text: String) {
        _notes.value = text
    }

    fun setRememberDefaults(value: Boolean) {
        _rememberDefaults.value = value
    }

    fun addProduct(name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "Give the product a name"
            return
        }
        viewModelScope.launch {
            runCatching { sprays.addProduct(name = trimmed) }
                .onFailure { _message.value = it.message ?: "Could not add the product" }
        }
    }

    fun save() {
        val lines = _rows.value.mapNotNull { row ->
            parseQuantityMl(row.quantityText)?.let { SprayProductQuantity(row.productId, it) }
        }
        if (lines.isEmpty()) {
            _message.value = "Enter an amount for at least one product"
            return
        }

        viewModelScope.launch {
            try {
                sprays.recordSpray(
                    trackId = trackId,
                    products = lines,
                    waterLitres = parsePositiveAmount(_waterLitres.value),
                    notes = _notes.value.trim().ifBlank { null }
                )
                if (_rememberDefaults.value) {
                    sprays.rememberDefaultsForTrack(trackId, lines)
                }
                _saved.value = true
            } catch (failure: Throwable) {
                _message.value = failure.message ?: "Could not save the spray"
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context, trackId: Long): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    SprayEntryViewModel(
                        trackId = trackId,
                        tracks = TrackRepository(database),
                        sprays = SprayRepository(database)
                    )
                }
            }
        }
    }
}
