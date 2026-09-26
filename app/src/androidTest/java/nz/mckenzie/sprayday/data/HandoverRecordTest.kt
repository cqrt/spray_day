package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.ProductEntity
import nz.mckenzie.sprayday.data.db.RecordedSessionEntity
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.SprayEventEntity
import nz.mckenzie.sprayday.data.db.SprayEventProductEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * The handover record, assembled from what is actually in the database.
 *
 * The point of a handover record is that it can be believed: every amount traced back
 * to the spray it came from, named, and pointed at the recording that proves it. So
 * these tests build a season and check the row that comes out - rather than trusting
 * a query to match a document.
 */
@RunWith(AndroidJUnit4::class)
class HandoverRecordTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase

    /** 2026-09-14 15:32 NZST. */
    private val sprayedAt = 1_789_356_720_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        // No db.close(): see the other view-model tests.
    }

    /** One track, two products, one spray of both, and the recording that goes with it. */
    private suspend fun season(): Long {
        val assetId = AssetRepository(db).createAsset(
            name = "Home block",
            geometry = listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5010, 173.9600)),
            groupName = "Home, north",
            method = SprayMethod.BOOM,
            createdAtEpochMs = 1_700_000_000_000L
        )
        val glyphosate = db.productDao().insert(ProductEntity(name = "Glyphosate"))
        val marker = db.productDao().insert(ProductEntity(name = "Marker dye", unit = "mL"))

        val session = db.recordingDao().insertSession(
            RecordedSessionEntity(
                name = "Home block \u00b7 14 Sep",
                assetId = assetId,
                startedAtEpochMs = sprayedAt,
                status = "FINISHED"
            )
        )

        val spray = db.sprayEventDao().insertEvent(
            SprayEventEntity(
                assetId = assetId,
                sprayedAtEpochMs = sprayedAt,
                waterLitres = 400.0,
                operatorName = "Matt",
                notes = "sprayed the \"wet\" corner",
                distanceM = 2350.0,
                areaSqm = 14_100.0,
                recordedSessionId = session
            )
        )
        db.sprayEventDao().insertEventProducts(
            listOf(
                SprayEventProductEntity(sprayEventId = spray, productId = glyphosate, quantityMl = 1500.0),
                SprayEventProductEntity(sprayEventId = spray, productId = marker, quantityMl = 1450.5)
            )
        )
        return spray
    }

    private fun repository(zone: ZoneId = ZoneId.of("Pacific/Auckland")) =
        HandoverRepository(db, zone)

    @Test
    fun everyProductOfASprayIsARowAndNothingIsLostInTheJoin() = runBlocking {
        season()

        val rows = repository().rows()

        assertEquals("one row per product", 2, rows.size)
        val first = rows.first()
        assertEquals("Home block", first.assetName)
        assertEquals("Home, north", first.groupName)
        assertEquals("Glyphosate", first.productName)
        assertEquals(1500.0, first.amount, 1e-9)
        assertEquals("mL", first.unit)
        assertEquals(400.0, first.waterLitres!!, 1e-9)
        assertEquals(2350.0, first.distanceM!!, 1e-9)
        assertEquals(14_100.0, first.areaSqm!!, 1e-9)
        assertEquals("Matt", first.operatorName)
        assertEquals("Home block \u00b7 14 Sep", first.recordingName)
        assertEquals(
            "the half millilitre must survive the trip",
            1450.5,
            rows[1].amount,
            1e-9
        )
    }

    @Test
    fun theRecordRendersAsOneLinePerSprayPlusColumnNames() = runBlocking {
        season()

        val csv = repository().renderCsv(repository().rows())
        val lines = csv.trimEnd().split("\r\n")

        assertEquals("headers plus two products", 3, lines.size)
        assertTrue(lines[0].startsWith("Date,Asset,Group,Method,Product,Amount,Unit"))
        assertTrue("the group with a comma is quoted", lines[1].contains("\"Home, north\""))
        assertTrue("and how it was sprayed is in the record", lines[1].contains(",Boom,"))
        assertTrue("the note with quotes is escaped", lines[2].contains("\"\"wet\"\""))
        assertTrue("the amount keeps its half millilitre", lines[2].contains(",1450.5,"))
        assertTrue("dates are sortable", lines[1].startsWith("2026-09-14 15:32"))
    }

    @Test
    fun aSprayWithNoRecordingLeavesThatColumnEmpty() = runBlocking {
        val assetId = AssetRepository(db).createAsset(
            name = "River block",
            geometry = listOf(GeoPoint(-41.6, 173.9), GeoPoint(-41.61, 173.91)),
            createdAtEpochMs = 1_700_000_000_000L
        )
        val product = db.productDao().insert(ProductEntity(name = "Glyphosate"))
        val spray = db.sprayEventDao().insertEvent(
            SprayEventEntity(assetId = assetId, sprayedAtEpochMs = sprayedAt)
        )
        db.sprayEventDao().insertEventProducts(
            listOf(SprayEventProductEntity(sprayEventId = spray, productId = product, quantityMl = 900.0))
        )

        val row = repository().rows().single()

        assertEquals("nothing to point at", null, row.recordingName)
        assertEquals("River block", row.assetName)
        assertEquals("", repository().renderCsv(listOf(row)).trimEnd().split("\r\n")[1].split(",").last())
    }

    @Test
    fun theFileNameSaysWhatItIsAndWhen() {
        val controller = HandoverController(repository(), context, ZoneId.of("Pacific/Auckland"))

        assertEquals("spray-day-sprays-2026-09-14.csv", controller.suggestedFileName(sprayedAt))
    }

    @Test
    fun aCarparkSaysTheGroundMeasuredFromItsOwnShapeInTheAreaColumn() = runBlocking {
        // The two kinds of ground in the app, in one column. A line's area can only ever be an estimate
        // from a swath width somebody typed; a carpark's is **measured** - the boundary is the shape and
        // the app works the area out from its corners - and this is the column the operator hands to
        // somebody else, where an estimate that read like a survey would be the whole problem. About a
        // hundred metres square, so the figure can be checked against the ground it was drawn on.
        val assetId = AssetRepository(db).createAsset(
            name = "Yard",
            geometry = listOf(
                GeoPoint(-41.5000, 173.9500),
                GeoPoint(-41.5000, 173.9512),
                GeoPoint(-41.5009, 173.9512),
                GeoPoint(-41.5000, 173.9500)
            ),
            kind = AssetKind.CARPARK,
            shape = AssetKind.CARPARK.shape,
            createdAtEpochMs = 1_700_000_000_000L
        )
        val ground = AssetRepository(db).getAsset(assetId)!!.groundSqm!!
        val product = db.productDao().insert(ProductEntity(name = "Glyphosate"))
        val spray = db.sprayEventDao().insertEvent(
            SprayEventEntity(assetId = assetId, sprayedAtEpochMs = sprayedAt, areaSqm = ground)
        )
        db.sprayEventDao().insertEventProducts(
            listOf(SprayEventProductEntity(sprayEventId = spray, productId = product, quantityMl = 2_500.0))
        )

        val csv = repository().renderCsv(repository().rows())
        val header = csv.lineSequence().first().split(",")
        val row = csv.lineSequence().elementAt(1).split(",")
        val areaColumn = header.indexOf("Area (ha)")

        assertTrue("the record still has its own area column", areaColumn >= 0)
        assertEquals("Yard", row[header.indexOf("Asset")])
        assertEquals(
            "the ground inside the boundary, in the column the record has always had",
            ground / 10_000.0,
            row[areaColumn].toDouble(),
            0.01
        )
    }

    @Test
    fun anEmptySeasonStillProducesAFileWithColumnNames() = runBlocking {
        val csv = repository().renderCsv(repository().rows())

        assertEquals(1, csv.trimEnd().split("\r\n").size)
        assertTrue(csv.contains("Recording"))
    }
}
