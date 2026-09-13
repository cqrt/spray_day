package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LNG_AT_EQUATOR
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Spraying a track while recording the line - the pieces M2b joins together.
 *
 * These are repository-level because that is where the joins are: a session that
 * knows its track, a spray event that points back at the session, coverage worked
 * out from the planned geometry against the recording, and the traffic-light state
 * that has to change as a result.
 */
@RunWith(AndroidJUnit4::class)
class SprayWhileRecordingTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var tracks: TrackRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository

    /** A straight 500 m planned line running east. */
    private val plannedLine = listOf(
        GeoPoint(-41.5, 173.9),
        GeoPoint(-41.5, 173.9 + 500.0 / METRES_PER_DEG_LNG_AT_EQUATOR)
    )

    /** Fixes along that line, as far as [lengthM], one every [stepM]. */
    private fun driveLine(lengthM: Double, stepM: Double = 10.0): List<GeoPoint> {
        val fixes = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled <= lengthM) {
            fixes += GeoPoint(-41.5, 173.9 + travelled / METRES_PER_DEG_LNG_AT_EQUATOR)
            travelled += stepM
        }
        return fixes
    }

    private suspend fun dueFor(trackId: Long): DueStatus =
        tracks.observeTracksWithDue(nowProvider = flowOf(System.currentTimeMillis()))
            .first()
            .single { it.track.id == trackId }
            .due
            .status

    private suspend fun sprayCountFor(trackId: Long): Int =
        tracks.observeTracksWithDue(nowProvider = flowOf(System.currentTimeMillis()))
            .first()
            .single { it.track.id == trackId }
            .sprayCount

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        sprays = SprayRepository(db)
        recordings = RecordingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aSprayRecordedFromARecordingPointsBackAtTheSession() = runBlocking {
        val trackId = tracks.createTrack("Block A", plannedLine)
        val sessionId = recordings.startRecording("Spray run", trackId = trackId)

        val fixes = driveLine(500.0)
        fixes.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = polylineLengthMeters(fixes))

        val productId = sprays.addProduct("Diuron")
        sprays.recordSpray(
            trackId = trackId,
            products = listOf(SprayProductQuantity(productId, 2_500.0)),
            distanceM = polylineLengthMeters(fixes),
            recordedSessionId = sessionId
        )

        val event = sprays.observeSprayEvents(trackId).first().single()
        assertEquals("the spray must point at its GPS evidence", sessionId, event.recordedSessionId)
        assertEquals(2_500.0, sprays.getSprayEventProducts(event.id).single().quantityMl, 0.001)
        assertEquals(
            "the spray should carry the distance actually driven",
            polylineLengthMeters(fixes),
            event.distanceM!!,
            1.0
        )
        assertEquals(
            "and the session should know which track it was for",
            trackId,
            recordings.getSession(sessionId)!!.trackId
        )
    }

    @Test
    fun recordingASprayUpdatesTheTrafficLightAndTheHistory() = runBlocking {
        val trackId = tracks.createTrack("Block B", plannedLine)
        assertEquals(DueStatus.NEVER_SPRAYED, dueFor(trackId))
        assertEquals(0, sprayCountFor(trackId))

        val sessionId = recordings.startRecording("Spray run", trackId = trackId)
        driveLine(500.0).forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = 500.0)
        sprays.recordSpray(
            trackId = trackId,
            products = listOf(SprayProductQuantity(sprays.addProduct("Glyphosate"), 1_800.0)),
            recordedSessionId = sessionId
        )

        assertEquals("sprayed today, so nothing like due yet", DueStatus.NOT_DUE, dueFor(trackId))
        assertEquals(1, sprayCountFor(trackId))
    }

    @Test
    fun coverageIsMeasuredFromThePlannedGeometryAgainstTheRecording() = runBlocking {
        val trackId = tracks.createTrack("Block C", plannedLine)
        val sessionId = recordings.startRecording("Half a block", trackId = trackId)

        val drove = driveLine(250.0)
        drove.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = polylineLengthMeters(drove))

        val planned = tracks.getTrackGeometry(trackId)
        val recorded = recordings.getPoints(sessionId)

        val covered = Coverage.coveredFraction(planned, recorded)

        assertEquals("half the line was driven", 0.5, covered, 0.06)
    }

    @Test
    fun aTrackCanBeAttachedToASessionThatIsAlreadyRecording() = runBlocking {
        val trackId = tracks.createTrack("Block D", plannedLine)
        val sessionId = recordings.startRecording("Started before choosing")
        assertNull("nothing chose a track yet", recordings.getSession(sessionId)!!.trackId)

        recordings.setSessionTrack(sessionId, trackId)

        assertEquals(trackId, recordings.getSession(sessionId)!!.trackId)
    }

    @Test
    fun amountsRememberedForATrackAreWhatTheNextSprayStartsFrom() = runBlocking {
        val trackId = tracks.createTrack("Block E", plannedLine)
        val diuron = sprays.addProduct("Diuron")
        val surfactant = sprays.addProduct("Surfactant")

        sprays.rememberDefaultsForTrack(
            trackId,
            listOf(
                SprayProductQuantity(diuron, 1_800.0),
                SprayProductQuantity(surfactant, 200.0)
            )
        )

        val lines = sprays.getTrackDefaultLines(trackId).associateBy { it.productId }

        assertEquals(1_800.0, lines.getValue(diuron).defaultQuantityMl!!, 0.001)
        assertEquals(200.0, lines.getValue(surfactant).defaultQuantityMl!!, 0.001)
    }

    @Test
    fun aRecordingWithoutASprayStillLeavesNoSprayRecord() = runBlocking {
        val trackId = tracks.createTrack("Block F", plannedLine)
        val sessionId = recordings.startRecording("Just the line", trackId = trackId)
        driveLine(200.0).forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = 200.0)

        assertTrue("no products means no spray", sprays.observeSprayEvents(trackId).first().isEmpty())
        assertEquals(DueStatus.NEVER_SPRAYED, dueFor(trackId))
        assertEquals("but the recording is kept", 21, recordings.pointCount(sessionId))
    }
}
