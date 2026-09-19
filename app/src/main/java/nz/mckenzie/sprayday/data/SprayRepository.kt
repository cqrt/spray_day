package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.ProductQuantityLine
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.data.db.AssetDefaultLine
import nz.mckenzie.sprayday.data.db.AssetProductDefaultEntity

/**
 * The product catalogue and the spray records themselves - "what went out, and
 * how much of it".
 */
class SprayRepository(private val db: SprayDayDatabase) {

    private val assetDao = db.assetDao()
    private val sprayEventDao = db.sprayEventDao()
    private val productDao = db.productDao()

    // --- Products -------------------------------------------------------------------

    fun observeProducts(): Flow<List<ProductEntity>> = productDao.observeActive()

    /** Every product, archived ones included, for managing the catalogue. */
    fun observeAllProducts(): Flow<List<ProductEntity>> = productDao.observeAll()

    suspend fun getProduct(productId: Long): ProductEntity? = productDao.getById(productId)

    suspend fun addProduct(
        name: String,
        unit: String = ProductEntity.UNIT_ML,
        rateText: String? = null,
        notes: String? = null
    ): Long = productDao.insert(
        ProductEntity(name = name, unit = unit, rateText = rateText, notes = notes)
    )

    suspend fun updateProduct(product: ProductEntity) = productDao.update(product)

    /**
     * Renames a product. Throws if the name is already taken (the column is
     * unique), which is the behaviour wanted: two products must not become one
     * chemical by accident.
     */
    suspend fun renameProduct(productId: Long, name: String) = productDao.rename(productId, name)

    suspend fun setProductArchived(productId: Long, archived: Boolean) =
        productDao.setArchived(productId, archived)

    // --- Per-track product defaults -------------------------------------------------

    /** Pre-fills the spray form: "this track always gets 400 mL of Product X". */
    suspend fun setTrackProductDefault(assetId: Long, productId: Long, defaultQuantityMl: Double?) =
        sprayEventDao.upsertDefault(
            AssetProductDefaultEntity(
                assetId = assetId,
                productId = productId,
                defaultQuantityMl = defaultQuantityMl
            )
        )

    suspend fun getAssetProductDefaults(assetId: Long): List<AssetProductDefaultEntity> =
        sprayEventDao.getDefaults(assetId)

    suspend fun deleteAssetProductDefault(assetId: Long, productId: Long) =
        sprayEventDao.deleteDefault(assetId, productId)

    // --- Spray events ---------------------------------------------------------------

    fun observeSprayEvents(assetId: Long): Flow<List<SprayEventEntity>> =
        sprayEventDao.observeEventsForTrack(assetId)

    suspend fun getSprayEvent(eventId: Long): SprayEventEntity? = sprayEventDao.getEvent(eventId)

    suspend fun getSprayEventProducts(sprayEventId: Long): List<SprayEventProductEntity> =
        sprayEventDao.getEventProducts(sprayEventId)

    /** Spray lines with product names, for the history list. */
    suspend fun getSprayEventProductLines(sprayEventId: Long): List<ProductQuantityLine> =
        sprayEventDao.productQuantityLines(sprayEventId)

    /** The amounts a track was last given, used to pre-fill the spray form. */
    suspend fun getAssetDefaultLines(assetId: Long): List<AssetDefaultLine> =
        sprayEventDao.assetDefaultLines(assetId)

    /**
     * Remembers what a track was given, so the next spray only needs confirming.
     * Called from the spray form when "remember for this track" is ticked.
     */
    suspend fun rememberDefaultsForTrack(
        assetId: Long,
        products: List<SprayProductQuantity>
    ) = db.withTransaction {
        products.forEach { line ->
            sprayEventDao.upsertDefault(
                AssetProductDefaultEntity(
                    assetId = assetId,
                    productId = line.productId,
                    defaultQuantityMl = line.quantityMl
                )
            )
        }
    }

    /**
     * Records a spray - the event, the mL of each product, and the track's new
     * last-sprayed timestamp - in one transaction, so the map colour and the
     * spray history can never disagree.
     */
    suspend fun recordSpray(
        assetId: Long,
        sprayedAtEpochMs: Long = System.currentTimeMillis(),
        products: List<SprayProductQuantity> = emptyList(),
        waterLitres: Double? = null,
        operatorName: String? = null,
        notes: String? = null,
        distanceM: Double? = null,
        areaSqm: Double? = null,
        recordedSessionId: Long? = null
    ): Long = db.withTransaction {
        val eventId = sprayEventDao.insertEvent(
            SprayEventEntity(
                assetId = assetId,
                sprayedAtEpochMs = sprayedAtEpochMs,
                waterLitres = waterLitres,
                operatorName = operatorName,
                notes = notes,
                distanceM = distanceM,
                areaSqm = areaSqm,
                recordedSessionId = recordedSessionId
            )
        )
        if (products.isNotEmpty()) {
            sprayEventDao.insertEventProducts(
                products.map { item ->
                    SprayEventProductEntity(
                        sprayEventId = eventId,
                        productId = item.productId,
                        quantityMl = item.quantityMl
                    )
                }
            )
        }
        assetDao.setLastSprayedAt(assetId, sprayedAtEpochMs)
        eventId
    }

    /**
     * Takes one spray off an asset's history.
     *
     * The asset's stored last-sprayed date is put back to the newest spray left, which is the
     * point of doing this in one transaction: that date decides the colour of the line and when
     * the asset is next due, and a spray that is no longer on the device must not go on
     * colouring anything. With none left the date goes back to null, so the asset reads as never
     * sprayed.
     *
     * The GPS recording behind the spray is not touched. It is the evidence of a pass that
     * happened, and the two are deleted from opposite ends on purpose - a recording can go
     * without taking the spray with it, and this takes the spray without the recording.
     *
     * @return false when there was no such spray, which is not a failure.
     */
    suspend fun deleteSprayEvent(eventId: Long): Boolean = db.withTransaction {
        val event = sprayEventDao.getEvent(eventId) ?: return@withTransaction false
        sprayEventDao.deleteEvent(eventId)
        assetDao.setLastSprayedAtExactly(event.assetId, sprayEventDao.lastSprayedAt(event.assetId))
        true
    }

    /**
     * Clears an asset's whole spray history: the start-again button for a record that has gone
     * wrong.
     *
     * What is left afterwards is the asset, its line, its settings and its recordings, and no
     * sprays at all - so the traffic light reads red until it is sprayed again, which is also
     * the only way back to that state once an asset has been sprayed.
     *
     * @return how many sprays were removed.
     */
    suspend fun deleteSprayHistory(assetId: Long): Int = db.withTransaction {
        val removed = sprayEventDao.deleteEventsForAsset(assetId)
        assetDao.setLastSprayedAtExactly(assetId, null)
        removed
    }
}
