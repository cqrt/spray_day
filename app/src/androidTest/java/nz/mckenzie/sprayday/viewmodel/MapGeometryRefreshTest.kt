package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.LocationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/** The map's data flow: what gets re-read from the database, and when. */
@RunWith(AndroidJUnit4::class)
class MapGeometryRefreshTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository

    /** Ticked by hand, so the test does not wait a real minute. */
    private val dueNow = MutableStateFlow(0L)

    /** Counts how often a line is read from the database. */
    private var geometryReads = 0

    private val homeLine = listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5010, 173.9600))

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assetRepository = AssetRepository(db)
        geometryReads = 0
    }

    @After
    fun tearDown() {
        // No db.close(): a view model that still observes a table would crash the
        // process, which is a lesson this suite has learned once already.
    }

    private fun viewModel() = MapViewModel(
        assetRepository = assetRepository,
        settingsRepository = SettingsRepository(context),
        locationSource = NoLocation,
        dueNow = dueNow,
        loadGeometry = { assetId ->
            geometryReads++
            assetRepository.getAssetGeometry(assetId)
        }
    )

    private suspend fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        fail("timed out waiting for $what")
    }

    /** A location source that always has a fix, as a phone in the paddock would. */
    private object FixedLocation : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()
        override suspend fun currentLocation(): GeoPoint = GeoPoint(-45.0, 170.0)
    }

    @Test
    fun theCameraIsNotFramedOnThePhoneWhileTheTracksAreStillLoading(): Unit = runBlocking {
        // A track at 41.5S, and a phone that answers from 45S. The tracks must win the
        // first frame: the frame is applied once, so getting it wrong means the operator
        // opens the map a long way from their work and stays there.
        assetRepository.createAsset("Home block", homeLine)
        val viewModel = MapViewModel(
            assetRepository = assetRepository,
            settingsRepository = SettingsRepository(context),
            locationSource = FixedLocation,
            dueNow = dueNow,
            loadGeometry = { assetId ->
                geometryReads++
                assetRepository.getAssetGeometry(assetId)
            }
        )

        val frame = withTimeout(5_000) { viewModel.initialFrame.first { it != null } }!!

        assertTrue(
            "the first frame should be the tracks, not a box around the phone: $frame",
            frame.maxLat > -42.0
        )
    }

    @Test
    fun aClockTickWithNothingChangedDoesNotReReadEveryLine() = runBlocking {
        assetRepository.createAsset("Home block", homeLine)
        val viewModel = viewModel()
        withTimeout(5_000) { viewModel.assetsWithDue.first { it.isNotEmpty() } }
        until("the first read") { geometryReads == 1 }

        // The clock ticks, which is what happens every minute in the app.
        dueNow.value = 60_000L
        delay(300)

        assertEquals(
            "a tick with nothing changed must not re-read the geometry",
            1,
            geometryReads
        )

        // And the pipeline is genuinely alive, so the assertion above is not passing by
        // accident: a real change does refresh.
        assetRepository.createAsset("River block", listOf(GeoPoint(-41.6, 173.9), GeoPoint(-41.61, 173.91)))
        until("the second track's geometry") { geometryReads >= 3 }
    }

    @Test
    fun redrawingALineRefreshesWhatTheMapDraws() = runBlocking {
        val assetId = assetRepository.createAsset("Home block", homeLine)
        val viewModel = viewModel()
        until("the first read") { geometryReads == 1 }

        // A redraw: different geometry, so a different length.
        assetRepository.replaceGeometry(
            assetId,
            listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600))
        )

        until("the redrawn geometry") { geometryReads == 2 }
        until("the map to hold the redrawn line") {
            viewModel.assetAt(-41.5000, 173.9550) == assetId
        }
    }

    @Test
    fun tappingOnATrackFindsItAndEmptyPaddockDoesNot() = runBlocking {
        val assetId = assetRepository.createAsset("Home block", homeLine)
        val viewModel = viewModel()
        until("the map to hold the line") {
            viewModel.assetAt(-41.5005, 173.9550) == assetId
        }

        assertEquals(
            "a tap on the line should open that track",
            assetId,
            viewModel.assetAt(-41.5005, 173.9550)
        )
        assertNull("a tap on empty paddock should open nothing", viewModel.assetAt(-45.0, 170.0))
    }

    /** The map tests are about tracks, not about the device's position. */
    private object NoLocation : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()
        override suspend fun currentLocation(): GeoPoint? = null
    }
}
