package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

@RunWith(AndroidJUnit4::class)
class RecordingDataTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var recordings: RecordingRepository

    private val trackId = 42L

    private val fixes = listOf(
        GeoPoint(0.0, 0.0, accuracyM = 5f, timeMs = 1_000L),
        GeoPoint(0.0, 0.001, accuracyM = 4f, timeMs = 3_000L),
        GeoPoint(0.0, 0.002, altitudeM = 22.0, accuracyM = 6f, timeMs = 5_000L)
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        recordings = RecordingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun recordingStartsInTheRecordingState() = runBlocking {
        val sessionId = recordings.startRecording(name = "Spray run", trackId = trackId, startedAtEpochMs = 500L)

        val session = recordings.getSession(sessionId)!!
        assertEquals(RecordingStatus.RECORDING.name, session.status)
        assertEquals(trackId, session.trackId)
        assertNull(session.endedAtEpochMs)
        assertEquals(0, session.pointCount)
    }

    @Test
    fun appendedFixesAreSequencedFromZero() = runBlocking {
        val sessionId = recordings.startRecording(name = "Spray run")

        fixes.forEach { recordings.appendPoint(sessionId, it) }

        val stored = recordings.getPoints(sessionId)
        assertEquals(3, stored.size)
        assertEquals(0.0, stored[0].lng, 1e-9)
        assertEquals(0.002, stored[2].lng, 1e-9)
        assertEquals(22.0, stored[2].altitudeM!!, 1e-9)
        assertEquals(1_000L, stored[0].timeMs)
        assertEquals(3, recordings.pointCount(sessionId))
    }

    @Test
    fun finishingASessionStoresDistancePointCountAndEndTime() = runBlocking {
        val sessionId = recordings.startRecording(name = "Spray run")
        fixes.forEach { recordings.appendPoint(sessionId, it) }

        recordings.finishRecording(
            sessionId = sessionId,
            distanceM = polylineLengthMeters(fixes),
            endedAtEpochMs = 9_000L
        )

        val session = recordings.getSession(sessionId)!!
        assertEquals(RecordingStatus.FINISHED.name, session.status)
        assertEquals(9_000L, session.endedAtEpochMs)
        assertEquals(3, session.pointCount)
        // Two 0.001 degree hops along the equator.
        assertEquals(222.39, session.distanceM, 1.0)
    }

    @Test
    fun pausingKeepsTheSessionOpen() = runBlocking {
        val sessionId = recordings.startRecording(name = "Spray run")

        recordings.setStatus(sessionId, RecordingStatus.PAUSED)

        val session = recordings.getSession(sessionId)!!
        assertEquals(RecordingStatus.PAUSED.name, session.status)
        assertNull(session.endedAtEpochMs)
    }

    @Test
    fun pointsStreamReflectsStoredFixes() = runBlocking {
        val sessionId = recordings.startRecording(name = "Spray run")
        fixes.forEach { recordings.appendPoint(sessionId, it) }

        val emitted = recordings.observePoints(sessionId).first()

        assertEquals(3, emitted.size)
        assertEquals(5f, emitted[0].accuracyM!!, 0.001f)
    }

    @Test
    fun deletingASessionCascadesItsPoints() = runBlocking {
        val sessionId = recordings.startRecording(name = "Spray run")
        fixes.forEach { recordings.appendPoint(sessionId, it) }

        recordings.deleteRecording(sessionId)

        assertNull(recordings.getSession(sessionId))
        assertTrue(recordings.getPoints(sessionId).isEmpty())
    }

    @Test
    fun sessionsAreListedNewestFirst() = runBlocking {
        recordings.startRecording(name = "Older", startedAtEpochMs = 1_000L)
        recordings.startRecording(name = "Newer", startedAtEpochMs = 5_000L)

        val sessions = recordings.observeSessions().first()

        assertEquals(2, sessions.size)
        assertEquals("Newer", sessions.first().name)
    }
}
