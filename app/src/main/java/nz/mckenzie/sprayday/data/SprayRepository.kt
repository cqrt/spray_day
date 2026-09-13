package nz.mckenzie.sprayday.data

import androidx.room.withTransaction
import kotlinx.coroutines.flow.Flow
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
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
