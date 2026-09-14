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

    @Query("SELECT * FROM tracks ORDER BY id")
    suspend fun allTracks(): List<TrackEntity>

    @Query("SELECT * FROM track_points ORDER BY trackId, sequence")
    suspend fun allTrackPoints(): List<TrackPointEntity>

    @Query("SELECT * FROM products ORDER BY id")
    suspend fun allProducts(): List<ProductEntity>

    @Query("SELECT * FROM spray_events ORDER BY id")
    suspend fun allSprayEvents(): List<SprayEventEntity>

    @Query("SELECT * FROM spray_event_products ORDER BY id")
    suspend fun allSprayEventProducts(): List<SprayEventProductEntity>

    @Query("SELECT * FROM track_product_defaults ORDER BY trackId, productId")
    suspend fun allTrackDefaults(): List<TrackProductDefaultEntity>

    @Query("SELECT * FROM recorded_sessions ORDER BY id")
    suspend fun allRecordedSessions(): List<RecordedSessionEntity>

    @Query("SELECT * FROM recorded_points ORDER BY sessionId, sequence")
    suspend fun allRecordedPoints(): List<RecordedPointEntity>

    // --- Counting, for "what is in the app right now" ------------------------------

    @Query("SELECT COUNT(*) FROM tracks")
    suspend fun trackCount(): Int

    @Query("SELECT COUNT(*) FROM spray_events")
    suspend fun sprayCount(): Int

    @Query("SELECT COUNT(*) FROM recorded_sessions")
    suspend fun recordingCount(): Int

    @Query("SELECT COUNT(*) FROM products")
    suspend fun productCount(): Int

    @Query("SELECT (SELECT COUNT(*) FROM track_points) + (SELECT COUNT(*) FROM recorded_points)")
    suspend fun pointCount(): Int

    // --- Writing it back ----------------------------------------------------------

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertProducts(rows: List<ProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTracks(rows: List<TrackEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackPoints(rows: List<TrackPointEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSprayEvents(rows: List<SprayEventEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertSprayEventProducts(rows: List<SprayEventProductEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertTrackDefaults(rows: List<TrackProductDefaultEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordedSessions(rows: List<RecordedSessionEntity>)

    @Insert(onConflict = OnConflictStrategy.REPLACE)
    suspend fun insertRecordedPoints(rows: List<RecordedPointEntity>)

    // --- Clearing, children before parents so foreign keys are never left dangling --

    @Query("DELETE FROM track_product_defaults")
    suspend fun clearTrackDefaults()

    @Query("DELETE FROM spray_event_products")
    suspend fun clearSprayEventProducts()

    @Query("DELETE FROM spray_events")
    suspend fun clearSprayEvents()

    @Query("DELETE FROM track_points")
    suspend fun clearTrackPoints()

    @Query("DELETE FROM tracks")
    suspend fun clearTracks()

    @Query("DELETE FROM products")
    suspend fun clearProducts()

    @Query("DELETE FROM recorded_points")
    suspend fun clearRecordedPoints()

    @Query("DELETE FROM recorded_sessions")
    suspend fun clearRecordedSessions()
}
