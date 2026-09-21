package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.gpx.GpxParser
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val DAY_MS = 24L * 60 * 60 * 1000

@RunWith(AndroidJUnit4::class)
class AssetAndSprayDataTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository

    private val now = 1_790_000_000_000L

    /** ~111.19 m long, eastwards along the equator. */
    private val line = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

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

    private suspend fun dueFor(assetId: Long) = assetRepository
        .observeAssetsWithDue(MinuteTicker.fixed(now))
        .first()
        .first { it.asset.id == assetId }

    /**
     * A track with a side track off it, stored and read back as the two paths it is.
     *
     * The claim this pins is the length: **each path counted once**. The same ground drawn the old way
     * - up the spur and back down it as part of the line - is a whole spur too long, and the length is
     * what the handover and the coverage percentage are worked out against.
     */
    @Test
    fun aTrackWithASideTrackIsStoredAsPathsAndItsLengthCountedOnce() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val spurEnd = GeoPoint(0.001, 0.0005)
        val lineWithSpur = listOf(GeoPoint(0.0, 0.0), junction, GeoPoint(0.0, 0.001))
        val spur = listOf(junction, spurEnd)

        val id = assetRepository.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(lineWithSpur, listOf(spur))
        )

        val stored = assetRepository.getAssetGeometry(id)
        assertEquals("the line and its side track", 2, stored.paths.size)
        assertEquals(lineWithSpur, stored.line)
        assertEquals(listOf(spur), stored.sideTracks)
        assertEquals(
            "the junction is in both paths, at the same two numbers",
            stored.line[1],
            stored.sideTracks[0].first()
        )
        assertEquals("every vertex of every path", 5, stored.pointCount)
        assertEquals(
            "222 m of track, not the 333 m walking the spur twice would read as",
            222.4,
            assetRepository.getAsset(id)!!.lengthM,
            0.5
        )

        // And the map reads the same thing: one asset, both of its paths, so a tap on the spur opens
        // the track it hangs off.
        assertEquals(2, assetRepository.allAssetGeometry()[id]!!.paths.size)
    }

    @Test
    fun createAssetStoresGeometryAndLength() = runBlocking {
        val id = assetRepository.createAsset(name = "Track 4", geometry = line)

        val geometry = assetRepository.getAssetGeometry(id)
        assertEquals(2, geometry.pointCount)
        assertEquals(0.001, geometry.line[1].lng, 1e-9)
        assertEquals(111.19, assetRepository.getAsset(id)!!.lengthM, 1.0)
    }

    @Test
    fun deletingATrackCascadesItsGeometry() = runBlocking {
        val id = assetRepository.createAsset(name = "Track 4", geometry = line)

        assetRepository.deleteAsset(id)

        assertNull(assetRepository.getAsset(id))
        assertTrue(assetRepository.getAssetGeometry(id).paths.isEmpty())
    }

    @Test
    fun neverSprayedTrackIsFlaggedAsSuch() = runBlocking {
        val id = assetRepository.createAsset(name = "Track 9", geometry = line)

        val due = dueFor(id)
        assertEquals(DueStatus.NEVER_SPRAYED, due.due.status)
        assertEquals(0, due.sprayCount)
        assertNull(due.due.dueDateEpochMs)
    }

    @Test
    fun sprayingATrackMakesItNotDue() = runBlocking {
        val id = assetRepository.createAsset(name = "Track 9", geometry = line)

        sprays.recordSpray(assetId = id, sprayedAtEpochMs = now)

        val due = dueFor(id)
        assertEquals(DueStatus.NOT_DUE, due.due.status)
        assertEquals(120L, due.due.daysUntilDue)
        assertEquals(1, due.sprayCount)
        assertEquals(now, assetRepository.getAsset(id)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun aSprayOlderThanTheIntervalIsOverdue() = runBlocking {
        val id = assetRepository.createAsset(name = "Track 9", geometry = line, intervalDays = 120)

        sprays.recordSpray(assetId = id, sprayedAtEpochMs = now - 130 * DAY_MS)

        assertEquals(DueStatus.OVERDUE, dueFor(id).due.status)
    }

    @Test
    fun aSprayInsideTheLeadWindowIsDueSoon() = runBlocking {
        val id = assetRepository.createAsset(name = "Track 9", geometry = line, intervalDays = 120)

        sprays.recordSpray(assetId = id, sprayedAtEpochMs = now - 110 * DAY_MS)

        assertEquals(DueStatus.DUE_SOON, dueFor(id).due.status)
    }

    @Test
    fun recordingASprayStoresMillilitresOfEachProduct() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 1", geometry = line)
        val glyphosate = sprays.addProduct(name = "Glyphosate 360", rateText = "10 mL/L")
        val surfactant = sprays.addProduct(name = "Surfactant")

        val eventId = sprays.recordSpray(
            assetId = assetId,
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
        val assetId = assetRepository.createAsset(name = "Track 2", geometry = line)

        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now - 200 * DAY_MS)
        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now - 40 * DAY_MS)

        assertEquals(2, dueFor(assetId).sprayCount)
        assertEquals(now - 40 * DAY_MS, assetRepository.getAsset(assetId)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun lastSprayedTimestampNeverMovesBackwards() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 2", geometry = line)

        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now)
        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now - 30 * DAY_MS)

        assertEquals(now, assetRepository.getAsset(assetId)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun deletingATrackCascadesItsSprayHistory() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 3", geometry = line)
        val productId = sprays.addProduct(name = "Product X")
        val eventId = sprays.recordSpray(
            assetId = assetId,
            sprayedAtEpochMs = now,
            products = listOf(SprayProductQuantity(productId, 500.0))
        )

        assetRepository.deleteAsset(assetId)

        assertNull(sprays.getSprayEvent(eventId))
        assertTrue(sprays.getSprayEventProducts(eventId).isEmpty())
    }

    /**
     * Deleting a spray has to move the asset's own last-sprayed date, or the line keeps the
     * colour of a spray that is no longer on the device. The event table is what the map and
     * the list read first, and with a spray gone the row for that asset is simply absent -
     * which would fall back to the column this puts right.
     */
    @Test
    fun deletingASprayLeavesTheOneBeforeItAsTheDateThatColoursTheLine() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 30", geometry = line)
        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now - 200 * DAY_MS)
        val latest = sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now)
        assertEquals(DueStatus.NOT_DUE, dueFor(assetId).due.status)

        assertTrue(sprays.deleteSprayEvent(latest))

        assertEquals(
            "the light reads the spray before it",
            now - 200 * DAY_MS,
            assetRepository.getAsset(assetId)!!.lastSprayedAtEpochMs
        )
        assertEquals(DueStatus.OVERDUE, dueFor(assetId).due.status)
        assertEquals(1, dueFor(assetId).sprayCount)
    }

    @Test
    fun deletingTheOnlySprayTurnsTheAssetBackToNeverSprayed() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 31", geometry = line)
        val productId = sprays.addProduct(name = "Product H")
        val eventId = sprays.recordSpray(
            assetId = assetId,
            sprayedAtEpochMs = now,
            products = listOf(SprayProductQuantity(productId, 300.0))
        )
        assertEquals(DueStatus.NOT_DUE, dueFor(assetId).due.status)

        assertTrue(sprays.deleteSprayEvent(eventId))

        assertNull(
            "nothing left to colour the line green",
            assetRepository.getAsset(assetId)!!.lastSprayedAtEpochMs
        )
        assertEquals(DueStatus.NEVER_SPRAYED, dueFor(assetId).due.status)
        assertEquals(0, dueFor(assetId).sprayCount)
        assertNull(sprays.getSprayEvent(eventId))
        assertTrue("the amounts go with the spray", sprays.getSprayEventProducts(eventId).isEmpty())
        assertFalse("and a second delete has nothing to do", sprays.deleteSprayEvent(eventId))
    }

    @Test
    fun clearingTheSprayHistoryLeavesTheAssetItsLineAndItsRecordings() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 32", geometry = line)
        val productId = sprays.addProduct(name = "Product I")
        val sessionId = recordings.startRecording("Estuary pass", assetId = assetId)
        val eventId = sprays.recordSpray(
            assetId = assetId,
            sprayedAtEpochMs = now,
            products = listOf(SprayProductQuantity(productId, 300.0)),
            recordedSessionId = sessionId
        )

        assertEquals(1, sprays.deleteSprayHistory(assetId))

        assertEquals(DueStatus.NEVER_SPRAYED, dueFor(assetId).due.status)
        assertNull(assetRepository.getAsset(assetId)!!.lastSprayedAtEpochMs)
        assertNull(sprays.getSprayEvent(eventId))
        assertEquals(
            "the line is still planned",
            2,
            assetRepository.getAssetGeometry(assetId).pointCount
        )
        assertNotNull(
            "and the recording is evidence of a pass, not history of a spray",
            recordings.getSession(sessionId)
        )
        assertEquals(
            "nothing left for the map to colour a stretch of the line with",
            AssetSprayCoverage.NONE,
            assetRepository.getSprayCoverage(assetId)
        )
        assertEquals(
            "clearing it a second time removes nothing",
            0,
            sprays.deleteSprayHistory(assetId)
        )
    }

    @Test
    fun assetProductDefaultsAreStoredAndReplaced() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Track 5", geometry = line)
        val productId = sprays.addProduct(name = "Product Y")

        sprays.setTrackProductDefault(assetId, productId, 400.0)
        sprays.setTrackProductDefault(assetId, productId, 650.0)

        val defaults = sprays.getAssetProductDefaults(assetId)
        assertEquals(1, defaults.size)
        assertEquals(650.0, defaults.first().defaultQuantityMl!!, 0.001)
    }

    @Test
    fun productDefaultsComeBackWithNamesForPreFilling() = runBlocking {
        val assetId = assetRepository.createAsset(name = "Pre-fill", geometry = line)
        val glyphosate = sprays.addProduct(name = "Glyphosate 360")
        val surfactant = sprays.addProduct(name = "Surfactant")

        sprays.rememberDefaultsForTrack(
            assetId = assetId,
            products = listOf(
                SprayProductQuantity(productId = glyphosate, quantityMl = 1450.0),
                SprayProductQuantity(productId = surfactant, quantityMl = 120.0)
            )
        )

        val lines = sprays.getAssetDefaultLines(assetId)
        assertEquals(2, lines.size)
        // Ordered by name, which is how the form shows them.
        assertEquals("Glyphosate 360", lines[0].name)
        assertEquals(1450.0, lines[0].defaultQuantityMl!!, 0.001)
        assertEquals("Surfactant", lines[1].name)
    }

    @Test
    fun sprayHistoryLinesCarryProductNamesAndAmounts() = runBlocking {
        val assetId = assetRepository.createAsset(name = "History", geometry = line)
        val productId = sprays.addProduct(name = "Product Z")
        val eventId = sprays.recordSpray(
            assetId = assetId,
            sprayedAtEpochMs = now,
            products = listOf(
                SprayProductQuantity(productId = productId, quantityMl = 875.5)
            )
        )

        val lines = sprays.getSprayEventProductLines(eventId)
        assertEquals(1, lines.size)
        assertEquals("Product Z", lines[0].name)
        assertEquals(875.5, lines[0].quantityMl, 0.001)
    }

    @Test
    fun exportedGpxRoundTripsThroughTheParser() = runBlocking {
        val id = assetRepository.createAsset(name = "Block 4 & 5", geometry = line)

        val gpx = assetRepository.exportAssetGpx(id)
        assertNotNull(gpx)
        assertTrue(gpx!!.contains("Block 4 &amp; 5"))

        val parsed = GpxParser.parse(gpx)
        assertEquals(2, parsed.size)
        assertEquals(0.001, parsed[1].lng, 1e-7)
    }

    @Test
    fun importingGpxCreatesATrackWithTheSameGeometry() = runBlocking {
        val source = assetRepository.createAsset(name = "Planned", geometry = line)
        val gpx = assetRepository.exportAssetGpx(source)!!

        val imported = assetRepository.importAssetGpx(name = "Imported", gpx = gpx)

        assertEquals(2, assetRepository.getAssetGeometry(imported.assetId).pointCount)
        assertEquals(111.19, assetRepository.getAsset(imported.assetId)!!.lengthM, 1.0)
        assertEquals("one line, and nothing else was in the file", 0, imported.sideTracks)
        assertFalse(imported.segmentsDidNotJoin)
    }

    /**
     * The file a tool writes for a track with a side track, read back as the two paths it is.
     *
     * This is round trip and creation in one: the app exports a track with a spur as two `<trkseg>`s,
     * and importing that file has to give back a track with the same two paths - the line, and a spur
     * starting on the line's own vertex - with the length counted once, which is the whole reason paths
     * exist.
     */
    @Test
    fun importingATrackWithASideTrackGivesBackBothPaths() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val spur = listOf(junction, GeoPoint(0.001, 0.0005))
        val source = assetRepository.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(listOf(GeoPoint(0.0, 0.0), junction, GeoPoint(0.0, 0.001)), listOf(spur))
        )
        val gpx = assetRepository.exportAssetGpx(source)!!

        val imported = assetRepository.importAssetGpx(name = "Gully track again", gpx = gpx)

        val stored = assetRepository.getAssetGeometry(imported.assetId)
        assertEquals("the line and its side track", 2, stored.paths.size)
        assertEquals(listOf(spur), stored.sideTracks)
        assertEquals("and the side track starts on the line's own vertex", stored.line[1], stored.sideTracks[0].first())
        assertEquals(1, imported.sideTracks)
        assertFalse(imported.segmentsDidNotJoin)
        assertEquals(
            "222 m of track, each path counted once",
            222.3,
            assetRepository.getAsset(imported.assetId)!!.lengthM,
            1.0
        )
    }

    @Test
    fun importingAFileWhoseSegmentsDoNotMeetReadsItAsOneLineAndSaysSo() = runBlocking {
        // Two segments that never touch: a tool that cuts a line up for its own reasons. That is not a
        // refusal - it is what this app has always imported - but the answer says what happened, because
        // a track with a jump in it is worth knowing about.
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk>
              <trkseg><trkpt lat="-41.0" lon="174.0"/><trkpt lat="-41.1" lon="174.1"/></trkseg>
              <trkseg><trkpt lat="-42.0" lon="175.0"/><trkpt lat="-42.1" lon="175.1"/></trkseg>
            </trk></gpx>
        """.trimIndent()

        val imported = assetRepository.importAssetGpx(name = "Two fences", gpx = gpx)

        val stored = assetRepository.getAssetGeometry(imported.assetId)
        assertEquals("one line, every point in it", 1, stored.paths.size)
        assertEquals(4, stored.pointCount)
        assertEquals(0, imported.sideTracks)
        assertTrue("and the answer says the file's segments did not meet", imported.segmentsDidNotJoin)
    }

    @Test
    fun importingASinglePointGpxIsRejected() = runBlocking {
        val gpx = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk><trkseg><trkpt lat="-41.0" lon="174.0"/></trkseg></trk></gpx>
        """.trimIndent()

        val failure = runCatching { assetRepository.importAssetGpx(name = "Bad", gpx = gpx) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
    }

    @Test
    fun anAssetCarriesTheBlockItIsWorkedWith() = runBlocking {
        assetRepository.createAsset(
            name = "Estuary road",
            geometry = line,
            groupName = "Estuary"
        )
        assetRepository.createAsset(name = "Estuary lagoon", geometry = line, groupName = "estuary")
        assetRepository.createAsset(name = "Lone track", geometry = line)

        val blocks = assetRepository.observeAssetsWithDue(MinuteTicker.fixed(now))
            .first()
            .associate { it.asset.name to it.groupName }

        assertEquals("Estuary", blocks["Estuary road"])
        assertEquals(
            "the same block however it was spelled, because the name is the block's",
            "Estuary",
            blocks["Estuary lagoon"]
        )
        assertNull("an asset in no block has no name to show", blocks["Lone track"])
    }

    @Test
    fun takingAnAssetOutOfItsBlockIsCarriedThrough() = runBlocking {
        val id = assetRepository.createAsset(name = "Estuary road", geometry = line, groupName = "Estuary")

        assetRepository.saveAssetEdits(assetRepository.getAsset(id)!!, groupName = null)

        assertNull(
            "a blank block name means it stands on its own",
            assetRepository.observeGroupName(id).first()
        )
    }

    @Test
    fun renamingABlockTakesItsAssetsWithIt() = runBlocking {
        val id = assetRepository.createAsset(name = "Estuary road", geometry = line, groupName = "Estuary")
        val block = assetRepository.observeGroups().first().single()

        assetRepository.saveBlockEdits(block.id, name = "Estuary flats", notes = "Road and lagoon")

        val stored = assetRepository.observeGroups().first().single()
        assertEquals("Estuary flats", stored.name)
        assertEquals("Road and lagoon", stored.notes)
        assertEquals(
            "the asset is in the block it was in, under the new name",
            "Estuary flats",
            assetRepository.observeGroupName(id).first()
        )
    }

    @Test
    fun deletingABlockLeavesItsAssetsWhereTheyAre() = runBlocking {
        val first = assetRepository.createAsset(name = "Estuary road", geometry = line, groupName = "Estuary")
        val second = assetRepository.createAsset(name = "Estuary lagoon", geometry = line, groupName = "Estuary")
        val block = assetRepository.observeGroups().first().single()

        assetRepository.deleteBlock(block.id)

        assertTrue("the block is gone", assetRepository.observeGroups().first().isEmpty())
        assertNull(assetRepository.observeGroupName(first).first())
        assertNull(assetRepository.observeGroupName(second).first())
        assertNotNull("and the assets are still here", assetRepository.getAsset(first))
        assertNotNull(assetRepository.getAsset(second))
    }
}
