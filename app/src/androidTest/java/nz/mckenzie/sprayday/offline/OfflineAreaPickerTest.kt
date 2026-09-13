package nz.mckenzie.sprayday.offline

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
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.LocationSource
import nz.mckenzie.sprayday.viewmodel.OfflineAreaPickerViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Choosing an offline area by hand: what the operator picks on the map is what gets
 * stored and downloaded - the box, the zoom range, and the name.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAreaPickerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var store: OfflineTileStore
    private lateinit var manager: OfflineAreaManager
    private lateinit var tracks: TrackRepository

    private class FakeLocationSource(private val fix: GeoPoint?) : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()
        override suspend fun currentLocation(): GeoPoint? = fix
    }

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        store = OfflineTileStore(File(temp.root, "tiles"))
        // A fixed fetcher: this test is about what the picker stores and asks for, not
        // about the network.
        manager = OfflineAreaManager(
            store = store,
            dao = db.offlineAreaDao(),
            fetcherFor = { TileFetcher { _, _, _ -> TileFetcher.Result.Tile(ByteArray(20)) } }
        )
        SettingsRepository(context).setLinzApiKey("test-key")
    }

    @After
    fun tearDown() = runBlocking {
        SettingsRepository(context).setLinzApiKey("")
        // No db.close() here: the view model observes the areas table for as long as
        // it lives, and closing the database underneath it takes the whole test
        // process down with it.
    }

    private fun viewModel(fix: GeoPoint? = null) = OfflineAreaPickerViewModel(
        manager = manager,
        tracks = tracks,
        locationSource = FakeLocationSource(fix),
        settings = SettingsRepository(context)
    )

    @Test
    fun theChosenBoxZoomsAndNameAreWhatGetsDownloaded() = runBlocking {
        val viewModel = viewModel()

        viewModel.tapAt(-46.4200, 168.3400)
        assertFalse("one corner is not an area", viewModel.draft.value.isComplete)
        viewModel.tapAt(-46.4100, 168.3550)
        viewModel.setName("Bottom flat")
        viewModel.setZoomRange(13, 14)

        val expected = viewModel.draft.value
        assertTrue(expected.isComplete)

        viewModel.download()
        withTimeout(20_000) { viewModel.saved.first { it } }

        val row = db.offlineAreaDao().getAll().single()
        assertEquals("Bottom flat", row.name)
        assertEquals(13, row.minZoom)
        assertEquals(14, row.maxZoom)
        assertEquals(expected.tileCount, row.plannedTiles)
        assertEquals("the corners tapped are the box stored", -46.4200, row.minLat, 1e-6)
        assertEquals(168.3400, row.minLng, 1e-6)
        assertEquals(-46.4100, row.maxLat, 1e-6)
        assertEquals(168.3550, row.maxLng, 1e-6)
        // And exactly the tiles that were asked for: the point of choosing by hand.
        assertEquals(expected.tileCount, store.storedTileCount())
    }

    @Test
    fun anUnnamedAreaStillGetsAFindableName() = runBlocking {
        val viewModel = viewModel()
        viewModel.setName("   ")
        viewModel.tapAt(-46.4200, 168.3400)
        viewModel.tapAt(-46.4100, 168.3550)

        viewModel.download()
        withTimeout(20_000) { viewModel.saved.first { it } }

        val row = db.offlineAreaDao().getAll().single()
        assertTrue("expected a dated default, got \"${row.name}\"", row.name.startsWith("Area "))
    }

    @Test
    fun thePickerOpensOverTheDevice() = runBlocking {
        val viewModel = viewModel(fix = GeoPoint(-46.4130, 168.3480))

        val bounds = withTimeout(5_000) { viewModel.startBounds.first { it != null } }!!

        assertEquals(-46.4130, (bounds.minLat + bounds.maxLat) / 2.0, 1e-6)
        assertEquals(168.3480, (bounds.minLng + bounds.maxLng) / 2.0, 1e-6)
    }

    @Test
    fun anAreaOverTheLimitIsRefusedBeforeAnythingIsFetched() = runBlocking {
        val viewModel = viewModel()
        viewModel.tapAt(-47.0, 167.0)
        viewModel.tapAt(-45.0, 169.0)
        viewModel.setZoomRange(10, 19)

        viewModel.download()

        assertTrue("the draft should know it is too big", viewModel.draft.value.isTooBig)
        assertNotNull("and the screen should be told why", viewModel.error.value)
        assertTrue("nothing should be stored", db.offlineAreaDao().getAll().isEmpty())
        assertEquals("and no tiles fetched", 0, store.storedTileCount())
    }

    @Test
    fun anAreaWithOnlyOneCornerIsRefusedWithAnExplanation() = runBlocking {
        val viewModel = viewModel()
        viewModel.tapAt(-46.4200, 168.3400)

        viewModel.download()

        assertTrue(
            "the operator should be told what is missing: ${viewModel.error.value}",
            viewModel.error.value?.contains("two opposite corners") == true
        )
        assertTrue(db.offlineAreaDao().getAll().isEmpty())
    }
}
