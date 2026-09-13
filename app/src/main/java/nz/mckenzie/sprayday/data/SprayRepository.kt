package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.ProductQuantityLine
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.data.db.TrackDefaultLine
import nz.mckenzie.sprayday.data.db.TrackProductDefaultEntity

/**
 * The product catalogue and the spray records themselves - "what went out, and
 * how much of it".
 */
class SprayRepository(private val db: SprayDayDatabase) {

    private val trackDao = db.trackDao()
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
    suspend fun setTrackProductDefault(trackId: Long, productId: Long, defaultQuantityMl: Double?) =
        sprayEventDao.upsertDefault(
            TrackProductDefaultEntity(
                trackId = trackId,
                productId = productId,
                defaultQuantityMl = defaultQuantityMl
            )
        )

    suspend fun getTrackProductDefaults(trackId: Long): List<TrackProductDefaultEntity> =
        sprayEventDao.getDefaults(trackId)

    suspend fun deleteTrackProductDefault(trackId: Long, productId: Long) =
        sprayEventDao.deleteDefault(trackId, productId)

    // --- Spray events ---------------------------------------------------------------

    fun observeSprayEvents(trackId: Long): Flow<List<SprayEventEntity>> =
        sprayEventDao.observeEventsForTrack(trackId)

    suspend fun getSprayEvent(eventId: Long): SprayEventEntity? = sprayEventDao.getEvent(eventId)

    suspend fun getSprayEventProducts(sprayEventId: Long): List<SprayEventProductEntity> =
        sprayEventDao.getEventProducts(sprayEventId)

    /** Spray lines with product names, for the history list. */
    suspend fun getSprayEventProductLines(sprayEventId: Long): List<ProductQuantityLine> =
        sprayEventDao.productQuantityLines(sprayEventId)

    /** The amounts a track was last given, used to pre-fill the spray form. */
    suspend fun getTrackDefaultLines(trackId: Long): List<TrackDefaultLine> =
        sprayEventDao.trackDefaultLines(trackId)

    /**
     * Remembers what a track was given, so the next spray only needs confirming.
     * Called from the spray form when "remember for this track" is ticked.
     */
    suspend fun rememberDefaultsForTrack(
        trackId: Long,
        products: List<SprayProductQuantity>
    ) = db.withTransaction {
        products.forEach { line ->
            sprayEventDao.upsertDefault(
                TrackProductDefaultEntity(
                    trackId = trackId,
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
        trackId: Long,
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
                trackId = trackId,
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
        trackDao.setLastSprayedAt(trackId, sprayedAtEpochMs)
        eventId
    }

    suspend fun deleteSprayEvent(eventId: Long) = sprayEventDao.deleteEvent(eventId)
}
