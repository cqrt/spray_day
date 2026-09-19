package nz.mckenzie.sprayday.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import androidx.room.withTransaction
import nz.mckenzie.sprayday.data.db.RecordedBreakEntity
import nz.mckenzie.sprayday.data.db.RecordedPointEntity
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.RecordingBreak
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.recording.RecordingStatus

/**
 * What a recording already holds, for whatever picks it back up.
 *
 * Read from the fixes themselves, so a screen that is re-created mid-spray says how far
 * the pass has really got rather than starting again from nothing.
 */
data class RecordingProgress(
    val pointCount: Int = 0,
    val distanceM: Double = 0.0,
    /** The last fix recorded, which is the ground the next one is compared against. */
    val lastPoint: GeoPoint? = null
) {
    companion object {
        val EMPTY = RecordingProgress()

        fun of(points: List<GeoPoint>) = RecordingProgress(
            pointCount = points.size,
            distanceM = polylineLengthMeters(points),
            lastPoint = points.lastOrNull()
        )
    }
}

/**
 * GPS recordings.
 *
 * Every accepted fix is written as it arrives, so a crash, process kill or flat
 * battery cannot lose more than a single point.
 */
class RecordingRepository(private val db: SprayDayDatabase) {

    private val recordingDao = db.recordingDao()
    private val sprayEventDao = db.sprayEventDao()

    suspend fun startRecording(
        name: String,
        assetId: Long? = null,
        startedAtEpochMs: Long = System.currentTimeMillis()
    ): Long = recordingDao.insertSession(
        RecordedSessionEntity(
            name = name,
            assetId = assetId,
            startedAtEpochMs = startedAtEpochMs,
            status = RecordingStatus.RECORDING.name
        )
    )

    /** Appends one accepted fix, assigning the next sequence number. */
    suspend fun appendPoint(sessionId: Long, point: GeoPoint): Long {
        val sequence = (recordingDao.lastSequence(sessionId) ?: -1) + 1
        return recordingDao.insertPoint(
            RecordedPointEntity(
                sessionId = sessionId,
                sequence = sequence,
                lat = point.lat,
                lng = point.lng,
                altitudeM = point.altitudeM,
                accuracyM = point.accuracyM,
                speedMps = point.speedMps,
                bearingDeg = point.bearingDeg,
                recordedAtEpochMs = point.timeMs
            )
        )
    }

    suspend fun setStatus(sessionId: Long, status: RecordingStatus) =
        recordingDao.setStatus(sessionId, status.name)

    /** Records which planned track a session is for, if chosen after starting. */
    suspend fun setSessionAsset(sessionId: Long, assetId: Long) =
        recordingDao.setSessionAsset(sessionId, assetId)

    /** Renames a session, so the recordings list matches the track it became. */
    suspend fun renameSession(sessionId: Long, name: String) =
        recordingDao.renameSession(sessionId, name)

    /** Closes a session, storing the distance and point count actually recorded. */
    suspend fun finishRecording(
        sessionId: Long,
        distanceM: Double,
        endedAtEpochMs: Long = System.currentTimeMillis()
    ) {
        val count = recordingDao.pointCount(sessionId)
        recordingDao.closeSession(
            sessionId = sessionId,
            status = RecordingStatus.FINISHED.name,
            endedAtEpochMs = endedAtEpochMs,
            distanceM = distanceM,
            pointCount = count
        )
    }

    fun observeSessions(): Flow<List<RecordedSessionEntity>> = recordingDao.observeSessions()

    /**
     * Recordings made for one planned track.
     *
     * The link is a soft one (a recording outlives the plan it was made for), so this
     * filters rather than joins.
     */
    fun observeSessionsForTrack(assetId: Long): Flow<List<RecordedSessionEntity>> =
        recordingDao.observeSessions().map { sessions -> sessions.filter { it.assetId == assetId } }

    suspend fun getSession(sessionId: Long): RecordedSessionEntity? = recordingDao.getSession(sessionId)

    /**
     * The session to reattach to on launch: one that was left recording or
     * paused, e.g. because the app was killed mid-spray. Returns null when
     * everything is finished.
     */
    suspend fun findUnfinishedSession(): RecordedSessionEntity? =
        recordingDao.findUnfinishedSession(RecordingStatus.FINISHED.name)

    fun observePoints(sessionId: Long): Flow<List<GeoPoint>> =
        recordingDao.observePoints(sessionId).map { rows -> rows.map { it.toGeoPoint() } }

    suspend fun getPoints(sessionId: Long): List<GeoPoint> =
        recordingDao.getPoints(sessionId).map { it.toGeoPoint() }

    /**
     * Opens a break: the operator has paused, so nothing between here and the moment they
     * carry on is claimed as driven or sprayed.
     *
     * Written the instant the button is pressed rather than when the pass carries on,
     * because a pass can be finished - or the phone put away - while it is paused, and the
     * stop still happened.
     */
    suspend fun beginBreak(sessionId: Long, atEpochMs: Long = System.currentTimeMillis()) {
        recordingDao.insertBreak(RecordedBreakEntity(sessionId = sessionId, fromEpochMs = atEpochMs))
    }

    /** Closes the break, at the moment the pass carried on. */
    suspend fun endBreak(sessionId: Long, atEpochMs: Long = System.currentTimeMillis()) {
        recordingDao.closeOpenBreaks(sessionId, atEpochMs)
    }

    fun observeBreaks(sessionId: Long): Flow<List<RecordingBreak>> =
        recordingDao.observeBreaks(sessionId).map { rows -> rows.map { it.toRecordingBreak() } }

    suspend fun getBreaks(sessionId: Long): List<RecordingBreak> =
        recordingDao.getBreaks(sessionId).map { it.toRecordingBreak() }

    suspend fun pointCount(sessionId: Long): Int = recordingDao.pointCount(sessionId)

    /**
     * What a session has recorded so far.
     *
     * Computed from the fixes rather than remembered, so a recording that outlived the
     * process that started it comes back with the distance and point count it really
     * has - the same fixes the coverage is measured from, and the last of them, which
     * is what the next fix has to be a plausible distance and speed from.
     */
    suspend fun recordingProgress(sessionId: Long): RecordingProgress =
        RecordingProgress.of(getPoints(sessionId))

    suspend fun deleteRecording(sessionId: Long) = db.withTransaction {
        // A spray recorded from this recording stays - it is the history - but its
        // link is cleared first, so nothing points at a session that is gone.
        sprayEventDao.clearRecordedSession(sessionId)
        recordingDao.deleteSession(sessionId)
    }
}

internal fun RecordedPointEntity.toGeoPoint() = GeoPoint(
    lat = lat,
    lng = lng,
    altitudeM = altitudeM,
    accuracyM = accuracyM,
    speedMps = speedMps,
    bearingDeg = bearingDeg,
    timeMs = recordedAtEpochMs
)

internal fun RecordedBreakEntity.toRecordingBreak() = RecordingBreak(
    fromEpochMs = fromEpochMs,
    toEpochMs = toEpochMs
)
