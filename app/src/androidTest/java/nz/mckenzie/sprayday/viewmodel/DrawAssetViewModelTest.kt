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
}
