package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Logging a spray from a recording.
 *
 * The link is the point: a spray logged from a recording is the one that can be
 * checked against the line actually driven afterwards, and a spray logged from the
 * track screen must not acquire a link it never had.
 *
 * And what the spray carries of the ground it was on: an area only a *carpark* can have measured for
 * it, which is the figure the handover record's own area column is read for.
 */
@RunWith(AndroidJUnit4::class)
class SprayEntryViewModelTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository

    private val line = listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5010, 173.9600))

    /**
     * A carpark's boundary, stored closed: the first corner again at the end, which is how a ring is
     * kept. About a hundred metres square, so the ground it encloses can be checked by eye.
     */
    private val yard = listOf(
        GeoPoint(-41.5000, 173.9500),
        GeoPoint(-41.5000, 173.9512),
        GeoPoint(-41.5009, 173.9512),
        GeoPoint(-41.5000, 173.9500)
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        assetRepository = AssetRepository(db)
        sprays = SprayRepository(db)
        recordings = RecordingRepository(db)
    }

    @After
    fun tearDown() {
        // No db.close(): see the other view-model tests.
    }

    private suspend fun until(what: String, condition: () -> Boolean) {
        val deadline = System.currentTimeMillis() + 5_000
        while (System.currentTimeMillis() < deadline) {
            if (condition()) return
            delay(20)
        }
        fail("timed out waiting for $what")
    }

    private suspend fun seedProduct(): Long {
        sprays.addProduct(name = "Glyphosate")
        return sprays.observeProducts().first().single().id
    }

    @Test
    fun aSprayLoggedFromARecordingPointsAtIt() = runBlocking {
        val assetId = assetRepository.createAsset("Home block", line)
        val productId = seedProduct()
        val sessionId = recordings.startRecording(name = "Home block \u00b7 14 Sep", assetId = assetId)

        val viewModel = SprayEntryViewModel(
            assetId = assetId,
            assetRepository = assetRepository,
            sprays = sprays,
            linkedSessionId = sessionId
        )
        until("the product row") { viewModel.rows.value.any { it.productId == productId } }

        viewModel.updateQuantity(productId, "1500")
        viewModel.save()
        until("the spray to be saved") { viewModel.saved.value }

        val event = sprays.observeSprayEvents(assetId).first().single()
        assertEquals(
            "the spray must point back at the recording it was logged from",
            sessionId,
            event.recordedSessionId
        )
    }

    @Test
    fun aSprayLoggedFromTheTrackItselfHasNoRecording() = runBlocking {
        val assetId = assetRepository.createAsset("Home block", line)
        val productId = seedProduct()

        val viewModel = SprayEntryViewModel(assetId = assetId, assetRepository = assetRepository, sprays = sprays)
        until("the product row") { viewModel.rows.value.any { it.productId == productId } }
        viewModel.updateQuantity(productId, "900")
        viewModel.save()
        until("the spray to be saved") { viewModel.saved.value }

        val event = sprays.observeSprayEvents(assetId).first().single()
        assertNull("nothing to link to", event.recordedSessionId)
        assertTrue("the amount still has to be there", event.id > 0L)
    }

    @Test
    fun aSprayLoggedOnACarparkCarriesTheGroundMeasuredFromItsOwnShape() = runBlocking {
        // The one area in this app that is a **measurement** rather than an estimate: the boundary is the
        // shape, the shape has an area, and a spray logged on it carries that. It is what the handover
        // record's area column is read for, and nothing here is typed - the phone works it out from the
        // corners, so the row and the asset cannot disagree about how much ground was treated.
        val assetId = assetRepository.createAsset(
            name = "Yard",
            geometry = yard,
            kind = AssetKind.CARPARK,
            shape = AssetKind.CARPARK.shape,
            createdAtEpochMs = 1_700_000_000_000L
        )
        val measured = assetRepository.getAsset(assetId)!!.groundSqm!!
        assertTrue("a yard about 100 m square has ground worth measuring: $measured", measured > 5_000.0)

        val productId = seedProduct()
        val viewModel = SprayEntryViewModel(assetId = assetId, assetRepository = assetRepository, sprays = sprays)
        until("the product row") { viewModel.rows.value.any { it.productId == productId } }
        viewModel.updateQuantity(productId, "2500")
        viewModel.save()
        until("the spray to be saved") { viewModel.saved.value }

        val event = sprays.observeSprayEvents(assetId).first().single()
        assertEquals(
            "the spray's area is the carpark's own ground, not a width somebody typed",
            measured,
            event.areaSqm!!,
            1e-9
        )
    }
}
