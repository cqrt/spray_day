package nz.mckenzie.sprayday.data

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The product catalogue and the effect of deleting a recording.
 *
 * Both matter because they touch history that must not be quietly rewritten: a
 * spray record points at a product id, so renaming a product could break the
 * history if it were done by insert-and-delete; and a spray points at a recording,
 * so deleting the recording must not take the spray with it.
 */
@RunWith(AndroidJUnit4::class)
class RecordingsAndProductsTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var tracks: TrackRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository

    private val line = listOf(
        GeoPoint(-41.5, 173.9),
        GeoPoint(-41.5, 173.91)
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        sprays = SprayRepository(db)
        recordings = RecordingRepository(db)
    }

    @After
    fun tearDown() = db.close()

    @Test
    fun renamingAProductKeepsItsSprayHistory() = runBlocking {
        val trackId = tracks.createTrack("Block", line)
        val productId = sprays.addProduct("Diuron")
        val eventId = sprays.recordSpray(
            trackId = trackId,
            products = listOf(SprayProductQuantity(productId, 2_500.0))
        )

        sprays.renameProduct(productId, "Diuron 90WG")

        assertEquals("Diuron 90WG", sprays.getProduct(productId)!!.name)
        val line = sprays.getSprayEventProductLines(eventId).single()
        assertEquals("history follows the rename", "Diuron 90WG", line.name)
        assertEquals(2_500.0, line.quantityMl, 0.001)
    }

    @Test
    fun aProductCannotBeRenamedOntoAnotherProductsName() = runBlocking {
        val diuron = sprays.addProduct("Diuron")
        sprays.addProduct("Glyphosate")

        val failure = runCatching { sprays.renameProduct(diuron, "Glyphosate") }

        assertTrue(
            "expected the unique name to be enforced, got ${failure.exceptionOrNull()}",
            failure.exceptionOrNull() != null
        )
        assertEquals("the original name must survive", "Diuron", sprays.getProduct(diuron)!!.name)
    }

    @Test
    fun archivingHidesAProductFromTheFormButKeepsItsHistory() = runBlocking {
        val trackId = tracks.createTrack("Block", line)
        val productId = sprays.addProduct("Diuron")
        val eventId = sprays.recordSpray(
            trackId = trackId,
            products = listOf(SprayProductQuantity(productId, 1_500.0))
        )

        sprays.setProductArchived(productId, true)

        assertTrue(
            "an archived product must not be offered on the form",
            sprays.observeProducts().first().none { it.id == productId }
        )
        assertTrue(
            "but it must still be listed for management",
            sprays.observeAllProducts().first().any { it.id == productId && it.archived }
        )
        assertEquals(
            "and its sprays must still name it",
            "Diuron",
            sprays.getSprayEventProductLines(eventId).single().name
        )

        sprays.setProductArchived(productId, false)
        assertTrue(sprays.observeProducts().first().any { it.id == productId })
        assertFalse(sprays.observeAllProducts().first().first { it.id == productId }.archived)
    }

    @Test
    fun deletingARecordingKeepsTheSprayButDropsTheLink() = runBlocking {
        val trackId = tracks.createTrack("Block", line)
        val sessionId = recordings.startRecording("Spray run", trackId = trackId)
        recordings.appendPoint(sessionId, line[0])
        recordings.finishRecording(sessionId, distanceM = 100.0)

        val productId = sprays.addProduct("Diuron")
        val eventId = sprays.recordSpray(
            trackId = trackId,
            products = listOf(SprayProductQuantity(productId, 2_000.0)),
            recordedSessionId = sessionId
        )
        assertEquals(sessionId, sprays.getSprayEvent(eventId)!!.recordedSessionId)

        recordings.deleteRecording(sessionId)

        val event = sprays.getSprayEvent(eventId)
        assertTrue("the spray itself is history and must stay", event != null)
        assertNull("but it cannot point at a recording that is gone", event!!.recordedSessionId)
        assertEquals(1, sprays.getSprayEventProducts(eventId).size)
        assertNull(recordings.getSession(sessionId))
    }

    @Test
    fun aRecordingKeepsItsDistanceAndPointsUntilItIsDeleted() = runBlocking {
        val trackId = tracks.createTrack("Block", line)
        val sessionId = recordings.startRecording("Spray run", trackId = trackId)
        line.forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = 850.0)

        val session = recordings.getSession(sessionId)!!
        assertEquals(850.0, session.distanceM, 0.001)
        assertEquals(2, session.pointCount)
        assertEquals(trackId, session.trackId)
        assertEquals(2, recordings.getPoints(sessionId).size)

        tracks.deleteTrack(trackId)

        assertEquals(
            "deleting the plan must not delete the evidence",
            sessionId,
            recordings.getSession(sessionId)!!.id
        )
    }
}
