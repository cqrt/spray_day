package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query

/**
 * Reading and writing the whole database, for backup and restore.
 *
 * Kept apart from the screen-facing DAOs because its queries are of a different kind:
 * "everything, in a stable order" rather than "what this screen is showing". Restore
 * deletes and re-inserts rather than updating in place, so the app can never end up
 * half of one season and half of another.
 */
@Dao
interface BackupDao {

    // --- Reading it all -----------------------------------------------------------

    @Query("SELECT * FROM groups ORDER BY id")
    suspend fun allGroups(): List<GroupEntity>

    @Query("SELECT * FROM assets ORDER BY id")
    suspend fun allAssets(): List<AssetEntity>

    @Query("SELECT * FROM asset_points ORDER BY assetId, sequence")
    suspend fun allAssetPoints(): List<AssetPointEntity>

    @Query("SELECT * FROM products ORDER BY id")
    suspend fun allProducts(): List<ProductEntity>

    @Query("SELECT * FROM spray_events ORDER BY id")
    suspend fun allSprayEvents(): List<SprayEventEntity>

    @Query("SELECT * FROM spray_event_products ORDER BY id")
    suspend fun allSprayEventProducts(): List<SprayEventProductEntity>

    @Query("SELECT * FROM asset_product_defaults ORDER BY assetId, productId")
    suspend fun allAssetDefaults(): List<AssetProductDefaultEntity>

    @Query("SELECT * FROM recorded_sessions ORDER BY id")
    suspend fun allRecordedSessions(): List<RecordedSessionEntity>

    @Query("SELECT * FROM recorded_points ORDER BY sessionId, sequence")
    suspend fun allRecordedPoints(): List<RecordedPointEntity>

    // --- Counting, for "what is in the app right now" ------------------------------

    @Query("SELECT COUNT(*) FROM assets")
    suspend fun assetCount(): Int

    @Query("SELECT COUNT(*) FROM groups")
    suspend fun groupCount(): Int

    @Query("SELECT COUNT(*) FROM spray_events")
    suspend fun sprayCount(): Int

    @Query("SELECT COUNT(*) FROM recorded_sessions")
    suspend fun recordingCount(): Int

    @Query("SELECT COUNT(*) FROM products")
    suspend fun productCount(): Int

    @Query("SELECT (SELECT COUNT(*) FROM asset_points) + (SELECT COUNT(*) FROM recorded_points)")
    suspend fun pointCount(): Int

    // --- Writing it back ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertGroups(rows: List<GroupEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(rows: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssets(rows: List<AssetEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssetPoints(rows: List<AssetPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSprayEvents(rows: List<SprayEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSprayEventProducts(rows: List<SprayEventProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertAssetDefaults(rows: List<AssetProductDefaultEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordedSessions(rows: List<RecordedSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordedPoints(rows: List<RecordedPointEntity>)

    // --- Clearing, children before parents so foreign keys are never left dangling --

    @Query("DELETE FROM asset_product_defaults")
    suspend fun clearAssetDefaults()

    @Query("DELETE FROM spray_event_products")
    suspend fun clearSprayEventProducts()

    @Query("DELETE FROM spray_events")
    suspend fun clearSprayEvents()

    @Query("DELETE FROM asset_points")
    suspend fun clearAssetPoints()

    @Query("DELETE FROM assets")
    suspend fun clearAssets()

    @Query("DELETE FROM groups")
    suspend fun clearGroups()

    @Query("DELETE FROM products")
    suspend fun clearProducts()

    @Query("DELETE FROM recorded_points")
    suspend fun clearRecordedPoints()

    @Query("DELETE FROM recorded_sessions")
    suspend fun clearRecordedSessions()
}
