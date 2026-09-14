package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Extremes of every planned track's geometry. Null columns when there is no geometry
 * at all, which is how "no tracks yet" is told apart from "tracks at 0,0".
 */
data class AssetPointBounds(
    val minLat: Double?,
    val minLng: Double?,
    val maxLat: Double?,
    val maxLng: Double?
)

/**
 * Declared as an abstract class rather than an interface so that
 * [replaceGeometry] can be a real transactional method - Kotlin interface
 * default methods need extra compiler flags to work reliably with Room.
 */
@Dao
abstract class AssetDao {
    @Query("SELECT * FROM tracks WHERE active = 1 ORDER BY name COLLATE NOCASE")
    abstract fun observeActiveAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM tracks ORDER BY name COLLATE NOCASE")
    abstract fun observeAllAssets(): Flow<List<AssetEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract fun observeAsset(id: Long): Flow<AssetEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract suspend fun getAsset(id: Long): AssetEntity?

    @Insert
    abstract suspend fun insert(track: AssetEntity): Long

    @Update
    abstract suspend fun update(track: AssetEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query(
        """
        UPDATE tracks
        SET lastSprayedAtEpochMs = MAX(COALESCE(lastSprayedAtEpochMs, 0), :sprayedAtEpochMs)
        WHERE id = :assetId
        """
    )
    abstract suspend fun setLastSprayedAt(assetId: Long, sprayedAtEpochMs: Long)

    @Query("UPDATE tracks SET lengthM = :lengthM WHERE id = :assetId")
    abstract suspend fun updateLength(assetId: Long, lengthM: Double)

    @Query("SELECT * FROM track_points WHERE trackId = :assetId ORDER BY sequence")
    abstract suspend fun getGeometry(assetId: Long): List<AssetPointEntity>

    /**
     * The box containing every planned track, for answering "where is the work?" with
     * one query rather than loading every point.
     */
    @Query(
        "SELECT MIN(lat) AS minLat, MIN(lng) AS minLng, " +
            "MAX(lat) AS maxLat, MAX(lng) AS maxLng FROM track_points"
    )
    abstract suspend fun pointBounds(): AssetPointBounds?

    @Query("SELECT * FROM track_points WHERE trackId = :assetId ORDER BY sequence")
    abstract fun observeGeometry(assetId: Long): Flow<List<AssetPointEntity>>

    @Insert
    abstract suspend fun insertGeometry(points: List<AssetPointEntity>)

    @Query("DELETE FROM track_points WHERE trackId = :assetId")
    abstract suspend fun deleteGeometry(assetId: Long)

    /** Swaps a track's geometry and refreshes its cached length atomically. */
    @androidx.room.Transaction
    open suspend fun replaceGeometry(assetId: Long, points: List<AssetPointEntity>, lengthM: Double) {
        deleteGeometry(assetId)
        if (points.isNotEmpty()) insertGeometry(points)
        updateLength(assetId, lengthM)
    }
}
