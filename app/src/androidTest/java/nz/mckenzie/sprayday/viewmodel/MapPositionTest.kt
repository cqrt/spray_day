package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.asFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.LocationSource
import nz.mckenzie.sprayday.tracking.LocationUnavailable
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Where the map thinks the phone is.
 *
 * The marker itself has moved: the fix is the map's own business now, so every map in the app
 * shows it rather than only the ones whose screen collected a stream - see
 * [nz.mckenzie.sprayday.tracking.DevicePositionTest] for that half. What is left to this screen
 * is the camera: asking to be put back on the map produces a frame around the fix - twice, if
 * asked twice - and a phone which cannot say where it is produces a sentence rather than a
 * button that does nothing.
 */
@RunWith(AndroidJUnit4::class)
class MapPositionTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository

    private val now = 1_790_000_000_000L

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assetRepository = AssetRepository(db)
    }

    /** No db.close() here, for the reason the other view-model tests record: a live flow
     * against a closed pool throws, and the crash lands on an unrelated test. */

    /** A source that hands over the fixes it was given, then falls silent. */
    private class FakeLocation(private val fixes: List<GeoPoint>) : LocationSource {
        override fun updates(): Flow<GeoPoint> = fixes.asFlow()
        override suspend fun currentLocation(): GeoPoint? = fixes.lastOrNull()
    }

    private fun fix(lat: Double, lng: Double) = GeoPoint(lat = lat, lng = lng, accuracyM = 5f)

    private fun viewModel(source: LocationSource) = MapViewModel(
        assetRepository = assetRepository,
        settingsRepository = SettingsRepository(context),
        locationSource = source,
        dueNow = flowOf(now),
        loadGeometry = { emptyList() }
    )

    @Test
    fun askingToBePutBackOnTheMapFramesTheFixEveryTimeItIsAsked() = runBlocking {
        val viewModel = viewModel(FakeLocation(listOf(fix(-41.50, 173.95))))

        viewModel.recentreOnDevice()
        val first = withTimeout(5_000) { viewModel.recentre.first { it != null } }!!

        viewModel.recentreOnDevice()
        val second = withTimeout(5_000) { viewModel.recentre.first { it?.count == 2 } }!!

        assertTrue(
            "the frame is around where the phone is",
            first.bounds.minLat < -41.50 && first.bounds.maxLat > -41.50
        )
        assertEquals("a second tap is a second request", 2, second.count)
        assertNull(viewModel.locationNotice.value)
    }

    @Test
    fun aPhoneThatCannotSayWhereItIsSaysSoRatherThanDoingNothing() = runBlocking {
        val viewModel = viewModel(FakeLocation(emptyList()))

        viewModel.recentreOnDevice()

        val notice = withTimeout(5_000) { viewModel.locationNotice.first { it != null } }
        assertEquals(LocationUnavailable.MESSAGE, notice)
        assertNull("no fix, no camera move", viewModel.recentre.value)
    }
}
