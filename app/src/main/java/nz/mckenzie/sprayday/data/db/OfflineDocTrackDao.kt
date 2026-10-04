package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.OnConflictStrategy
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

/** The DOC tracks kept for offline use: read by the browser, written by a download. */
@Dao
abstract class OfflineDocTrackDao {

    @Query("SELECT * FROM offline_doc_tracks ORDER BY name COLLATE NOCASE")
    abstract suspend fun getAll(): List<OfflineDocTrackEntity>

    /** A live count, for the offline screen to say what is stored. */
    @Query("SELECT COUNT(*) FROM offline_doc_tracks")
    abstract fun observeCount(): Flow<Int>

    @Query("SELECT COUNT(*) FROM offline_doc_tracks")
    abstract suspend fun count(): Int

    /** By service key, so a track downloaded again is updated rather than doubled. */
    @Insert(onConflict = OnConflictStrategy.REPLACE)
    abstract suspend fun insertAll(tracks: List<OfflineDocTrackEntity>)

    @Query("DELETE FROM offline_doc_tracks")
    abstract suspend fun deleteAll()
}
