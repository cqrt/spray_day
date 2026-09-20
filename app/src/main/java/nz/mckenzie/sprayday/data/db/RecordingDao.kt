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
     * Records which planned asset a session is for. The operator may pick the asset
     * after starting to record, so this is not only set at creation.
     */
    @Query("UPDATE recorded_sessions SET assetId = :assetId WHERE id = :sessionId")
    abstract suspend fun setSessionAsset(sessionId: Long, assetId: Long)

    /**
     * Renames a session. Used when a recording is saved as a named asset, so the
     * recordings list and the asset list agree on what the line is called.
     */
    @Query("UPDATE recorded_sessions SET name = :name WHERE id = :sessionId")
    abstract suspend fun renameSession(sessionId: Long, name: String)

    /**
     * Writes down the operator's answer that both sides of a line were done on this pass.
     *
     * Only ever set, never cleared: the answer is part of what happened, and a later pass
     * does not take it back. See [RecordedSessionEntity.bothSidesClaimed].
     */
    @Query("UPDATE recorded_sessions SET bothSidesClaimed = 1 WHERE id = :sessionId")
    abstract suspend fun claimBothSides(sessionId: Long)

    @Query("DELETE FROM recorded_sessions WHERE id = :id")
    abstract suspend fun deleteSession(id: Long)

    /** Appends one accepted fix. Called per fix so nothing is lost on a crash. */
    @Insert
    abstract suspend fun insertPoint(point: RecordedPointEntity): Long

    @Query("SELECT MAX(sequence) FROM recorded_points WHERE sessionId = :sessionId")
    abstract suspend fun lastSequence(sessionId: Long): Int?

    @Query("SELECT COUNT(*) FROM recorded_points WHERE sessionId = :sessionId")
    abstract suspend fun pointCount(sessionId: Long): Int

    /**
     * How many recordings name an asset.
     *
     * For the desk's delete rule, which counts what is attached before it takes anything away. The
     * column is a plain nullable id with no foreign key on purpose - a recording outlives its asset -
     * so this count is the only thing that would otherwise be lost quietly at the moment of a delete.
     */
    @Query("SELECT COUNT(*) FROM recorded_sessions WHERE assetId = :assetId")
    abstract suspend fun countForAsset(assetId: Long): Int

    /**
     * Which assets any recording names, one row per recording.
     *
     * The whole-farm version of [countForAsset], for the state document: every asset's delete sentence
     * counts recordings, and asking per asset would be a query per track for a number that one read
     * gives for all of them.
     */
    @Query("SELECT assetId FROM recorded_sessions WHERE assetId IS NOT NULL")
    abstract suspend fun assetIds(): List<Long>

    @Query("SELECT * FROM recorded_points WHERE sessionId = :sessionId ORDER BY sequence")
    abstract suspend fun getPoints(sessionId: Long): List<RecordedPointEntity>

    @Query("SELECT * FROM recorded_points WHERE sessionId = :sessionId ORDER BY sequence")
    abstract fun observePoints(sessionId: Long): Flow<List<RecordedPointEntity>>

    // --- Pauses ---------------------------------------------------------------------

    /** Opens a break: the operator has stopped, and nothing in between is claimed as driven. */
    @Insert
    abstract suspend fun insertBreak(row: RecordedBreakEntity): Long

    /**
     * Closes whatever break is still open, at the moment the pass carried on.
     *
     * Deliberately closes every open break rather than the newest one: two open breaks in a
     * session would mean the pass had carried on without anyone saying so, and the later
     * stamp is the honest end for both.
     */
    @Query(
        "UPDATE recorded_breaks SET toEpochMs = :toEpochMs " +
            "WHERE sessionId = :sessionId AND toEpochMs IS NULL"
    )
    abstract suspend fun closeOpenBreaks(sessionId: Long, toEpochMs: Long)

    @Query("SELECT * FROM recorded_breaks WHERE sessionId = :sessionId ORDER BY fromEpochMs")
    abstract suspend fun getBreaks(sessionId: Long): List<RecordedBreakEntity>

    @Query("SELECT * FROM recorded_breaks WHERE sessionId = :sessionId ORDER BY fromEpochMs")
    abstract fun observeBreaks(sessionId: Long): Flow<List<RecordedBreakEntity>>
}
