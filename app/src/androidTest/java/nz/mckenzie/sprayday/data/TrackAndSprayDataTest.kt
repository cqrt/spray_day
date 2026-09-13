package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.gpx.GpxParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val DAY_MS = 24L * 60 * 60 * 1000

@RunWith(AndroidJUnit4::class)
class TrackAndSprayDataTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var tracks: TrackRepository
    private lateinit var sprays: SprayRepository

    private val now = 1_790_000_000_000L

    /** ~111.19 m long, eastwards along the equator. */
    private val line = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        sprays = SprayRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private suspend fun dueFor(trackId: Long) = tracks
        .observeTracksWithDue(TrackTicker.fixed(now))
        .first()
        .first { it.track.id == trackId }

    @Test
    fun createTrackStoresGeometryAndLength() = runBlocking {
        val id = tracks.createTrack(name = "Track 4", geometry = line)

        val geometry = tracks.getTrackGeometry(id)
        assertEquals(2, geometry.size)
        assertEquals(0.001, geometry[1].lng, 1e-9)
        assertEquals(111.19, tracks.getTrack(id)!!.lengthM, 1.0)
    }

    @Test
    fun deletingATrackCascadesItsGeometry() = runBlocking {
        val id = tracks.createTrack(name = "Track 4", geometry = line)

        tracks.deleteTrack(id)

        assertNull(tracks.getTrack(id))
        assertTrue(tracks.getTrackGeometry(id).isEmpty())
    }

    @Test
    fun neverSprayedTrackIsFlaggedAsSuch() = runBlocking {
        val id = tracks.createTrack(name = "Track 9", geometry = line)

        val due = dueFor(id)
        assertEquals(DueStatus.NEVER_SPRAYED, due.due.status)
        assertEquals(0, due.sprayCount)
        assertNull(due.due.dueDateEpochMs)
    }

    @Test
    fun sprayingATrackMakesItNotDue() = runBlocking {
        val id = tracks.createTrack(name = "Track 9", geometry = line)

        sprays.recordSpray(trackId = id, sprayedAtEpochMs = now)

        val due = dueFor(id)
        assertEquals(DueStatus.NOT_DUE, due.due.status)
        assertEquals(120L, due.due.daysUntilDue)
        assertEquals(1, due.sprayCount)
        assertEquals(now, tracks.getTrack(id)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun aSprayOlderThanTheIntervalIsOverdue() = runBlocking {
        val id = tracks.createTrack(name = "Track 9", geometry = line, intervalDays = 120)

        sprays.recordSpray(trackId = id, sprayedAtEpochMs = now - 130 * DAY_MS)

        assertEquals(DueStatus.OVERDUE, dueFor(id).due.status)
    }

    @Test
    fun aSprayInsideTheLeadWindowIsDueSoon() = runBlocking {
        val id = tracks.createTrack(name = "Track 9", geometry = line, intervalDays = 120)

        sprays.recordSpray(trackId = id, sprayedAtEpochMs = now - 110 * DAY_MS)

        assertEquals(DueStatus.DUE_SOON, dueFor(id).due.status)
    }

    @Test
    fun recordingASprayStoresMillilitresOfEachProduct() = runBlocking {
        val trackId = tracks.createTrack(name = "Track 1", geometry = line)
        val glyphosate = sprays.addProduct(name = "Glyphosate 360", rateText = "10 mL/L")
        val surfactant = sprays.addProduct(name = "Surfactant")

        val eventId = sprays.recordSpray(
            trackId = trackId,
            sprayedAtEpochMs = now,
            products = listOf(
                SprayProductQuantity(productId = glyphosate, quantityMl = 1450.0),
                SprayProductQuantity(productId = surfactant, quantityMl = 120.5)
            ),
            waterLitres = 145.0,
            operatorName = "Matt"
        )

        val items = sprays.getSprayEventProducts(eventId)
        assertEquals(2, items.size)
        assertEquals(1450.0, items.first { it.productId == glyphosate }.quantityMl, 0.001)
        assertEquals(120.5, items.first { it.productId == surfactant }.quantityMl, 0.001)
        assertEquals(145.0, sprays.getSprayEvent(eventId)!!.waterLitres!!, 0.001)
    }

    @Test
    fun repeatedSpraysAreCountedAndKeepTheLatestDate() = runBlocking {
        val trackId = tracks.createTrack(name = "Track 2", geometry = line)

        sprays.recordSpray(trackId = trackId, sprayedAtEpochMs = now - 200 * DAY_MS)
        sprays.recordSpray(trackId = trackId, sprayedAtEpochMs = now - 40 * DAY_MS)

        assertEquals(2, dueFor(trackId).sprayCount)
        assertEquals(now - 40 * DAY_MS, tracks.getTrack(trackId)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun lastSprayedTimestampNeverMovesBackwards() = runBlocking {
        val trackId = tracks.createTrack(name = "Track 2", geometry = line)

        sprays.recordSpray(trackId = trackId, sprayedAtEpochMs = now)
        sprays.recordSpray(trackId = trackId, sprayedAtEpochMs = now - 30 * DAY_MS)

        assertEquals(now, tracks.getTrack(trackId)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun deletingATrackCascadesItsSprayHistory() = runBlocking {
        val trackId = tracks.createTrack(name = "Track 3", geometry = line)
        val productId = sprays.addProduct(name = "Product X")
        val eventId = sprays.recordSpray(
            trackId = trackId,
            sprayedAtEpochMs = now,
            products = listOf(SprayProductQuantity(productId, 500.0))
        )

        tracks.deleteTrack(trackId)

        assertNull(sprays.getSprayEvent(eventId))
        assertTrue(sprays.getSprayEventProducts(eventId).isEmpty())
    }

    @Test
    fun trackProductDefaultsAreStoredAndReplaced() = runBlocking {
        val trackId = tracks.createTrack(name = "Track 5", geometry = line)
        val productId = sprays.addProduct(name = "Product Y")

        sprays.setTrackProductDefault(trackId, productId, 400.0)
        sprays.setTrackProductDefault(trackId, productId, 650.0)

        val defaults = sprays.getTrackProductDefaults(trackId)
        assertEquals(1, defaults.size)
        assertEquals(650.0, defaults.first().defaultQuantityMl!!, 0.001)
    }

    @Test
    fun exportedGpxRoundTripsThroughTheParser() = runBlocking {
        val id = tracks.createTrack(name = "Block 4 & 5", geometry = line)

        val gpx = tracks.exportTrackGpx(id)
        assertNotNull(gpx)
        assertTrue(gpx!!.contains("Block 4 &amp; 5"))

        val parsed = GpxParser.parse(gpx)
        assertEquals(2, parsed.size)
        assertEquals(0.001, parsed[1].lng, 1e-7)
    }

    @Test
    fun importingGpxCreatesATrackWithTheSameGeometry() = runBlocking {
        val source = tracks.createTrack(name = "Planned", geometry = line)
        val gpx = tracks.exportTrackGpx(source)!!

        val importedId = tracks.importTrackGpx(name = "Imported", gpx = gpx)

        assertEquals(2, tracks.getTrackGeometry(importedId).size)
        assertEquals(111.19, tracks.getTrack(importedId)!!.lengthM, 1.0)
    }

    @Test
    fun importingASinglePointGpxIsRejected() = runBlocking {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk><trkseg><trkpt lat="-41.0" lon="174.0"/></trkseg></trk></gpx>
        """.trimIndent()

        val failure = runCatching { tracks.importTrackGpx(name = "Bad", gpx = gpx) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }
}
