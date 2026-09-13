package nz.mckenzie.sprayday.data

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.map
import nz.mckenzie.sprayday.data.db.RecordedPointEntity
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.recording.RecordingStatus

/**
 * GPS recordings.
 *
 * Every accepted fix is written as it arrives, so a crash, process kill or flat
 * battery cannot lose more than a single point.
 */
class RecordingRepository(private val db: SprayDayDatabase) {

    private val recordingDao = db.recordingDao()

    suspend fun startRecording(
        name: String,
        trackId: Long? = null,
        startedAtEpochMs: Long = System.currentTimeMillis()
    ): Long = recordingDao.insertSession(
        RecordedSessionEntity(
            name = name,
            trackId = trackId,
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

    suspend fun pointCount(sessionId: Long): Int = recordingDao.pointCount(sessionId)

    suspend fun deleteRecording(sessionId: Long) = recordingDao.deleteSession(sessionId)
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
