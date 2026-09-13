package nz.mckenzie.sprayday.data.db

import androidx.room.Dao
import androidx.room.Insert
import androidx.room.Query
import kotlinx.coroutines.flow.Flow

@Dao
abstract class RecordingDao {

    @Insert
    abstract suspend fun insertSession(session: RecordedSessionEntity): Long

    @Query("SELECT * FROM recorded_sessions WHERE id = :id")
    abstract suspend fun getSession(id: Long): RecordedSessionEntity?

    /** The most recent session that has not been finished, if any. */
    @Query(
        """
        SELECT * FROM recorded_sessions
        WHERE status != :finishedStatus
        ORDER BY startedAtEpochMs DESC
        LIMIT 1
        """
    )
    abstract suspend fun findUnfinishedSession(finishedStatus: String): RecordedSessionEntity?

    @Query("SELECT * FROM recorded_sessions ORDER BY startedAtEpochMs DESC")
    abstract fun observeSessions(): Flow<List<RecordedSessionEntity>>

    @Query(
        """
        UPDATE recorded_sessions
        SET status = :status,
            endedAtEpochMs = :endedAtEpochMs,
            distanceM = :distanceM,
            pointCount = :pointCount
        WHERE id = :sessionId
        """
    )
    abstract suspend fun closeSession(
        sessionId: Long,
        status: String,
        endedAtEpochMs: Long,
        distanceM: Double,
        pointCount: Int
    )

    @Query("UPDATE recorded_sessions SET status = :status WHERE id = :sessionId")
    abstract suspend fun setStatus(sessionId: Long, status: String)

    /**
     * Records which planned track a session is for. The operator may pick the track
     * after starting to record, so this is not only set at creation.
     */
    @Query("UPDATE recorded_sessions SET trackId = :trackId WHERE id = :sessionId")
    abstract suspend fun setSessionTrack(sessionId: Long, trackId: Long)

    @Query("DELETE FROM recorded_sessions WHERE id = :id")
    abstract suspend fun deleteSession(id: Long)

    /** Appends one accepted fix. Called per fix so nothing is lost on a crash. */
    @Insert
    abstract suspend fun insertPoint(point: RecordedPointEntity): Long

    @Query("SELECT MAX(sequence) FROM recorded_points WHERE sessionId = :sessionId")
    abstract suspend fun lastSequence(sessionId: Long): Int?

    @Query("SELECT COUNT(*) FROM recorded_points WHERE sessionId = :sessionId")
    abstract suspend fun pointCount(sessionId: Long): Int

    @Query("SELECT * FROM recorded_points WHERE sessionId = :sessionId ORDER BY sequence")
    abstract suspend fun getPoints(sessionId: Long): List<RecordedPointEntity>

    @Query("SELECT * FROM recorded_points WHERE sessionId = :sessionId ORDER BY sequence")
    abstract fun observePoints(sessionId: Long): Flow<List<RecordedPointEntity>>
}
