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
 */
@RunWith(AndroidJUnit4::class)
class SprayEntryViewModelTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository

    private val line = listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5010, 173.9600))

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
}
