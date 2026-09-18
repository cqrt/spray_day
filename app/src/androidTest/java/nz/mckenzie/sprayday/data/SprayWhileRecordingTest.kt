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
import org.junit.Assert.assertNotNull
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
    private lateinit var assetRepository: AssetRepository
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

    private suspend fun dueFor(assetId: Long): DueStatus =
        assetRepository.observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis()))
            .first()
            .single { it.asset.id == assetId }
            .due
            .status

    private suspend fun sprayCountFor(assetId: Long): Int =
        assetRepository.observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis()))
            .first()
            .single { it.asset.id == assetId }
            .sprayCount

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assetRepository = AssetRepository(db)
        sprays = SprayRepository(db)
        recordings = RecordingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun aSprayRecordedFromARecordingPointsBackAtTheSession() = runBlocking {
        val assetId = assetRepository.createAsset("Block A", plannedLine)
        val sessionId = recordings.startRecording("Spray run", assetId = assetId)

        val fixes = driveLine(500.0)
        fixes.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = polylineLengthMeters(fixes))

        val productId = sprays.addProduct("Diuron")
        sprays.recordSpray(
            assetId = assetId,
            products = listOf(SprayProductQuantity(productId, 2_500.0)),
            distanceM = polylineLengthMeters(fixes),
            recordedSessionId = sessionId
        )

        val event = sprays.observeSprayEvents(assetId).first().single()
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
            assetId,
            recordings.getSession(sessionId)!!.assetId
        )
    }

    @Test
    fun recordingASprayUpdatesTheTrafficLightAndTheHistory() = runBlocking {
        val assetId = assetRepository.createAsset("Block B", plannedLine)
        assertEquals(DueStatus.NEVER_SPRAYED, dueFor(assetId))
        assertEquals(0, sprayCountFor(assetId))

        val sessionId = recordings.startRecording("Spray run", assetId = assetId)
        driveLine(500.0).forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = 500.0)
        sprays.recordSpray(
            assetId = assetId,
            products = listOf(SprayProductQuantity(sprays.addProduct("Glyphosate"), 1_800.0)),
            recordedSessionId = sessionId
        )

        assertEquals("sprayed today, so nothing like due yet", DueStatus.NOT_DUE, dueFor(assetId))
        assertEquals(1, sprayCountFor(assetId))
    }

    @Test
    fun coverageIsMeasuredFromThePlannedGeometryAgainstTheRecording() = runBlocking {
        val assetId = assetRepository.createAsset("Block C", plannedLine)
        val sessionId = recordings.startRecording("Half a block", assetId = assetId)

        val drove = driveLine(250.0)
        drove.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = polylineLengthMeters(drove))

        val planned = assetRepository.getAssetGeometry(assetId)
        val recorded = recordings.getPoints(sessionId)

        val covered = Coverage.coveredFraction(planned, recorded)

        assertEquals("half the line was driven", 0.5, covered, 0.06)
    }

    @Test
    fun aTrackCanBeAttachedToASessionThatIsAlreadyRecording() = runBlocking {
        val assetId = assetRepository.createAsset("Block D", plannedLine)
        val sessionId = recordings.startRecording("Started before choosing")
        assertNull("nothing chose a track yet", recordings.getSession(sessionId)!!.assetId)

        recordings.setSessionAsset(sessionId, assetId)

        assertEquals(assetId, recordings.getSession(sessionId)!!.assetId)
    }

    @Test
    fun amountsRememberedForATrackAreWhatTheNextSprayStartsFrom() = runBlocking {
        val assetId = assetRepository.createAsset("Block E", plannedLine)
        val diuron = sprays.addProduct("Diuron")
        val surfactant = sprays.addProduct("Surfactant")

        sprays.rememberDefaultsForTrack(
            assetId,
            listOf(
                SprayProductQuantity(diuron, 1_800.0),
                SprayProductQuantity(surfactant, 200.0)
            )
        )

        val lines = sprays.getAssetDefaultLines(assetId).associateBy { it.productId }

        assertEquals(1_800.0, lines.getValue(diuron).defaultQuantityMl!!, 0.001)
        assertEquals(200.0, lines.getValue(surfactant).defaultQuantityMl!!, 0.001)
    }

    @Test
    fun aRecordingWithoutASprayStillLeavesNoSprayRecord() = runBlocking {
        val assetId = assetRepository.createAsset("Block F", plannedLine)
        val sessionId = recordings.startRecording("Just the line", assetId = assetId)
        driveLine(200.0).forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = 200.0)

        assertTrue("no products means no spray", sprays.observeSprayEvents(assetId).first().isEmpty())
        assertEquals(DueStatus.NEVER_SPRAYED, dueFor(assetId))
        assertEquals("but the recording is kept", 21, recordings.pointCount(sessionId))
    }

    @Test
    fun theMapCanSeeWhichPartOfALineASprayCovered() = runBlocking {
        val assetId = assetRepository.createAsset("Block G", plannedLine)
        val sessionId = recordings.startRecording("Half a block", assetId = assetId)
        val drove = driveLine(250.0)
        drove.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = polylineLengthMeters(drove))
        sprays.recordSpray(assetId = assetId, recordedSessionId = sessionId)

        val coverage = assetRepository.getSprayCoverage(assetId)

        assertNotNull("the map asks for this, and gets nothing without it", coverage)
        assertEquals("one pass, off the one spray", 1, coverage.passes.size)
        assertEquals(
            "and the fixes come with it, because they are what says which part of the line went out",
            drove.size,
            coverage.passes.single().points.size
        )
        assertNull("nothing was logged by hand", coverage.lastWithoutRecordingAtEpochMs)
    }

    @Test
    fun aSprayWithNoRecordingIsTheWholeLineAsFarAsTheMapIsConcerned() = runBlocking {
        val assetId = assetRepository.createAsset("Block H", plannedLine)
        sprays.recordSpray(assetId = assetId)

        val coverage = assetRepository.getSprayCoverage(assetId)

        assertTrue("there are no fixes to say otherwise", coverage.passes.isEmpty())
        assertNotNull(
            "but the spray happened, and a track sprayed without a recording must not read as untouched",
            coverage.lastWithoutRecordingAtEpochMs
        )
    }

    @Test
    fun aPassFromBeforeTheAssetsOwnIntervalIsNotReadBack() = runBlocking {
        // A pass this old cannot change what part of the line is coloured: whatever it covered
        // is due again by now, and reading years of fixes back to draw the same picture is
        // work the map does not need to do.
        val assetId = assetRepository.createAsset("Block I", plannedLine)
        val sessionId = recordings.startRecording("Long ago", assetId = assetId)
        val drove = driveLine(250.0)
        drove.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = polylineLengthMeters(drove))
        sprays.recordSpray(
            assetId = assetId,
            sprayedAtEpochMs = System.currentTimeMillis() - 200L * 24 * 60 * 60 * 1000,
            recordedSessionId = sessionId
        )

        val coverage = assetRepository.getSprayCoverage(assetId)

        assertTrue("an old pass cannot change what part of a line is coloured", coverage.passes.isEmpty())
    }
}
