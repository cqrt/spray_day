package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import androidx.room.Update
import kotlinx.coroutines.flow.Flow

/**
 * Declared as an abstract class rather than an interface so that
 * [replaceGeometry] can be a real transactional method - Kotlin interface
 * default methods need extra compiler flags to work reliably with Room.
 */
@Dao
abstract class TrackDao {

    @Query("SELECT * FROM tracks WHERE active = 1 ORDER BY name COLLATE NOCASE")
    abstract fun observeActiveTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks ORDER BY name COLLATE NOCASE")
    abstract fun observeAllTracks(): Flow<List<TrackEntity>>

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract fun observeTrack(id: Long): Flow<TrackEntity?>

    @Query("SELECT * FROM tracks WHERE id = :id")
    abstract suspend fun getTrack(id: Long): TrackEntity?

    @Insert
    abstract suspend fun insert(track: TrackEntity): Long

    @Update
    abstract suspend fun update(track: TrackEntity)

    @Query("DELETE FROM tracks WHERE id = :id")
    abstract suspend fun delete(id: Long)

    @Query(
        """
        UPDATE tracks
        SET lastSprayedAtEpochMs = MAX(COALESCE(lastSprayedAtEpochMs, 0), :sprayedAtEpochMs)
        WHERE id = :trackId
        """
    )
    abstract suspend fun setLastSprayedAt(trackId: Long, sprayedAtEpochMs: Long)

    @Query("UPDATE tracks SET lengthM = :lengthM WHERE id = :trackId")
    abstract suspend fun updateLength(trackId: Long, lengthM: Double)

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY sequence")
    abstract suspend fun getGeometry(trackId: Long): List<TrackPointEntity>

    @Query("SELECT * FROM track_points WHERE trackId = :trackId ORDER BY sequence")
    abstract fun observeGeometry(trackId: Long): Flow<List<TrackPointEntity>>

    @Insert
    abstract suspend fun insertGeometry(points: List<TrackPointEntity>)

    @Query("DELETE FROM track_points WHERE trackId = :trackId")
    abstract suspend fun deleteGeometry(trackId: Long)

    /** Swaps a track's geometry and refreshes its cached length atomically. */
    @androidx.room.Transaction
    open suspend fun replaceGeometry(trackId: Long, points: List<TrackPointEntity>, lengthM: Double) {
        deleteGeometry(trackId)
        if (points.isNotEmpty()) insertGeometry(points)
        updateLength(trackId, lengthM)
    }
}
