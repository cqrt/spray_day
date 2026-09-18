package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow
import nz.mckenzie.sprayday.domain.handover.HandoverRow

/**
 * Rolled-up spray history per asset, used by the due-status engine to colour
 * the map without loading every event.
 */
data class AssetSpraySummary(
    val assetId: Long,
    val lastSprayedAtEpochMs: Long?,
    val sprayCount: Int
)

/** One product line of a spray, with the product's display name. */
data class ProductQuantityLine(
    val name: String,
    val quantityMl: Double
)

/** An asset's saved pre-fill for one product. */
data class AssetDefaultLine(
    val productId: Long,
    val name: String,
    val defaultQuantityMl: Double?
)

@Dao
abstract class SprayEventDao {

    @Insert
    abstract suspend fun insertEvent(event: SprayEventEntity): Long

    @Insert
    abstract suspend fun insertEventProducts(items: List<SprayEventProductEntity>)

    @Query("SELECT * FROM spray_events WHERE id = :id")
    abstract suspend fun getEvent(id: Long): SprayEventEntity?

    @Query("SELECT * FROM spray_events WHERE assetId = :assetId ORDER BY sprayedAtEpochMs DESC")
    abstract fun observeEventsForTrack(assetId: Long): Flow<List<SprayEventEntity>>

    /**
     * An asset's sprays from [sinceEpochMs] onwards, oldest first.
     *
     * For the map, which asks which parts of a line a spray could account for: a spray
     * older than the asset's own interval cannot change what any part of it looks like.
     */
    @Query(
        """
        SELECT * FROM spray_events
        WHERE assetId = :assetId AND sprayedAtEpochMs >= :sinceEpochMs
        ORDER BY sprayedAtEpochMs
        """
    )
    abstract suspend fun eventsSince(assetId: Long, sinceEpochMs: Long): List<SprayEventEntity>

    @Query("SELECT * FROM spray_event_products WHERE sprayEventId = :sprayEventId")
    abstract suspend fun getEventProducts(sprayEventId: Long): List<SprayEventProductEntity>

    @Query("SELECT * FROM spray_event_products WHERE sprayEventId = :sprayEventId")
    abstract fun observeEventProducts(sprayEventId: Long): Flow<List<SprayEventProductEntity>>

    /** Spray lines with product names, for showing a history entry. */
    @Query(
        """
        SELECT p.name AS name, ep.quantityMl AS quantityMl
        FROM spray_event_products ep
        JOIN products p ON p.id = ep.productId
        WHERE ep.sprayEventId = :sprayEventId
        ORDER BY p.name COLLATE NOCASE
        """
    )
    abstract suspend fun productQuantityLines(sprayEventId: Long): List<ProductQuantityLine>

    /**
     * What an asset was last given, as its pre-fill for the next spray. This is
     * the mechanism behind the three-times-a-year workflow: the amounts are
     * suggested rather than retyped.
     */
    @Query(
        """
        SELECT p.id AS productId, p.name AS name, d.defaultQuantityMl AS defaultQuantityMl
        FROM asset_product_defaults d
        JOIN products p ON p.id = d.productId
        WHERE d.assetId = :assetId
        ORDER BY p.name COLLATE NOCASE
        """
    )
    abstract suspend fun assetDefaultLines(assetId: Long): List<AssetDefaultLine>

    @Query(
        """
        SELECT assetId,
               MAX(sprayedAtEpochMs) AS lastSprayedAtEpochMs,
               COUNT(*) AS sprayCount
        FROM spray_events
        GROUP BY assetId
        """
    )
    abstract fun observeSpraySummaries(): Flow<List<AssetSpraySummary>>

    @Query("SELECT MAX(sprayedAtEpochMs) FROM spray_events WHERE assetId = :assetId")
    abstract suspend fun lastSprayedAt(assetId: Long): Long?

    @Query("DELETE FROM spray_events WHERE id = :id")
    abstract suspend fun deleteEvent(id: Long)

    /**
     * Forgets the link to a recording that has been deleted. The spray record
     * itself stays: it is the history, and the recording was only the evidence.
     */
    @Query("UPDATE spray_events SET recordedSessionId = NULL WHERE recordedSessionId = :sessionId")
    abstract suspend fun clearRecordedSession(sessionId: Long)

    // --- Per-asset product defaults --------------------------------------------------

    @Insert(onConflict = androidx.room.OnConflictStrategy.REPLACE)
    abstract suspend fun upsertDefault(item: AssetProductDefaultEntity)

    @Query("SELECT * FROM asset_product_defaults WHERE assetId = :assetId")
    abstract suspend fun getDefaults(assetId: Long): List<AssetProductDefaultEntity>

    @Query("DELETE FROM asset_product_defaults WHERE assetId = :assetId AND productId = :productId")
    abstract suspend fun deleteDefault(assetId: Long, productId: Long)

    /**
     * Every spray of every asset as one row per product, oldest first - the shape a
     * handover record is read in. Joined rather than assembled in Kotlin so the record
     * cannot disagree with the database it describes.
     */
    @Query(
        """
        SELECT e.sprayedAtEpochMs AS sprayedAtEpochMs,
               a.name AS assetName,
               g.name AS groupName,
               a.method AS method,
               p.name AS productName,
               ep.quantityMl AS amount,
               p.unit AS unit,
               e.waterLitres AS waterLitres,
               e.distanceM AS distanceM,
               e.areaSqm AS areaSqm,
               e.operatorName AS operatorName,
               e.notes AS notes,
               r.name AS recordingName
        FROM spray_event_products ep
        JOIN spray_events e ON e.id = ep.sprayEventId
        JOIN assets a ON a.id = e.assetId
        JOIN products p ON p.id = ep.productId
        LEFT JOIN groups g ON g.id = a.groupId
        LEFT JOIN recorded_sessions r ON r.id = e.recordedSessionId
        ORDER BY e.sprayedAtEpochMs, ep.id
        """
    )
    abstract suspend fun handoverRows(): List<HandoverRow>
}
