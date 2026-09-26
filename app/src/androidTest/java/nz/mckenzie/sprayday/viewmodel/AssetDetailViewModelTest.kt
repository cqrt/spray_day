package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.ui.AssetEditFields
import nz.mckenzie.sprayday.ui.AssetEditResult
import nz.mckenzie.sprayday.ui.AssetEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

private const val DAY_MS = 24L * 60 * 60 * 1000

/**
 * The per-track settings.
 *
 * Every track used to be sprayed on the same hard-wired 120-day cycle, because there
 * was nowhere to say otherwise. These tests are about the two consequences of
 * changing that: the fields persist, and the interval set here is the one the
 * traffic light uses.
 *
 * The spray history is corrected from here as well, which is the same act by other
 * means: an asset's colour is worked out from that history, so taking an entry off it
 * is how a track goes back to reading as never sprayed.
 */
@RunWith(AndroidJUnit4::class)
class AssetDetailViewModelTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository
    private var assetId = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assetRepository = AssetRepository(db)
        assetId = assetRepository.createAsset(
            name = "Home block",
            geometry = listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5010, 173.9545))
        )
        Unit
    }

    @After
    fun tearDown() {
        // No db.close() here, for the reason recorded in the other view-model tests: a
        // view model that still observes a table will throw against a closed pool, and
        // the crash lands on an unrelated test.
    }

    /** The due status as the map and the lists would compute it right now. */
    private suspend fun dueStatus(): DueStatus? = assetRepository
        .observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis()))
        .first()
        .firstOrNull { it.asset.id == assetId }
        ?.due
        ?.status

    /**
     * The edit form, filled in the way the screen fills it, so these tests exercise the
     * same path the operator does rather than a shortcut only the test knows.
     */
    private fun edit(
        asset: AssetEntity,
        name: String = asset.name,
        groupName: String = "",
        kind: AssetKind = AssetKind.fromStorage(asset.kind, AssetShape.fromStorage(asset.shape)),
        method: SprayMethod = SprayMethod.fromStorage(asset.method),
        intervalDays: String = asset.intervalDays.toString(),
        swathWidthM: String = "",
        notes: String = asset.notes.orEmpty()
    ) = AssetEdits.apply(
        asset,
        AssetEditFields(
            name = name,
            groupName = groupName,
            kind = kind,
            method = method,
            intervalDays = intervalDays,
            swathWidthM = swathWidthM,
            notes = notes
        )
    )

    @Test
    fun whatTheEditFormSetsIsWhatTheTrackKeeps() = runBlocking {
        val viewModel = viewModel()
        val track = track()
        val edited = edit(
            track,
            name = "Back paddock",
            groupName = "Back",
            kind = AssetKind.ROAD,
            method = SprayMethod.KNAPSACK,
            intervalDays = "90",
            swathWidthM = "6",
            notes = "spray the fenceline twice"
        )
        assertTrue(edited is AssetEditResult.Ok)

        val ok = edited as AssetEditResult.Ok
        viewModel.save(ok.asset, ok.groupName)

        val stored = withTimeout(5_000) {
            assetRepository.observeAsset(assetId).first { it?.name == "Back paddock" }
        }!!
        assertEquals("Back", assetRepository.observeGroupName(assetId).first())
        assertEquals(SprayMethod.KNAPSACK, SprayMethod.fromStorage(stored.method))
        assertEquals(
            "a road is still a road after a rename",
            AssetKind.ROAD,
            AssetKind.fromStorage(stored.kind, AssetShape.fromStorage(stored.shape))
        )
        assertEquals(90, stored.intervalDays)
        assertEquals(6.0, stored.swathWidthM!!, 1e-9)
        assertEquals("spray the fenceline twice", stored.notes)
        // The geometry the map draws from must survive an edit.
        assertEquals(1, assetRepository.observeAssetGeometry(assetId).first().pointCount - 1)
    }

    @Test
    fun theIntervalSetOnATrackIsTheOneTheTrafficLightUses() = runBlocking {
        val viewModel = viewModel()
        // Freshly created and never sprayed: due regardless of interval.
        assertEquals(DueStatus.NEVER_SPRAYED, dueStatus())

        // Spray it now, so the interval becomes the only thing that decides.
        val now = System.currentTimeMillis()
        assetRepository.updateAsset(track().copy(lastSprayedAtEpochMs = now))
        assertEquals("120 days after spraying is not due", DueStatus.NOT_DUE, dueStatus())

        // The same track, once its owner says it wants it every 10 days - inside the
        // 14-day lead time, so the traffic light must change.
        val edited = edit(
            track().copy(lastSprayedAtEpochMs = now),
            name = "Home block",
            intervalDays = "10"
        )
        viewModel.save((edited as AssetEditResult.Ok).asset, (edited as AssetEditResult.Ok).groupName)

        withTimeout(5_000) { assetRepository.observeAsset(assetId).first { it?.intervalDays == 10 } }
        assertEquals(
            "a 10-day track sprayed today is inside its lead time",
            DueStatus.DUE_SOON,
            dueStatus()
        )
    }

    @Test
    fun aSwathWidthCanBeAddedAndRemovedAgain() = runBlocking {
        val viewModel = viewModel()

        val withWidth = edit(track(), intervalDays = "120", swathWidthM = "4.5")
            as AssetEditResult.Ok
        viewModel.save(withWidth.asset, withWidth.groupName)
        val stored = withTimeout(5_000) { assetRepository.observeAsset(assetId).first { it?.swathWidthM != null } }!!
        assertEquals(4.5, stored.swathWidthM!!, 1e-9)

        val withoutWidth = edit(stored, intervalDays = "120", swathWidthM = "")
            as AssetEditResult.Ok
        viewModel.save(withoutWidth.asset, withoutWidth.groupName)
        withTimeout(5_000) { assetRepository.observeAsset(assetId).first { it?.swathWidthM == null } }

        assertNull("removing the width must not leave a zero behind", track().swathWidthM)
        // And with no width there is no treated area to claim.
        val finalTrack = track()
        assertNull(finalTrack.swathWidthM)
    }

    /**
     * The line the detail screen shows is the asset's block, read on its own.
     *
     * It used to be read out of the edit form's state, which is the wrong place for something
     * only ever displayed: the screen would show nothing until a form had been prepared, and
     * a field that exists to be typed into is a poor place to keep something being read.
     */
    @Test
    fun theBlockOnTheDetailScreenComesFromItsOwnFlow() = runBlocking {
        val viewModel = viewModel()

        viewModel.save(track(), groupName = "Estuary")

        assertEquals("Estuary", withTimeout(5_000) { viewModel.groupName.first { it != null } })
    }

    /**
     * Clearing the history is how an asset goes back to red.
     *
     * The screen's list and the colour of the line are two readings of the same rows, so both
     * have to come back to nothing - a cleared history that still read green would be worse
     * than no button at all.
     */
    @Test
    fun clearingTheSprayHistoryEmptiesTheListAndTheTrafficLight() = runBlocking {
        val sprays = SprayRepository(db)
        val viewModel = viewModel()
        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = System.currentTimeMillis())
        assertEquals(1, withTimeout(5_000) { viewModel.history.first { it.isNotEmpty() } }.size)
        assertEquals(DueStatus.NOT_DUE, dueStatus())

        viewModel.clearSprayHistory()

        withTimeout(5_000) { viewModel.history.first { it.isEmpty() } }
        assertEquals("the line reads red again", DueStatus.NEVER_SPRAYED, dueStatus())
        assertNull(assetRepository.getAsset(assetId)!!.lastSprayedAtEpochMs)
    }

    /** One entry rather than the lot: the colour falls back to the spray before it. */
    @Test
    fun takingOneSprayOffTheHistoryPutsTheColourBackToTheOneBefore() = runBlocking {
        val sprays = SprayRepository(db)
        val viewModel = viewModel()
        val now = System.currentTimeMillis()
        sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now - 200 * DAY_MS)
        val latest = sprays.recordSpray(assetId = assetId, sprayedAtEpochMs = now)
        assertEquals(DueStatus.NOT_DUE, dueStatus())

        viewModel.deleteSpray(latest)

        withTimeout(5_000) { viewModel.history.first { it.size == 1 } }
        assertEquals(DueStatus.OVERDUE, dueStatus())
    }

    /**
     * A place is one coordinate, so the box its page asks a camera for has no size at all - and a
     * camera told to fit that goes past the imagery, which is what the page's own ceiling on its
     * zoom is for (`ASSET_PAGE_MAX_ZOOM`, 15; the measurements are in `build/verify/zoom15.txt`).
     *
     * So the box is left as the place itself rather than opened up here: a box opened up to the
     * app's frame fits at about 12, and the page would show two kilometres of country with a shed
     * somewhere in it.
     */
    @Test
    fun aPlaceHandsItsOwnCoordinateToThePage() = runBlocking {
        val placeId = assetRepository.createAsset(
            name = "Woolshed",
            geometry = listOf(GeoPoint(-41.5, 173.8)),
            kind = AssetKind.BUILDING,
            shape = AssetShape.POINT
        )
        val viewModel = AssetDetailViewModel(
            assetId = placeId,
            assetRepository = assetRepository,
            sprays = SprayRepository(db),
            recordingsRepository = RecordingRepository(db),
            settingsRepository = SettingsRepository(context),
            context = context
        )

        val bounds = withTimeout(5_000) { viewModel.bounds.first { it != null } }!!

        assertEquals("a place is one coordinate", bounds.minLat, bounds.maxLat, 0.0)
        assertEquals(bounds.minLng, bounds.maxLng, 0.0)
        assertEquals("and it is the place's own", -41.5, bounds.minLat, 1e-12)
        assertEquals(173.8, bounds.minLng, 1e-12)
    }

    private suspend fun track(): AssetEntity = assetRepository.getAsset(assetId)!!

    private fun viewModel() = AssetDetailViewModel(
        assetId = assetId,
        assetRepository = assetRepository,
        sprays = SprayRepository(db),
        recordingsRepository = RecordingRepository(db),
        settingsRepository = SettingsRepository(context),
        context = context
    )
}
