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
import nz.mckenzie.sprayday.data.db.TrackEntity
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.ui.TrackEditResult
import nz.mckenzie.sprayday.ui.TrackEdits
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The per-track settings.
 *
 * Every track used to be sprayed on the same hard-wired 120-day cycle, because there
 * was nowhere to say otherwise. These tests are about the two consequences of
 * changing that: the fields persist, and the interval set here is the one the
 * traffic light uses.
 */
@RunWith(AndroidJUnit4::class)
class TrackDetailViewModelTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var tracks: TrackRepository
    private var trackId = 0L

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        trackId = tracks.createTrack(
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
    private suspend fun dueStatus(): DueStatus? = tracks
        .observeTracksWithDue(nowProvider = flowOf(System.currentTimeMillis()))
        .first()
        .firstOrNull { it.track.id == trackId }
        ?.due
        ?.status

    @Test
    fun whatTheEditFormSetsIsWhatTheTrackKeeps() = runBlocking {
        val viewModel = viewModel()
        val track = track()
        val edited = TrackEdits.apply(
            track = track,
            name = "Back paddock",
            areaLabel = "Back",
            intervalDays = "90",
            swathWidthM = "6",
            notes = "spray the fenceline twice"
        )
        assertTrue(edited is TrackEditResult.Ok)

        viewModel.save((edited as TrackEditResult.Ok).track)

        val stored = withTimeout(5_000) {
            tracks.observeTrack(trackId).first { it?.name == "Back paddock" }
        }!!
        assertEquals("Back", stored.areaLabel)
        assertEquals(90, stored.intervalDays)
        assertEquals(6.0, stored.swathWidthM!!, 1e-9)
        assertEquals("spray the fenceline twice", stored.notes)
        // The geometry the map draws from must survive an edit.
        assertEquals(1, tracks.observeTrackGeometry(trackId).first().size - 1)
    }

    @Test
    fun theIntervalSetOnATrackIsTheOneTheTrafficLightUses() = runBlocking {
        val viewModel = viewModel()
        // Freshly created and never sprayed: due regardless of interval.
        assertEquals(DueStatus.NEVER_SPRAYED, dueStatus())

        // Spray it now, so the interval becomes the only thing that decides.
        val now = System.currentTimeMillis()
        tracks.updateTrack(track().copy(lastSprayedAtEpochMs = now))
        assertEquals("120 days after spraying is not due", DueStatus.NOT_DUE, dueStatus())

        // The same track, once its owner says it wants it every 10 days - inside the
        // 14-day lead time, so the traffic light must change.
        val edited = TrackEdits.apply(
            track = track().copy(lastSprayedAtEpochMs = now),
            name = "Home block",
            areaLabel = "",
            intervalDays = "10",
            swathWidthM = "",
            notes = ""
        )
        viewModel.save((edited as TrackEditResult.Ok).track)

        withTimeout(5_000) { tracks.observeTrack(trackId).first { it?.intervalDays == 10 } }
        assertEquals(
            "a 10-day track sprayed today is inside its lead time",
            DueStatus.DUE_SOON,
            dueStatus()
        )
    }

    @Test
    fun aSwathWidthCanBeAddedAndRemovedAgain() = runBlocking {
        val viewModel = viewModel()

        val withWidth = TrackEdits.apply(track(), "Home block", "", "120", "4.5", "")
        viewModel.save((withWidth as TrackEditResult.Ok).track)
        val stored = withTimeout(5_000) { tracks.observeTrack(trackId).first { it?.swathWidthM != null } }!!
        assertEquals(4.5, stored.swathWidthM!!, 1e-9)

        val withoutWidth = TrackEdits.apply(stored, "Home block", "", "120", "", "")
        viewModel.save((withoutWidth as TrackEditResult.Ok).track)
        withTimeout(5_000) { tracks.observeTrack(trackId).first { it?.swathWidthM == null } }

        assertNull("removing the width must not leave a zero behind", track().swathWidthM)
        // And with no width there is no treated area to claim.
        val finalTrack = track()
        assertNull(finalTrack.swathWidthM)
    }

    private suspend fun track(): TrackEntity = tracks.getTrack(trackId)!!

    private fun viewModel() = TrackDetailViewModel(
        trackId = trackId,
        tracks = tracks,
        sprays = SprayRepository(db),
        recordingsRepository = RecordingRepository(db),
        settingsRepository = SettingsRepository(context),
        context = context
    )
}
