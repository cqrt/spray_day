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
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.AssetEntity
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
    private val assetId: Long,
    private val assetRepository: AssetRepository,
    private val sprays: SprayRepository,
    /**
     * The recording this spray is being logged from, when the operator came here from
     * one. The saved spray then points at it, which is what makes the spray checkable
     * against the line that was actually driven.
     */
    val linkedSessionId: Long? = null
) : ViewModel() {

    data class ProductRow(
        val productId: Long,
        val name: String,
        val rateText: String?,
        val quantityText: String
    )

    val track: StateFlow<AssetEntity?> = assetRepository.observeAsset(assetId)
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), null)

    private val _rows = MutableStateFlow<List<ProductRow>>(emptyList())
    val rows: StateFlow<List<ProductRow>> = _rows

    /** Every product including archived ones, for the manage-products dialog. */
    val allProducts: StateFlow<List<ProductEntity>> = sprays.observeAllProducts()
        .stateIn(viewModelScope, SharingStarted.WhileSubscribed(STOP_TIMEOUT_MS), emptyList())

    private val _waterLitres = MutableStateFlow("")
    val waterLitres: StateFlow<String> = _waterLitres

    private val _notes = MutableStateFlow("")
    val notes: StateFlow<String> = _notes

    private val _rememberDefaults = MutableStateFlow(true)
    val rememberDefaults: StateFlow<Boolean> = _rememberDefaults

    private val _message = MutableStateFlow<String?>(null)
    val message: StateFlow<String?> = _message

    private val _saved = MutableStateFlow(false)

    /**
     * True once the spray has been recorded. A one-shot signal: the screen calls
     * [consumeSaveResult] as it navigates away, otherwise a reused view model
     * (see the per-track view model key) would bounce the operator straight back
     * out of the form on their next visit.
     */
    val saved: StateFlow<Boolean> = _saved

    fun consumeSaveResult() {
        _saved.value = false
    }

    init {
        viewModelScope.launch {
            val defaults = sprays.getAssetDefaultLines(assetId)
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

    /** Renames a product, keeping its history: sprays keep pointing at the same id. */
    fun renameProduct(productId: Long, name: String) {
        val trimmed = name.trim()
        if (trimmed.isEmpty()) {
            _message.value = "Give the product a name"
            return
        }
        viewModelScope.launch {
            _message.value = null
            runCatching { sprays.renameProduct(productId, trimmed) }
                .onFailure {
                    _message.value = if (it is android.database.sqlite.SQLiteConstraintException) {
                        "A product called \"$trimmed\" already exists"
                    } else {
                        it.message ?: "Could not rename the product"
                    }
                }
        }
    }

    /**
     * Hides a product from the entry form without touching the sprays that used it,
     * so an out-of-favour chemical stops being offered but stays in the history.
     */
    fun setProductArchived(productId: Long, archived: Boolean) {
        viewModelScope.launch {
            runCatching { sprays.setProductArchived(productId, archived) }
                .onFailure { _message.value = it.message ?: "Could not update the product" }
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
                    assetId = assetId,
                    products = lines,
                    waterLitres = parsePositiveAmount(_waterLitres.value),
                    notes = _notes.value.trim().ifBlank { null },
                    recordedSessionId = linkedSessionId
                )
                if (_rememberDefaults.value) {
                    sprays.rememberDefaultsForTrack(assetId, lines)
                }
                _saved.value = true
            } catch (failure: Throwable) {
                _message.value = failure.message ?: "Could not save the spray"
            }
        }
    }

    companion object {
        private const val STOP_TIMEOUT_MS = 5_000L

        fun factory(context: Context, assetId: Long, linkedSessionId: Long? = null): ViewModelProvider.Factory {
            val appContext = context.applicationContext
            return viewModelFactory {
                initializer {
                    val database = SprayDayDatabase.get(appContext)
                    SprayEntryViewModel(
                        assetId = assetId,
                        assetRepository = AssetRepository(database),
                        sprays = SprayRepository(database),
                        linkedSessionId = linkedSessionId
                    )
                }
            }
        }
    }
}
