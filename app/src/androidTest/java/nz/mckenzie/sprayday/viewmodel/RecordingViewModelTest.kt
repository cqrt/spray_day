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
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.tracking.TrackingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The record screen's finish flow.
 *
 * A regression lives here: recording a line used to save only a GPS session, so the
 * line never appeared on the Tracks page and there was no way to spray it again.
 * On this screen "record" means "make a track", so finishing a new line asks for a
 * name and creates one - while spraying an existing track still just records
 * against it, since that track already has a name.
 */
@RunWith(AndroidJUnit4::class)
class RecordingViewModelTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var recordings: RecordingRepository
    private lateinit var tracks: TrackRepository

    private val line = listOf(
        GeoPoint(-41.5000, 173.9500),
        GeoPoint(-41.5005, 173.9520),
        GeoPoint(-41.5010, 173.9545)
    )

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        recordings = RecordingRepository(db)
        tracks = TrackRepository(db)
        TrackingState.clear()
    }

    @After
    fun tearDown() {
        TrackingState.clear()
        // Deliberately no db.close(): this view model observes the session's points for
        // as long as it lives, so closing the database underneath it throws on a
        // background thread and takes the whole instrumentation process down - with
        // whichever test happens to be running blamed for it.
    }

    private fun viewModel() = RecordingViewModel(
        recordings = recordings,
        tracks = tracks,
        sprays = SprayRepository(db),
        settingsRepository = SettingsRepository(context),
        context = context
    )

    /**
     * A session already recording, with points, as the service would have left it.
     * Created after the view model so its init does not try to reattach to it.
     */
    private suspend fun recordingSession(): Long {
        val sessionId = recordings.startRecording(name = "Spray run")
        line.forEach { recordings.appendPoint(sessionId, it) }
        TrackingState.begin(sessionId, System.currentTimeMillis())
        TrackingState.onAcceptedFix(pointCount = line.size, distanceM = 240.0, accuracyM = 5f)
        return sessionId
    }

    private suspend fun tracksNamed(name: String) = tracks
        .observeTracksWithDue(nowProvider = flowOf(System.currentTimeMillis()))
        .first()
        .filter { it.track.name == name }

    @Test
    fun finishingANewLineAsksForANameAndCreatesATrack() = runBlocking {
        val viewModel = viewModel()
        val sessionId = recordingSession()

        viewModel.finish()

        val suggested = withTimeout(5_000) { viewModel.pendingTrackName.first { it != null } }
        assertTrue("a suggested name should be offered, got $suggested", !suggested.isNullOrBlank())
        assertNull(
            "nothing should be finished until the name is confirmed",
            recordings.getSession(sessionId)!!.endedAtEpochMs
        )

        viewModel.confirmFinish("Paddock 3")

        // The view model's init may have attached to the session and set a message of
        // its own, so wait for the one that says what finished.
        val message = withTimeout(5_000) {
            viewModel.message.first { it?.contains("Paddock 3") == true }
        }
        assertTrue("the message should name the track: $message", message!!.contains("Paddock 3"))

        val session = recordings.getSession(sessionId)!!
        assertEquals(RecordingStatus.FINISHED.name, session.status)
        assertEquals("the recording should carry the name too", "Paddock 3", session.name)

        val stored = tracksNamed("Paddock 3").single()
        assertEquals("the recording should point at the track it became", stored.track.id, session.trackId)
        assertEquals(
            "the track should carry the recorded line",
            line.size,
            tracks.getTrackGeometry(stored.track.id).size
        )
        assertTrue("its length should have been computed", stored.track.lengthM > 0.0)
        assertEquals("a freshly recorded track has not been sprayed", DueStatus.NEVER_SPRAYED, stored.due.status)
    }

    @Test
    fun finishingAgainstAnExistingTrackDoesNotAskForAName() = runBlocking {
        val trackId = tracks.createTrack("Block A", line)
        val viewModel = viewModel()
        val sessionId = recordingSession()

        viewModel.selectTrack(trackId)
        withTimeout(5_000) { viewModel.selectedTrackId.first { it == trackId } }

        viewModel.finish()

        assertNull("an existing track already has a name", viewModel.pendingTrackName.value)
        val message = withTimeout(5_000) {
            viewModel.message.first { it?.startsWith("Saved") == true }
        }
        assertFalse(
            "no new track should have been created: $message",
            message!!.contains("on the Tracks page")
        )
        val session = recordings.getSession(sessionId)!!
        assertEquals(RecordingStatus.FINISHED.name, session.status)
        assertEquals(trackId, session.trackId)
        assertTrue(
            "the recording should be named after the track and the date, so the three " +
                "passes a year are told apart: ${session.name}",
            session.name.startsWith("Block A · ")
        )
        assertEquals(
            "the plan should not have been duplicated",
            1,
            tracks.observeTracksWithDue(nowProvider = flowOf(System.currentTimeMillis())).first().size
        )
    }

    @Test
    fun cancellingTheNameKeepsTheRecordingRunning() = runBlocking {
        val viewModel = viewModel()
        val sessionId = recordingSession()

        viewModel.finish()
        withTimeout(5_000) { viewModel.pendingTrackName.first { it != null } }

        viewModel.cancelFinish()

        assertNull(viewModel.pendingTrackName.value)
        val session = recordings.getSession(sessionId)!!
        assertNull("the session must still be open", session.endedAtEpochMs)
        assertEquals(RecordingStatus.RECORDING.name, session.status)
    }

    @Test
    fun aLineTooShortToBeATrackIsStillSavedAsARecording() = runBlocking {
        val viewModel = viewModel()
        val sessionId = recordings.startRecording(name = "Spray run")
        recordings.appendPoint(sessionId, line.first())
        TrackingState.begin(sessionId, System.currentTimeMillis())
        TrackingState.onAcceptedFix(pointCount = 1, distanceM = 0.0, accuracyM = 5f)

        viewModel.finish()
        withTimeout(5_000) { viewModel.pendingTrackName.first { it != null } }
        viewModel.confirmFinish("One point")

        val message = withTimeout(5_000) {
            viewModel.message.first { it?.contains("Recordings only") == true }
        }
        assertTrue("the message should be honest about the shortfall: $message", message!!.contains("Recordings only"))
        assertEquals(RecordingStatus.FINISHED.name, recordings.getSession(sessionId)!!.status)
        assertTrue(
            "no track should have been created",
            tracks.observeTracksWithDue(nowProvider = flowOf(System.currentTimeMillis())).first().isEmpty()
        )
    }
}
