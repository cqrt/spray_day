package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.LocationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The draw screen: what it makes, and the signal it raises when it has made it.
 *
 * Regression cover for the "draw screen flashes and closes" bug sits here too - a view
 * model outlives its screen, so a "we saved it" flag left set made the screen navigate
 * away the instant it was opened a second time. The signal has to be one-shot: raised
 * on save, consumed once acted on.
 */
@RunWith(AndroidJUnit4::class)
class DrawAssetViewModelTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun viewModel(at: GeoPoint? = null) = DrawAssetViewModel(
        assetRepository = AssetRepository(db),
        settingsRepository = SettingsRepository(context),
        locationSource = FixedLocation(at)
    )

    /**
     * The live state of a drawing session, waited for the way the screen waits for it.
     *
     * The card's numbers are `WhileSubscribed` flows, so a test that reaches for `.value` without
     * collecting gets the initial value and asserts about a number the operator never sees - the flows
     * only run while somebody is watching, which on the phone is the screen itself. Waiting for the
     * value is therefore both the honest reading and the race-free one.
     */
    private suspend fun pointsBecome(viewModel: DrawAssetViewModel, count: Int): Boolean =
        runCatching { withTimeout(5_000) { viewModel.pointCount.first { it == count } } }.isSuccess

    private suspend fun sideTracksBecome(viewModel: DrawAssetViewModel, count: Int): Boolean =
        runCatching { withTimeout(5_000) { viewModel.sideTrackCount.first { it == count } } }.isSuccess

    private suspend fun drawingBecome(viewModel: DrawAssetViewModel, drawing: Boolean): Boolean =
        runCatching { withTimeout(5_000) { viewModel.drawingSideTrack.first { it == drawing } } }.isSuccess

    /** The junction pick, which is a shared flow like the others: it has to be awaited, not read. */
    private suspend fun junctionBecomes(viewModel: DrawAssetViewModel, picked: Boolean): Boolean =
        runCatching { withTimeout(5_000) { viewModel.junctionPicked.first { it == picked } } }.isSuccess

    /** A phone that knows where it is, or one that cannot say. */
    private class FixedLocation(private val fix: GeoPoint?) : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()

        override suspend fun currentLocation(): GeoPoint? = fix
    }

    @Test
    fun theMapOpensAroundThePhoneRatherThanOnTheCountry(): Unit = runBlocking {
        val viewModel = viewModel(at = GeoPoint(lat = -46.4132, lng = 168.3538))

        val frame = withTimeout(5_000) { viewModel.initialFrame.first { it != null } }!!

        assertEquals("the frame should be around the phone", -46.4132, frame.minLat, 0.02)
        assertEquals(168.3538, frame.minLng, 0.02)
    }

    @Test
    fun aPhoneThatCannotSayWhereItIsLeavesTheMapOnItsNeutralView(): Unit = runBlocking {
        val viewModel = viewModel(at = null)

        // Nothing to frame on, so the map keeps its country-wide default rather than
        // the screen waiting for a fix that may never come.
        assertNull(withTimeout(5_000) { viewModel.initialFrame.first() })
    }

    @Test
    fun savingRaisesTheNavigationSignalAndConsumingClearsIt() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)

        viewModel.save("First track")
        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        assertNotNull("saving should raise the signal", saved)
        assertTrue("the signal should carry the new track id", saved!! > 0L)

        viewModel.consumeSaveResult()

        assertNull("after consuming, the screen must not navigate again", viewModel.savedAssetId.value)
    }

    @Test
    fun theSignalCanBeRaisedAgainByASubsequentSave() = runBlocking {
        val viewModel = viewModel()

        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        viewModel.save("First")
        withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        viewModel.consumeSaveResult()
        viewModel.clear()

        viewModel.addPoint(-41.5200, 173.9800)
        viewModel.addPoint(-41.5250, 173.9900)
        viewModel.save("Second")

        assertNotNull(
            "consuming one save must not disable the signal for the next",
            withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        )
    }

    @Test
    fun theDraftStartsEmptyAndUndoAndClearWork() = runBlocking {
        val viewModel = viewModel()

        assertTrue("a new drawing session starts with no draft", viewModel.paths.value.isEmpty())

        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        assertTrue("the card counts two points", pointsBecome(viewModel, 2))

        viewModel.undo()
        assertTrue("the card counts one point", pointsBecome(viewModel, 1))

        viewModel.clear()
        assertTrue(viewModel.paths.value.isEmpty())
    }

    @Test
    fun aSinglePointIsNotSaveableAndSaysWhy() {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)

        viewModel.save("Too short")

        assertNull("a line needs at least two points", viewModel.savedAssetId.value)
        assertNotNull("the operator should be told why", viewModel.message.value)
    }

    @Test
    fun whatTheDrawScreenShowsIsWhatTheAssetIs(): Unit = runBlocking {
        val viewModel = viewModel()

        viewModel.chooseKind(AssetKind.ROAD)
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        viewModel.save("Estuary road")

        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }!!
        val asset = AssetRepository(db).getAsset(saved)!!
        assertEquals("the kind chosen while drawing is the kind stored", AssetKind.ROAD, AssetKind.fromStorage(asset.kind))
        assertEquals(AssetShape.LINE, AssetShape.fromStorage(asset.shape))
    }

    @Test
    fun aSpotIsOneTapAndSavesWhereItIs(): Unit = runBlocking {
        val viewModel = viewModel()

        viewModel.chooseKind(AssetKind.INFRASTRUCTURE)
        viewModel.chooseShape(AssetShape.POINT)
        viewModel.addPoint(-41.5100, 173.9600)
        // Collected rather than read: canSave is a WhileSubscribed flow, so its value is
        // only computed while something is watching it - which the screen always is.
        assertTrue("one tap is enough for a place", withTimeout(5_000) { viewModel.canSave.first { it } })

        viewModel.save("Water trough")

        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }!!
        val repository = AssetRepository(db)
        val asset = repository.getAsset(saved)!!
        assertEquals(AssetKind.INFRASTRUCTURE, AssetKind.fromStorage(asset.kind))
        assertEquals(AssetShape.POINT, AssetShape.fromStorage(asset.shape))
        assertEquals("a place has no length", 0.0, asset.lengthM, 1e-9)
        assertEquals(1, repository.getAssetGeometry(saved).pointCount)
    }

    @Test
    fun tappingAgainMovesTheSpotRatherThanGrowingALine() = runBlocking {
        val viewModel = viewModel()
        viewModel.chooseKind(AssetKind.INFRASTRUCTURE)
        viewModel.chooseShape(AssetShape.POINT)

        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5200, 173.9800)

        assertTrue("a place is one coordinate", pointsBecome(viewModel, 1))
        assertEquals(-41.5200, viewModel.paths.value.first().single().lat, 1e-9)
    }

    @Test
    fun switchingToASpotKeepsTheLastTapAndDropsTheRest() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)

        viewModel.chooseKind(AssetKind.INFRASTRUCTURE)
        viewModel.chooseShape(AssetShape.POINT)

        assertTrue("the most recent tap is the one that was meant", pointsBecome(viewModel, 1))
        assertEquals(-41.5150, viewModel.paths.value.first().single().lat, 1e-9)
    }


    /* ---- A track with a side track off it ---------------------------------------------- */

    /**
     * The gesture: draw the line as far as the junction, press *Side track*, tap the spur, press
     * *Back to the track*, carry on.
     *
     * What matters is the join. The side track's first vertex is the line's own last vertex, which is
     * what makes it a side track rather than a line that happens to be near one - and it is why the
     * drawing has to happen in that order.
     */
    @Test
    fun aSideTrackStartsAtTheEndOfTheLineAndJoinsItExactly() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        val junction = viewModel.paths.value.first().last()

        viewModel.startSideTrack()
        assertTrue("the screen offers the way back", drawingBecome(viewModel, true))
        assertTrue("and the track has one side track now", sideTracksBecome(viewModel, 1))

        viewModel.addPoint(-41.5300, 173.9800)

        val spur = viewModel.paths.value[1]
        assertEquals("the join is the line's own vertex", junction, spur.first())
        assertEquals(2, spur.size)
        assertEquals("the line itself is untouched", 2, viewModel.paths.value.first().size)
        assertTrue("and every vertex is counted", pointsBecome(viewModel, 4))
    }

    @Test
    fun theLineCarriesOnAfterTheSideTrackIsFinished() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        viewModel.startSideTrack()
        viewModel.addPoint(-41.5300, 173.9800)
        viewModel.backToTheLine()

        viewModel.addPoint(-41.5200, 173.9900)

        assertTrue("back to drawing the line", drawingBecome(viewModel, false))
        assertEquals(2, viewModel.paths.value.size)
        assertEquals("the line grew by the tap after the side track", 3, viewModel.paths.value.first().size)
        assertEquals("and the side track kept what it had", 2, viewModel.paths.value[1].size)
    }

    @Test
    fun aTapOnTheTrackSaysWhereASideTrackLeavesIt() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5100, 173.9800)
        assertEquals("a line of two points", 2, viewModel.paths.value[0].size)

        // A tap **on** the track, a few metres off it and halfway along: the point goes into the line there
        // rather than out where the finger landed, because a junction has to be a vertex of the line.
        viewModel.addPoint(-41.5102, 173.9700, tapRadiusM = 40.0)

        val line = viewModel.paths.value[0]
        assertEquals("the line gained the point, and nothing else", 3, line.size)
        assertEquals("halfway along, on the line", 173.9700, line[1].lng, 0.0001)
        assertEquals("and on the line rather than where the finger was", -41.5100, line[1].lat, 0.0001)
        assertTrue("which is where a side track will leave from", junctionBecomes(viewModel, true))

        // And the side track starts there, not at the end of the track - which is the whole point.
        viewModel.startSideTrack()
        assertEquals("the junction is the side track's first vertex", line[1], viewModel.paths.value[1][0])
        assertEquals("which is the whole of it so far", 1, viewModel.paths.value[1].size)
        assertTrue("and it is off the end of the track", line[1] != line.last())
    }

    @Test
    fun aTapOutInThePaddockStillDraws() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5100, 173.9800)

        // 550 m off the track with a fingertip's tolerance: this is the operator drawing, not pointing.
        viewModel.addPoint(-41.5150, 173.9900, tapRadiusM = 40.0)

        assertEquals("the line grew", 3, viewModel.paths.value[0].size)
        assertEquals("exactly where it was tapped", -41.5150, viewModel.paths.value[0][2].lat, 0.0)
        assertTrue("and no junction was picked", junctionBecomes(viewModel, false))
    }

    @Test
    fun anUndoAfterPickingAPointLeavesTheNextSideTrackAtTheEndOfTheTrack() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5100, 173.9800)
        viewModel.addPoint(-41.5102, 173.9700, tapRadiusM = 40.0)
        assertTrue(junctionBecomes(viewModel, true))

        // The picked point is a vertex of the line, so an Undo takes it away - and a junction that is no
        // longer a vertex of the line would hang a spur off nothing.
        viewModel.undo()
        assertTrue("the pick goes with the point", junctionBecomes(viewModel, false))

        viewModel.startSideTrack()
        val line = viewModel.paths.value[0]
        assertEquals("so the side track leaves the end", line.last(), viewModel.paths.value[1][0])
    }

    @Test
    fun aSideTrackWithOnePointIsTakenOffByBackToTheTrackAndByUndo() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)

        // Pressed, then thought better of: nothing was drawn on it, so nothing is kept.
        viewModel.startSideTrack()
        assertTrue("the side track was started", drawingBecome(viewModel, true))
        viewModel.backToTheLine()
        assertEquals("one tap is not a side track", 1, viewModel.paths.value.size)
        assertTrue(drawingBecome(viewModel, false))

        // And the same by undoing the tap that started it.
        viewModel.startSideTrack()
        assertTrue("started again, to undo it this time", drawingBecome(viewModel, true))
        viewModel.undo()
        assertEquals("undo takes the side track with the junction tap", 1, viewModel.paths.value.size)
        assertTrue("and hands the operator back to the line", pointsBecome(viewModel, 2))
        assertTrue(drawingBecome(viewModel, false))
    }

    @Test
    fun undoingAtapOffTheSideTrackLeavesTheSideTrackInPlace() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        viewModel.startSideTrack()
        viewModel.addPoint(-41.5300, 173.9800)
        viewModel.addPoint(-41.5350, 173.9850)

        viewModel.undo()

        assertEquals("the side track loses its last tap", 2, viewModel.paths.value[1].size)
        assertTrue("and stays the path being drawn", drawingBecome(viewModel, true))
    }

    @Test
    fun aSideTrackIsNotOfferedUntilThereIsALineToLeave() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)

        viewModel.startSideTrack()

        assertEquals("one point is not a track yet", 1, viewModel.paths.value.size)
        assertTrue(drawingBecome(viewModel, false))
        assertNotNull("and the card says so", viewModel.message.value)
    }

    @Test
    fun savingATrackWithASideTrackStoresBothPathsAndCountsTheLengthOnce() = runBlocking {
        val repository = AssetRepository(db)
        val viewModel = DrawAssetViewModel(
            assetRepository = repository,
            settingsRepository = SettingsRepository(context),
            locationSource = FixedLocation(null)
        )
        // A line 0.001 degrees along the equator (111 m) with a 0.001-degree side track off its end.
        viewModel.addPoint(0.0, 0.0)
        viewModel.addPoint(0.0, 0.001)
        viewModel.startSideTrack()
        viewModel.addPoint(0.001, 0.001)

        viewModel.save("Gully track")
        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }!!
        assertEquals("the save raised the signal the screen acts on", saved, viewModel.savedAssetId.value)

        val stored = repository.getAssetGeometry(saved)
        assertEquals(2, stored.paths.size)
        assertEquals("the side track is stored as a path of its own", 2, stored.sideTracks.first().size)
        assertEquals(
            "222 m of track, not the 333 m of walking the spur twice",
            222.4,
            repository.getAsset(saved)!!.lengthM,
            0.5
        )
    }

    @Test
    fun savingWhileMidSideTrackDropsTheOneTapAndKeepsTheLine() = runBlocking {
        val repository = AssetRepository(db)
        val viewModel = DrawAssetViewModel(
            assetRepository = repository,
            settingsRepository = SettingsRepository(context),
            locationSource = FixedLocation(null)
        )
        viewModel.addPoint(0.0, 0.0)
        viewModel.addPoint(0.0, 0.001)
        viewModel.startSideTrack()

        viewModel.save("Gully track")

        val stored = repository.getAssetGeometry(withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }!!)
        assertEquals("the line is saved", 2, stored.line.size)
        assertEquals("and the tap with nothing on it is not", 0, stored.sideTracks.size)
    }

    @Test
    fun switchingAwayFromInfrastructurePutsTheShapeBackToALine() = runBlocking {
        val viewModel = viewModel()
        viewModel.chooseKind(AssetKind.INFRASTRUCTURE)
        viewModel.chooseShape(AssetShape.POINT)
        viewModel.addPoint(-41.5100, 173.9600)

        viewModel.chooseKind(AssetKind.TRACK)

        assertEquals("a road is not a place", AssetShape.LINE, viewModel.shape.value)
        assertFalse(
            "and a one-point line is not saveable",
            withTimeout(5_000) { viewModel.canSave.first() }
        )
    }

    /* ---- Changing a track that is already drawn ---------------------------------------- */

    /**
     * A track at the equator: 111 m east, in three vertices.
     *
     * Nowhere near the phone these tests stand in for, because that is the case this exists for: the
     * operator is changing a track they drew weeks ago, from somewhere else entirely.
     */
    private suspend fun aDrawnTrack(repository: AssetRepository): Long {
        val junction = GeoPoint(0.0, 0.0005)
        return repository.createAsset(
            name = "Gully track",
            geometry = listOf(GeoPoint(0.0, 0.0), junction, GeoPoint(0.0, 0.001)),
            groupName = "Home block"
        )
    }

    private fun changing(repository: AssetRepository, assetId: Long) = DrawAssetViewModel(
        assetRepository = repository,
        settingsRepository = SettingsRepository(context),
        locationSource = FixedLocation(GeoPoint(-41.5, 173.9)),
        editingAssetId = assetId
    )

    @Test
    fun changingATrackOpensOnItsOwnGeometryAndSavesItBack() = runBlocking {
        val repository = AssetRepository(db)
        val id = aDrawnTrack(repository)
        val viewModel = changing(repository, id)

        assertTrue("the stored line is what the taps start from", pointsBecome(viewModel, 3))
        assertEquals(
            "and the track's own name is what the screen is titled with",
            "Gully track",
            withTimeout(5_000) { viewModel.trackName.first { it != null } }
        )
        assertTrue("the screen knows it is changing rather than drawing", viewModel.editing.value)

        // A side track off the far end of the line, which is where one leaves a track.
        viewModel.startSideTrack()
        viewModel.addPoint(0.001, 0.001)
        viewModel.saveChanges()

        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        assertEquals("the same track, not a new one", id, saved)
        assertEquals(
            "and the list still holds the one row",
            1,
            repository.observeAssetsWithDue().first().size
        )

        val stored = repository.getAssetGeometry(id)
        assertEquals("the line it was, and the side track it now has", 2, stored.paths.size)
        assertEquals(3, stored.line.size)
        assertEquals(
            "the join is the line's own vertex",
            stored.line.last(),
            stored.sideTracks.first().first()
        )
        assertEquals(
            "and the cached length moved with the geometry, which is what the lists read",
            222.3,
            repository.getAsset(id)!!.lengthM,
            1.0
        )
        assertEquals(
            "while the name, which this screen never asked about, is untouched",
            "Gully track",
            repository.getAsset(id)!!.name
        )
    }

    @Test
    fun changingATrackOpensTheMapOnTheTrackRatherThanThePhone() = runBlocking {
        val repository = AssetRepository(db)
        val id = aDrawnTrack(repository)
        val viewModel = changing(repository, id)

        val frame = withTimeout(5_000) { viewModel.initialFrame.first { it != null } }!!

        assertTrue(
            "the frame holds the track, which is on the equator: $frame",
            frame.minLat < 0.0 && frame.maxLat > 0.0
        )
        assertTrue(
            "and it is not the phone's own frame, 41 degrees to the south: $frame",
            frame.maxLat > -1.0
        )
    }

    @Test
    fun clearingATrackAndSavingChangesIsRefusedRatherThanEmptyingIt() = runBlocking {
        val repository = AssetRepository(db)
        val id = aDrawnTrack(repository)
        val viewModel = changing(repository, id)
        assertTrue(pointsBecome(viewModel, 3))

        viewModel.clear()
        viewModel.saveChanges()

        assertNull("nothing was written", viewModel.savedAssetId.value)
        assertNotNull("and it says why: ${viewModel.message.value}", viewModel.message.value)
        assertEquals(
            "the track on the phone is the one it was",
            3,
            repository.getAssetGeometry(id).pointCount
        )
    }

    @Test
    fun drawingANewTrackStillMakesANewOne() = runBlocking {
        // The two modes share a view model, so the one thing that must not leak is which is in force:
        // a screen opened to draw makes a new row, and writes over nothing.
        val repository = AssetRepository(db)
        val existing = aDrawnTrack(repository)
        val viewModel = viewModel()

        viewModel.addPoint(0.0, 0.0)
        viewModel.addPoint(0.0, 0.001)
        viewModel.save("New track")

        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }!!
        assertTrue("a new row, not the track that exists", saved != existing)
        assertEquals(2, repository.observeAssetsWithDue().first().size)
        assertEquals(
            "and the track that exists was not written to",
            3,
            repository.getAssetGeometry(existing).pointCount
        )
    }
}

