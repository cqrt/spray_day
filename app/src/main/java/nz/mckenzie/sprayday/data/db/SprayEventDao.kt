package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/**
 * Rolled-up spray history per track, used by the due-status engine to colour
 * the map without loading every event.
 */
data class TrackSpraySummary(
    val trackId: Long,
    val lastSprayedAtEpochMs: Long?,
    val sprayCount: Int
)

@Dao
abstract class SprayEventDao {

    @Insert
    abstract suspend fun insertEvent(event: SprayEventEntity): Long

    @Insert
    abstract suspend fun insertEventProducts(items: List<SprayEventProductEntity>)

    @Query("SELECT * FROM spray_events WHERE id = :id")
    abstract suspend fun getEvent(id: Long): SprayEventEntity?

    @Query("SELECT * FROM spray_events WHERE trackId = :trackId ORDER BY sprayedAtEpochMs DESC")
    abstract fun observeEventsForTrack(trackId: Long): Flow<List<SprayEventEntity>>

    @Query("SELECT * FROM spray_event_products WHERE sprayEventId = :sprayEventId")
    abstract suspend fun getEventProducts(sprayEventId: Long): List<SprayEventProductEntity>

    @Query("SELECT * FROM spray_event_products WHERE sprayEventId = :sprayEventId")
    abstract fun observeEventProducts(sprayEventId: Long): Flow<List<SprayEventProductEntity>>

    @Query(
        """
        SELECT trackId,
               MAX(sprayedAtEpochMs) AS lastSprayedAtEpochMs,
               COUNT(*) AS sprayCount
        FROM spray_events
        GROUP BY trackId
        """
    )
    abstract fun observeSpraySummaries(): Flow<List<TrackSpraySummary>>

    @Query("SELECT MAX(sprayedAtEpochMs) FROM spray_events WHERE trackId = :trackId")
    abstract suspend fun lastSprayedAt(trackId: Long): Long?

    @Query("DELETE FROM spray_events WHERE id = :id")
    abstract suspend fun deleteEvent(id: Long)

    // --- Per-track product defaults -------------------------------------------------

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDefault(item: TrackProductDefaultEntity)

    @Query("SELECT * FROM track_product_defaults WHERE trackId = :trackId")
    abstract suspend fun getDefaults(trackId: Long): List<TrackProductDefaultEntity>

    @Query("DELETE FROM track_product_defaults WHERE trackId = :trackId AND productId = :productId")
    abstract suspend fun deleteDefault(trackId: Long, productId: Long)
}
