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
import nz.mckenzie.sprayday.viewmodel.AreaSource
import nz.mckenzie.sprayday.viewmodel.OfflineViewModel
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.junit.runner.RunWith
import java.io.File

/**
 * Where the offline screen decides to put the area it offers.
 *
 * A regression lives here: the area used to be a hard-coded patch of Marlborough, so
 * an operator in Invercargill could download 13 MB of imagery of somewhere they had
 * never been - and the screen said "Spray area" without a location, so there was no
 * way to notice.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAreaSourceTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var tracks: TrackRepository
    private lateinit var store: OfflineTileStore

    private val invercargill = GeoPoint(-46.4130, 168.3480)

    /** A location source that answers with [fix] (or nothing at all). */
    private class FakeLocationSource(private val fix: GeoPoint?) : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()
        override suspend fun currentLocation(): GeoPoint? = fix
    }

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        store = OfflineTileStore(File(temp.root, "tiles"))
    }

    @After
    fun tearDown() = db.close()

    private fun viewModel(fix: GeoPoint?) = OfflineViewModel(
        manager = OfflineAreaManager(store = store, dao = db.offlineAreaDao()),
        tracks = tracks,
        locationSource = FakeLocationSource(fix),
        settingsRepository = SettingsRepository(context)
    )

    @Test
    fun theOfferedAreaIsCentredOnTheDevice() = runBlocking {
        val viewModel = viewModel(invercargill)

        val source = withTimeout(5_000) {
            viewModel.areaSource.first { it != AreaSource.UNKNOWN }
        }
        val plan = withTimeout(5_000) { viewModel.plan.first { it != null } }

        assertEquals(AreaSource.MY_LOCATION, source)
        val centreLat = (plan!!.bounds.minLat + plan.bounds.maxLat) / 2.0
        val centreLng = (plan.bounds.minLng + plan.bounds.maxLng) / 2.0
        assertEquals(invercargill.lat, centreLat, 1e-6)
        assertEquals(invercargill.lng, centreLng, 1e-6)
        assertEquals("Around my location", plan.name)
    }

    @Test
    fun withoutAPositionTheOfferedAreaCoversTheTracks() = runBlocking {
        tracks.createTrack(
            name = "Invercargill block",
            geometry = listOf(
                GeoPoint(invercargill.lat, invercargill.lng),
                GeoPoint(invercargill.lat - 0.01, invercargill.lng + 0.01)
            )
        )
        val viewModel = viewModel(fix = null)

        val source = withTimeout(5_000) {
            viewModel.areaSource.first { it != AreaSource.UNKNOWN }
        }
        val plan = withTimeout(5_000) { viewModel.plan.first { it != null } }

        assertEquals(AreaSource.MY_TRACKS, source)
        assertNotNull(plan)
        val centreLat = (plan!!.bounds.minLat + plan.bounds.maxLat) / 2.0
        assertTrue(
            "the area should sit around the operator's own work, not elsewhere: $centreLat",
            centreLat < invercargill.lat && centreLat > invercargill.lat - 0.02
        )
        assertEquals("Around my tracks", plan.name)
    }

    @Test
    fun withNothingToGoOnItOffersNothingRatherThanSomewhereRandom() = runBlocking {
        val viewModel = viewModel(fix = null)

        val complaint = withTimeout(5_000) { viewModel.error.first { it != null } }

        assertNull("no area should be offered", viewModel.plan.value)
        assertEquals(AreaSource.UNKNOWN, viewModel.areaSource.value)
        assertTrue(
            "the screen should say why, not silently cache a guess: $complaint",
            complaint!!.contains("do not know where you are")
        )
    }

    @Test
    fun trackBoundsAreNullUntilThereIsGeometry() = runBlocking {
        assertNull("nothing to frame before any track exists", tracks.trackBounds())

        tracks.createTrack(
            name = "Block",
            geometry = listOf(
                GeoPoint(-46.40, 168.30),
                GeoPoint(-46.45, 168.40)
            )
        )

        val bounds = tracks.trackBounds()
        assertNotNull(bounds)
        assertEquals(-46.45, bounds!!.minLat, 1e-9)
        assertEquals(168.30, bounds.minLng, 1e-9)
        assertEquals(-46.40, bounds.maxLat, 1e-9)
        assertEquals(168.40, bounds.maxLng, 1e-9)
    }
}
