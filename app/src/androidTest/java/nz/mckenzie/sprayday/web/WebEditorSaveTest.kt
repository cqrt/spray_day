package nz.mckenzie.sprayday.web

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * A save from the desk, through the repository the phone's own screens use, into a real database.
 *
 * The JVM tests hold the rules and the routes; what they cannot hold is that an edit reaches Room -
 * that the block an operator typed on a laptop becomes a block row, that the geometry, the length
 * and the spray history are left exactly as they were, and that a version read out of `/api/state`
 * is taken when nothing has moved and refused when something has. That is what this file is for, and
 * it is why the change was put through `connectedDebugAndroidTest` rather than only the unit tests.
 */
@RunWith(AndroidJUnit4::class)
class WebEditorSaveTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var assets: AssetRepository
    private lateinit var documents: WebEditorDocuments

    /** ~111.19 m long, eastwards along the equator. */
    private val line = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001))

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assets = AssetRepository(db)
        documents = WebEditorDocuments(
            assets = assets,
            sprays = SprayRepository(db),
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
        intervalDays: String = "120"
    ): String =
        """{"name":"$name","kind":"ROAD","shape":"LINE","method":"BOOM","blockName":"$block",""" +
            """"intervalDays":"$intervalDays","swathWidthM":"12.5","passesRequired":2,""" +
            """"passSeparationM":"3.0","notes":"Both edges","version":"$version"}"""

    private fun saved(answer: WebEditorWrite): WebEditorWrite.Saved {
        assertTrue("expected a save: $answer", answer is WebEditorWrite.Saved)
        return answer as WebEditorWrite.Saved
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
        assertEquals("and the line is still where it was", 2, assets.getAssetGeometry(id).size)
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
}
