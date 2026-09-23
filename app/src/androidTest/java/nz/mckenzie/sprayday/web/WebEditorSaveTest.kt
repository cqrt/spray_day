package nz.mckenzie.sprayday.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.Basemap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A write from the desk, through the repository the phone's own screens use, into a real database.
 *
 * The JVM tests hold the rules and the routes; what they cannot hold is that a write reaches Room -
 * that the block an operator typed on a laptop becomes a block row, that a drawn line is stored vertex
 * for vertex with its length worked out, that the spray history is left exactly as it was, and that a
 * version read out of `/api/state` is taken when nothing has moved and refused when something has.
 * That is what this file is for, and it is why each change was put through
 * `connectedDebugAndroidTest` rather than only the unit tests.
 *
 * The delete half is here for a reason the unit tests cannot reach either: what a delete would take
 * with it is two counts in two tables, one of which - the recordings - outlives the asset it names.
 */
@RunWith(AndroidJUnit4::class)
class WebEditorSaveTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var assets: AssetRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository
    private lateinit var documents: WebEditorDocuments

    /** ~111.19 m long, eastwards along the equator. */
    private val line = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assets = AssetRepository(db)
        sprays = SprayRepository(db)
        recordings = RecordingRepository(db)
        documents = WebEditorDocuments(
            assets = assets,
            sprays = sprays,
            token = "7f3a9c7f3a9c7f3a9c7f3a9c7f3a9c7f",
            // The style is the one document nothing here reads, and a lambda that says so beats a
            // value that pretends. The fix, on the other hand, *is* read - by `state`, and not by a
            // save - so it answers honestly: this phone has no fix yet.
            basemap = { error("nothing here reads the basemap") },
            position = { null },
            readPageFile = { null },
            now = { 1_790_000_000_000L }
        )
    }

    @After
    fun tearDown() = db.close()

    /** The asset as `/api/state` carries it, which is where a desk gets its version from. */
    private suspend fun stateRecord(assetId: Long): WebEditorAssetRecord {
        val document = Json.decodeFromString(
            WebEditorDocument.serializer(),
            documents.state("phone:8799")
        )
        return document.assets.first { it.asset.id == assetId }
    }

    /**
     * The body a page sends, written out as JSON rather than built from [WebEditorEdit].
     *
     * Written by hand on purpose: a test that built its body from the class would keep passing after
     * somebody renamed a field, which is precisely the failure a page would see as "nothing saved".
     */
    private fun editBody(
        version: String,
        name: String = "Estuary road",
        block: String = "Estuary",
        intervalDays: String = "120",
        points: List<GeoPoint>? = null,
        paths: List<List<GeoPoint>>? = null
    ): String =
        """{"name":"$name","kind":"ROAD","shape":"LINE","method":"BOOM","blockName":"$block",""" +
            """"intervalDays":"$intervalDays","swathWidthM":"12.5","passesRequired":2,""" +
            """"passSeparationM":"3.0","notes":"Both edges",""" +
            (if (points == null) "" else """"points":${pointsJson(points)},""") +
            (if (paths == null) "" else """"paths":[${paths.joinToString(",") { pointsJson(it) }}],""") +
            """"version":"$version"}"""

    /**
     * The body a drawing mode sends for a new asset: the details from the form and the line from the
     * map, with no version, because a new asset has nothing to be stale against.
     */
    private fun newAssetBody(
        name: String = "Gully track",
        block: String? = null,
        points: List<GeoPoint> = line
    ): String =
        """{"name":"$name","kind":"TRACK","shape":"LINE","method":"UNSET",""" +
            """"blockName":${if (block == null) "null" else "\"$block\""},"intervalDays":"7",""" +
            """"swathWidthM":"","passesRequired":1,"passSeparationM":"","notes":null,""" +
            """"points":${pointsJson(points)}}"""

    private fun pointsJson(points: List<GeoPoint>): String =
        points.joinToString(prefix = "[", postfix = "]") { """{"lat":${it.lat},"lng":${it.lng}}""" }

    private fun saved(answer: WebEditorWrite): WebEditorWrite.Saved {
        assertTrue("expected a save: $answer", answer is WebEditorWrite.Saved)
        return answer as WebEditorWrite.Saved
    }

    private fun created(answer: WebEditorWrite): WebEditorWrite.Created {
        assertTrue("expected a new asset: $answer", answer is WebEditorWrite.Created)
        return answer as WebEditorWrite.Created
    }

    private fun removed(answer: WebEditorWrite): WebEditorWrite.Removed {
        assertTrue("expected a delete: $answer", answer is WebEditorWrite.Removed)
        return answer as WebEditorWrite.Removed
    }

    private fun refused(answer: WebEditorWrite, expected: WebEditorRefusal): WebEditorWrite.Refused {
        assertTrue("expected a refusal: $answer", answer is WebEditorWrite.Refused)
        val refusal = answer as WebEditorWrite.Refused
        assertEquals("refused for the right reason: ${refusal.message}", expected, refusal.refusal)
        return refusal
    }

    @Test
    fun aSaveFromTheDeskChangesTheDetailsAndNothingElse() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line, groupName = "Estuary")
        // A spray already logged on the phone, and a line already measured: the desk has heard of
        // neither, and neither may be lost.
        assets.updateAsset(assets.getAsset(id)!!.copy(lastSprayedAtEpochMs = 1_780_000_000_000L))
        val version = stateRecord(id).version

        val answer = saved(
            documents.save(id, editBody(version, name = "Estuary road, lower", intervalDays = "90"))
        )

        assertEquals("Estuary road, lower", answer.record.asset.name)
        assertEquals("Estuary", answer.record.groupName)
        assertTrue(
            "the answer carries the version the next edit quotes: ${answer.record.version}",
            answer.record.version.isNotBlank()
        )

        val stored = assets.getAsset(id)!!
        assertEquals("Estuary road, lower", stored.name)
        assertEquals(90, stored.intervalDays)
        assertEquals("the swath the desk typed is the repository's write", 12.5, stored.swathWidthM!!, 0.0)
        assertEquals(2, stored.passesRequired)
        assertEquals(3.0, stored.passSeparationM!!, 0.0)
        assertEquals("ROAD", stored.kind)
        // Everything the page never saw, exactly as it was.
        assertEquals(1_780_000_000_000L, stored.lastSprayedAtEpochMs)
        assertTrue(stored.active)
        assertEquals("the length is still the line's own", 111.19, stored.lengthM, 0.5)
        assertEquals("and the line is still where it was", 2, assets.getAssetGeometry(id).pointCount)
    }

    @Test
    fun aVersionFromBeforeSomebodyRenamedItIsRefused() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line)
        val version = stateRecord(id).version

        // Somebody renames it on the phone while the card is open on the desk.
        assets.saveAssetEdits(assets.getAsset(id)!!.copy(name = "Estuary road, upper"), null)

        refused(documents.save(id, editBody(version, name = "Estuary road, lower")), WebEditorRefusal.STALE)

        assertEquals(
            "the phone's name is the one that survives",
            "Estuary road, upper",
            assets.getAsset(id)!!.name
        )
    }

    @Test
    fun aSprayLoggedWhileTheCardIsOpenDoesNotRefuseTheEdit() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line)
        val version = stateRecord(id).version

        // The version is about the fields a desk may write, and a spray is not one of them: an edit
        // refused because a tractor went past while somebody was typing a new name would be the
        // feature getting in the way of the work.
        assets.updateAsset(assets.getAsset(id)!!.copy(lastSprayedAtEpochMs = 1_780_000_000_000L))

        saved(documents.save(id, editBody(version, name = "Estuary road, lower")))

        assertEquals("Estuary road, lower", assets.getAsset(id)!!.name)
        assertEquals(1_780_000_000_000L, assets.getAsset(id)!!.lastSprayedAtEpochMs)
    }

    @Test
    fun anEditCanMoveAnAssetIntoAnotherBlock() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line, groupName = "Estuary")
        val version = stateRecord(id).version

        val answer = saved(documents.save(id, editBody(version, block = "Home block")))

        assertEquals("Home block", answer.record.groupName)
        assertEquals("Home block", assets.observeGroupName(id).first())
        // The block row is the repository's own work, so the phone's list folds the asset under it
        // too - the desk started a block without a screen for blocks existing.
        assertTrue(assets.observeBlockNames().first().contains("Home block"))
    }

    @Test
    fun anAssetThePhoneDoesNotHaveIsRefusedRatherThanInvented() = runBlocking {
        refused(documents.save(4242L, editBody("whatever-version")), WebEditorRefusal.MISSING)

        assertNull("nothing was created by an edit to an id that names nothing", assets.getAsset(4242L))
    }

    @Test
    fun anArchivedAssetIsNotTheDeskToChange() = runBlocking {
        val id = assets.createAsset(name = "Old track", geometry = line)
        val version = stateRecord(id).version
        assets.updateAsset(assets.getAsset(id)!!.copy(active = false))

        // Archived assets are not in `/api/state`, so a desk holding one is holding a card written
        // against a page that has moved on - which is a refusal, not a resurrection.
        refused(documents.save(id, editBody(version, name = "Renamed anyway")), WebEditorRefusal.MISSING)

        assertEquals("Old track", assets.getAsset(id)!!.name)
    }

    @Test
    fun aLineMovedOnTheDeskIsStoredWholeAndMeasuredAgain() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line)
        val version = stateRecord(id).version
        val longer = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002))

        val answer = saved(documents.save(id, editBody(version, points = longer)))

        assertEquals(
            "the line the desk drew is the line on the phone, vertex for vertex",
            longer,
            assets.getAssetGeometry(id).line
        )
        assertEquals(
            "and the cached length moved with it, which is what the lists read",
            222.39,
            assets.getAsset(id)!!.lengthM,
            0.5
        )
        assertNotEquals("the card's version is not the version any more", version, answer.record.version)
        assertEquals("the answer carries the new one", answer.record.version, stateRecord(id).version)
    }

    @Test
    fun aLineMovedOnThePhoneRefusesACardThatDidNotSeeIt() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line)
        val version = stateRecord(id).version
        // The phone is where a track gets drawn by hand, so this is the ordinary way a card goes stale
        // about geometry rather than an exotic one: somebody walked the line again on the phone.
        val drawnOnThePhone = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.005))
        assets.replaceGeometry(id, AssetGeometry.of(drawnOnThePhone))

        refused(documents.save(id, editBody(version, points = line)), WebEditorRefusal.STALE)

        assertEquals("the phone's line is the one that survives", drawnOnThePhone, assets.getAssetGeometry(id).line)
    }

    @Test
    fun aNewTrackFromTheDeskIsStoredLikeAnyOther() = runBlocking {
        val answer = created(documents.create(newAssetBody(block = "Home block")))

        val id = answer.record.asset.id
        assertTrue("the answer is about a row that exists", id > 0)
        val stored = assets.getAsset(id)!!
        assertEquals("Gully track", stored.name)
        assertEquals("TRACK", stored.kind)
        assertEquals("the interval the form sent, not a default", 7, stored.intervalDays)
        assertTrue("a new asset is active", stored.active)
        assertEquals(line, assets.getAssetGeometry(id).line)
        assertEquals(
            "the length is worked out from the line, as it is for any track drawn on the phone",
            111.19,
            stored.lengthM,
            0.5
        )
        // The block was started by the name, exactly as it would be from the phone's own form.
        assertEquals("Home block", assets.observeGroupName(id).first())
        assertEquals("Home block", assets.observeBlockNames().first().single())
        assertTrue("the new track has a version for the desk to quote", stateRecord(id).version.isNotBlank())
    }

    @Test
    fun aNewTrackWithNothingDrawnIsRefusedAndNothingIsStored() = runBlocking {
        val answer = refused(documents.create(newAssetBody(points = emptyList())), WebEditorRefusal.INVALID)

        assertEquals("A line needs at least two points", answer.message)
        assertTrue(
            "nothing was created by a drawing with no line",
            assets.observeAssetsWithDue().first().isEmpty()
        )
    }

    /**
     * A desk's line write against a track that has a side track.
     *
     * The wire has no field for side tracks yet, so a write carrying a line would drop every spur on
     * the track - the one thing the desk must never do quietly. The details half of a card still saves,
     * so this is a refusal about the drawing rather than about the card.
     */
    @Test
    fun aLineWriteToATrackWithASideTrackIsRefusedRatherThanDroppingIt() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val spur = listOf(junction, GeoPoint(0.001, 0.0005))
        val id = assets.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(listOf(line.first(), junction, line.last()), listOf(spur))
        )
        val version = stateRecord(id).version

        val refusal = refused(
            documents.save(id, editBody(version, points = line)),
            WebEditorRefusal.INVALID
        )

        assertTrue(
            "says what is missing: ${refusal.message}",
            refusal.message.contains("side tracks")
        )
        assertTrue(
            "and what to do about it: ${refusal.message}",
            refusal.message.contains("Reload the page")
        )
        assertEquals(
            "the side track is still there, which is what the refusal is for",
            1,
            assets.getAssetGeometry(id).sideTracks.size
        )
        assertEquals("and the line the desk tried to write was not written", 3, assets.getAssetGeometry(id).line.size)
    }

    /**
     * The other half of that rule: a page that **has** been handed the side tracks can change the line.
     *
     * This is the write v0.6.27 had to refuse outright, and the two writes together are the whole
     * contract: the paths travel back with the line as the page's own record holds them, and the phone
     * writes what it is given - the side track untouched, the line as the operator tidied it, and the
     * length worked out from both.
     */
    @Test
    fun aLineWriteThatCarriesTheSideTracksChangesTheLineAndKeepsThem() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val spur = listOf(junction, GeoPoint(0.001, 0.0005))
        val id = assets.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(listOf(line.first(), junction, line.last()), listOf(spur))
        )
        val version = stateRecord(id).version
        // What the card was handed, and therefore what it sends back: the line as the phone holds it,
        // then the untouched spur.
        val asThePageHoldsIt = stateRecord(id).paths.map { it.map { point -> GeoPoint(point.lat, point.lng) } }
        assertEquals("the record carries both paths, line first", 2, asThePageHoldsIt.size)
        val tidied = listOf(line.first(), junction, GeoPoint(0.0, 0.0015))

        val answer = saved(
            documents.save(id, editBody(version, paths = listOf(tidied, asThePageHoldsIt[1])))
        )

        val stored = assets.getAssetGeometry(id)
        assertEquals("the line as the desk drew it", tidied, stored.line)
        assertEquals("and the side track, untouched", listOf(spur), stored.sideTracks)
        assertEquals(
            "with the length worked out from both, each path counted once",
            277.9,
            answer.record.asset.lengthM,
            1.0
        )
        assertEquals("and the version the desk is told is a new one", true, answer.record.version != version)
    }

    @Test
    fun aSideTrackDrawnOnTheDeskIsStoredWithTheLine() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val asThePhoneHoldsIt = listOf(line.first(), junction, line.last())
        val id = assets.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(asThePhoneHoldsIt)
        )
        val version = stateRecord(id).version
        val spur = listOf(junction, GeoPoint(0.001, 0.0005))

        // The desk drawing a side track where the line ends: the junction is the line's own vertex, every
        // path is whole, and the rules take it - so this is a write like any other, not something the phone
        // has to do for the desk.
        val answer = saved(
            documents.save(id, editBody(version, paths = listOf(asThePhoneHoldsIt, spur)))
        )

        val stored = assets.getAssetGeometry(id)
        assertEquals("the line, as the desk had it", asThePhoneHoldsIt, stored.line)
        assertEquals("and the side track it drew", listOf(spur), stored.sideTracks)
        assertEquals(
            "with the length worked out from both, each path counted once",
            222.6,
            answer.record.asset.lengthM,
            1.0
        )
        assertEquals("and a new version, so the desk can save again", true, answer.record.version != version)
    }

    @Test
    fun aSideTrackTakenOffOnTheDeskComesOffThePhoneToo() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val spur = listOf(junction, GeoPoint(0.001, 0.0005))
        val id = assets.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(listOf(line.first(), junction, line.last()), listOf(spur))
        )
        val version = stateRecord(id).version
        val asThePageHoldsIt = stateRecord(id).paths.map { it.map { point -> GeoPoint(point.lat, point.lng) } }

        // The desk tidying a spur away: a body carrying `paths` is a page that read this track's own
        // paths, so fewer of them is a decision rather than an out of date page.
        saved(documents.save(id, editBody(version, paths = listOf(asThePageHoldsIt[0]))))

        assertEquals(
            "the side track is off the phone, because the desk said so",
            0,
            assets.getAssetGeometry(id).sideTracks.size
        )
        assertEquals("and the line is what it was", 3, assets.getAssetGeometry(id).line.size)
    }

    @Test
    fun theDetailsOfATrackWithASideTrackStillSaveFromTheDesk() = runBlocking {
        val junction = GeoPoint(0.0, 0.0005)
        val id = assets.createAsset(
            name = "Gully track",
            geometry = AssetGeometry.of(
                listOf(line.first(), junction, line.last()),
                listOf(listOf(junction, GeoPoint(0.001, 0.0005)))
            )
        )
        val version = stateRecord(id).version

        val answer = saved(documents.save(id, editBody(version, name = "Gully track east")))

        assertEquals("Gully track east", answer.record.asset.name)
        assertEquals(
            "and the drawing is untouched by a write that carried no line",
            1,
            assets.getAssetGeometry(id).sideTracks.size
        )
    }

    @Test
    fun aTrackWithNothingOnItCanBeDeletedFromTheDesk() = runBlocking {
        val id = assets.createAsset(name = "Mis-drawn", geometry = line)
        val version = stateRecord(id).version

        val answer = removed(documents.remove(id, version))

        assertEquals("Mis-drawn is gone from the phone.", answer.message)
        assertNull("the row is gone", assets.getAsset(id))
        assertTrue(
            "and its points went with it, because a point belongs to an asset",
            assets.getAssetGeometry(id).paths.isEmpty()
        )
    }

    @Test
    fun aTrackWithASprayOnItIsNotDeletedFromTheDesk() = runBlocking {
        val id = assets.createAsset(name = "Estuary road", geometry = line)
        val version = stateRecord(id).version
        sprays.recordSpray(assetId = id, sprayedAtEpochMs = 1_780_000_000_000L)

        val refusal = refused(documents.remove(id, version), WebEditorRefusal.IN_USE)

        assertTrue("says how many: ${refusal.message}", refusal.message.contains("1 spray on the phone"))
        assertTrue(
            "and where the delete is done: ${refusal.message}",
            refusal.message.contains("Delete it on the phone")
        )
        assertEquals("the track is still there", id, assets.getAsset(id)!!.id)
        assertEquals("and so is its spray", 1, sprays.observeSprayEvents(id).first().size)
    }

    @Test
    fun aTrackWithARecordingOnItIsNotDeletedFromTheDesk() = runBlocking {
        // The case the schema makes sharp: a recording outlives its asset, so a delete would leave it
        // pointing at a number nothing answers to - which the recordings screen shows as "its asset has
        // since been deleted". Worth refusing for, and the sentence says which of the two it was.
        val id = assets.createAsset(name = "Estuary road", geometry = line)
        val sessionId = recordings.startRecording(name = "Morning run", assetId = id)
        val version = stateRecord(id).version

        val refusal = refused(documents.remove(id, version), WebEditorRefusal.IN_USE)

        assertTrue("names the recording: ${refusal.message}", refusal.message.contains("1 recording"))
        assertEquals(
            "and the recording still points at the track it was made against",
            id,
            recordings.getSession(sessionId)!!.assetId
        )
    }

    @Test
    fun aDeleteOfSomethingThatMovedIsRefusedRatherThanDone() = runBlocking {
        val id = assets.createAsset(name = "Mis-drawn", geometry = line)
        val version = stateRecord(id).version
        assets.saveAssetEdits(assets.getAsset(id)!!.copy(name = "Named on the phone"), null)

        refused(documents.remove(id, version), WebEditorRefusal.STALE)

        assertEquals("Named on the phone", assets.getAsset(id)!!.name)
    }

    @Test
    fun aDeleteThatDidNotSayWhatItReadIsRefusedRatherThanGuessed() = runBlocking {
        val id = assets.createAsset(name = "Mis-drawn", geometry = line)

        refused(documents.remove(id, version = null), WebEditorRefusal.INVALID)
        refused(documents.remove(id, version = ""), WebEditorRefusal.INVALID)

        assertEquals(
            "nothing was deleted by a request that did not say which track it read",
            id,
            assets.getAsset(id)!!.id
        )
    }

    @Test
    fun thePageIsServedWithTypesABrowserWillRun() = runBlocking {
        // Here rather than in a JVM test because `WebEditorDocuments` is built from repositories, and an
        // in-memory Room database is what this file has - and this is the one place the page's own file
        // types are decided.
        val pages = listOf(
            "index.html" to "text/html; charset=utf-8",
            "app.js" to "text/javascript; charset=utf-8",
            "edit.js" to "text/javascript; charset=utf-8",
            // `.mjs` is the one worth pinning: the desk's drawing code is the same file node imports to
            // test its undo stack without a browser, and a browser refuses a module whose type it does
            // not read as JavaScript - which on a desk looks like a map that will not draw, with the
            // reason only in a console nobody opens.
            "geometry.mjs" to "text/javascript; charset=utf-8",
            // The other three pure modules the page runs: what a save carries, what the glow under the
            // picked-out track is made of, and where the desk was looking when it was last open. All are
            // imported the same way, so a type a browser will not read as JavaScript is the same blank desk.
            "camera.mjs" to "text/javascript; charset=utf-8",
            "glow.mjs" to "text/javascript; charset=utf-8",
            "wire.mjs" to "text/javascript; charset=utf-8",
            "style.css" to "text/css; charset=utf-8",
            "vendor/maplibre-gl.js" to "text/javascript; charset=utf-8"
        )
        val serving = WebEditorDocuments(
            assets = assets,
            sprays = sprays,
            token = "7f3a9c7f3a9c7f3a9c7f3a9c7f3a9c7f",
            basemap = { error("nothing here reads the basemap") },
            position = { null },
            readPageFile = { name -> if (pages.any { it.first == name }) "x".toByteArray() else null },
            now = { 1_790_000_000_000L }
        )

        val root = serving.page("/")
        assertNotNull("the address with no path on the end is the page itself", root)
        assertEquals("text/html; charset=utf-8", root!!.contentType)

        for ((name, type) in pages) {
            val answer = serving.page("/$name")
            assertNotNull("the editor ships $name", answer)
            assertEquals("$name is served as the type a browser will run", type, answer!!.contentType)
        }

        assertNull("a file the editor does not ship is nothing", serving.page("/not-a-file.js"))
        assertNull(
            "and a path that walks out of the page's own directory is a path nothing ships",
            serving.page("/../../databases/spray_day.db")
        )
    }

    /**
     * What the documents say about the token, in the one place the token can be seen from outside.
     *
     * The style is where it matters: MapLibre asks for tiles and features itself, so the token has to
     * be in the URLs the style hands over or the map is blank - and a run with no token must hand
     * over URLs with no token in them, which is the same statement the bare address on the card makes.
     */
    @Test
    fun theDocumentsCarryTheTokenWhenThereIsOneAndNothingWhenThereIsNot() = runBlocking {
        fun serving(token: String?) = WebEditorDocuments(
            assets = assets,
            sprays = sprays,
            token = token,
            basemap = { Basemap.DEFAULT },
            position = { null },
            readPageFile = { null },
            now = { 1_790_000_000_000L }
        )

        val guarded = serving("7f3a9c7f3a9c7f3a9c7f3a9c7f3a9c7f").style("192.168.1.23:8799")
        assertTrue(
            "the tiles and the features carry the token: $guarded",
            guarded.contains("/api/assets.geojson?k=7f3a9c7f3a9c7f3a9c7f3a9c7f3a9c7f")
        )
        assertTrue(
            "and the tiles, which MapLibre fetches on its own: $guarded",
            guarded.contains("?k=7f3a9c7f3a9c7f3a9c7f3a9c7f3a9c7f")
        )

        val open = serving(null).style("192.168.1.23:8799")
        assertFalse("a run with no token hands out no token: $open", open.contains("k="))
        assertTrue(
            "and still says where the work and the tiles are: $open",
            open.contains("http://192.168.1.23:8799/api/assets.geojson") &&
                open.contains("http://192.168.1.23:8799/tiles/")
        )
    }
}
