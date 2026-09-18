package nz.mckenzie.sprayday.viewmodel

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import nz.mckenzie.sprayday.tracking.LocationSource

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.delay
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LNG_AT_EQUATOR
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.tracking.TrackingState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The record screen's finish flow.
 *
 * A regression lives here: recording a line used to save only a GPS session, so the
 * line never appeared on the Assets page and there was no way to spray it again.
 * On this screen "record" means "make a track", so finishing a new line asks for a
 * name and creates one - while spraying an existing track still just records
 * against it, since that track already has a name.
 */
@RunWith(AndroidJUnit4::class)
class RecordingViewModelTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var recordings: RecordingRepository
    private lateinit var assetRepository: AssetRepository

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
        assetRepository = AssetRepository(db)
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
        assetRepository = assetRepository,
        sprays = SprayRepository(db),
        settingsRepository = SettingsRepository(context),
        context = context,
        locationSource = NoFixLocation
    )

    /** A phone that offers no fix: this suite is about recording, not about framing. */
    private object NoFixLocation : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()

        override suspend fun currentLocation(): GeoPoint? = null
    }

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

    private suspend fun assetsNamed(name: String) = assetRepository
        .observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis()))
        .first()
        .filter { it.asset.name == name }

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

        val stored = assetsNamed("Paddock 3").single()
        assertEquals("the recording should point at the track it became", stored.asset.id, session.assetId)
        assertEquals(
            "the track should carry the recorded line",
            line.size,
            assetRepository.getAssetGeometry(stored.asset.id).size
        )
        assertTrue("its length should have been computed", stored.asset.lengthM > 0.0)
        assertEquals("a freshly recorded track has not been sprayed", DueStatus.NEVER_SPRAYED, stored.due.status)
    }

    @Test
    fun finishingAgainstAnExistingTrackDoesNotAskForAName() = runBlocking {
        val assetId = assetRepository.createAsset("Block A", line)
        val viewModel = viewModel()
        val sessionId = recordingSession()

        viewModel.selectTrack(assetId)
        withTimeout(5_000) { viewModel.selectedAssetId.first { it == assetId } }

        viewModel.finish()

        assertNull("an existing track already has a name", viewModel.pendingTrackName.value)
        val message = withTimeout(5_000) {
            viewModel.message.first { it?.startsWith("Saved") == true }
        }
        assertFalse(
            "no new track should have been created: $message",
            message!!.contains("on the Assets page")
        )
        val session = recordings.getSession(sessionId)!!
        assertEquals(RecordingStatus.FINISHED.name, session.status)
        assertEquals(assetId, session.assetId)
        assertTrue(
            "the recording should be named after the track and the date, so the three " +
                "passes a year are told apart: ${session.name}",
            session.name.startsWith("Block A · ")
        )
        assertEquals(
            "the plan should not have been duplicated",
            1,
            assetRepository.observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis())).first().size
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
            assetRepository.observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis())).first().isEmpty()
        )
    }

    /**
     * Fixes along a straight line running east, one every four metres, as far as [lengthM].
     *
     * Four metres because that is what the app records in the field: the filter wants three
     * metres of movement and a fix every few seconds, so a real track's points are a few
     * metres apart. Ten metres - a step and a half of the coverage maths - hid a fault that
     * only appears when a plan's segments are shorter than the step it is walked in.
     */
    private fun fixesAlong(lengthM: Double): List<GeoPoint> = buildList {
        var travelled = 0.0
        while (travelled <= lengthM) {
            add(GeoPoint(-41.5, 173.9 + travelled / METRES_PER_DEG_LNG_AT_EQUATOR))
            travelled += 4.0
        }
    }

    /** A fix as the map's GeoJSON writes it, so a test can say which line the map is showing. */
    private fun drawn(point: GeoPoint): String =
        String.format(java.util.Locale.US, "%.7f", point.lng)

    /**
     * The map's line drawn in red: the part of the track still to spray, or the recording
     * itself when no track is chosen and there is nothing to compare it with.
     */
    private fun redPart(geoJson: String): String =
        if (geoJson.contains(AssetColors.RED)) geoJson.substringAfter(AssetColors.RED) else ""

    /**
     * The map's line drawn in green: the part of the track this pass has sprayed, which is the
     * recording as it landed on the plan.
     *
     * The stretches are written in the plan's own order, and every test here walks from the
     * track's start, so the green stretch is the first one and runs up to the red.
     */
    private fun greenPart(geoJson: String): String =
        if (geoJson.contains(AssetColors.GREEN)) {
            geoJson.substringAfter(AssetColors.GREEN).substringBefore(AssetColors.RED)
        } else {
            ""
        }

    /**
     * The state the spraying was reported from: a track made from a finished recording, and a
     * second recording walking the same line - half of it so far.
     *
     * Returns the track's id, the session being walked, and the fixes walked so far. The screen
     * follows the session, and both the start button and the reattach on launch come down to one
     * `TrackingState.begin` call, so this drives that directly: no GPS and no foreground service.
     */
    private suspend fun trackMadeAndPassUnderWay(): Triple<Long, Long, List<GeoPoint>> {
        val madeIt = recordings.startRecording(name = "Track 18 Sep")
        val whole = fixesAlong(500.0)
        whole.forEach { recordings.appendPoint(madeIt, it) }
        recordings.finishRecording(madeIt, distanceM = polylineLengthMeters(whole))
        val assetId = assetRepository.createAsset("Track 18 Sep", whole)

        val walking = recordings.startRecording(name = "Spray run", assetId = assetId)
        TrackingState.begin(walking, System.currentTimeMillis())
        return Triple(assetId, walking, fixesAlong(250.0))
    }

    /**
     * The report from the field: half the track walked, and the screen said the whole line was
     * covered. The recording that *made* the track covers all of it, so a screen that keeps
     * following that one reads 100% the moment anything brings it back.
     */
    @Test
    fun aHalfWalkedPassReadsAsHalfTheLineNotAsTheWholeOfIt() = runBlocking {
        val viewModel = viewModel()
        val (assetId, walking, walked) = trackMadeAndPassUnderWay()
        viewModel.selectTrack(assetId)
        withTimeout(5_000) { viewModel.selectedAssetId.first { it == assetId } }

        // The walk, as the service writes it: one fix at a time.
        walked.forEach { recordings.appendPoint(walking, it) }

        // The screen has read every fix when the sprayed part reaches the last of them.
        val map = withTimeout(5_000) {
            viewModel.recordedGeoJson.first { greenPart(it).contains(drawn(walked.last())) }
        }
        assertTrue(
            "half the track drawn as sprayed and half as still to do, not one colour: $map",
            map.contains(AssetColors.GREEN) && map.contains(AssetColors.RED)
        )
        assertFalse(
            "the sprayed part stops where the walk stopped, and the rest of the track " +
                "stays to do: $map",
            greenPart(map).contains(drawn(fixesAlong(500.0).last()))
        )

        // The number is debounced by three quarters of a second, so give it the beat it asks for.
        delay(1_500)
        val covered = viewModel.coverage.value
        assertEquals(
            "half the line walked is half the line covered; the finished recording that made " +
                "the track covers all of it, and measuring against that one gave $covered",
            0.5,
            covered!!,
            0.08
        )
    }

    /**
     * The other half of the same fault: the fixes that keep arriving for a recording that has
     * been finished used to be shown, because the screen was still listening to that session.
     */
    @Test
    fun aRetiredRecordingCannotPutItsLineBackOnTheScreen() = runBlocking {
        val viewModel = viewModel()
        val (assetId, walking, walked) = trackMadeAndPassUnderWay()
        viewModel.selectTrack(assetId)
        withTimeout(5_000) { viewModel.selectedAssetId.first { it == assetId } }
        walked.forEach { recordings.appendPoint(walking, it) }
        withTimeout(5_000) {
            viewModel.recordedGeoJson.first { greenPart(it).contains(drawn(walked.last())) }
        }

        // That pass is finished and named, and the next one starts from the same line.
        recordings.finishRecording(walking, distanceM = polylineLengthMeters(walked))
        val next = recordings.startRecording(name = "Spray run", assetId = assetId)
        TrackingState.begin(next, System.currentTimeMillis())
        val justStarted = fixesAlong(30.0)
        justStarted.forEach { recordings.appendPoint(next, it) }

        // Writing to the retired session is what used to fetch its whole line back: every insert
        // into that table wakes the query that reads it, whichever session it belongs to.
        recordings.appendPoint(walking, fixesAlong(500.0)[30])
        withTimeout(5_000) {
            viewModel.recordedGeoJson.first { greenPart(it).contains(drawn(justStarted.last())) }
        }
        delay(1_500)

        val map = viewModel.recordedGeoJson.value
        assertFalse(
            "the retired pass must not be drawn as sprayed again: $map",
            greenPart(map).contains(drawn(walked.last()))
        )
        val covered = viewModel.coverage.value
        assertTrue(
            "and the number is the new pass's 30 m of 500, not the retired pass's whole line: " +
                "$covered",
            covered != null && covered < 0.25
        )
    }

    /**
     * The record screen has two numbers about one pass - the distance and points it has
     * recorded, and the coverage measured from those same fixes. A screen that is
     * re-created mid-spray (a configuration change, the system discarding it, or the app
     * being restarted) used to come back with the counters at zero while the fixes, and
     * so the coverage, carried on: the operator read "0 m" beside "100%", with no way to
     * tell that the coverage was the honest of the two.
     */
    @Test
    fun aScreenThatComesBackToARecordingKeepsItsProgress() = runBlocking {
        // The service's session, paused with fixes in the database and nothing in memory,
        // which is exactly what a re-created screen finds.
        val sessionId = recordings.startRecording(name = "Spray run")
        line.forEach { recordings.appendPoint(sessionId, it) }
        recordings.setStatus(sessionId, RecordingStatus.PAUSED)
        TrackingState.clear()

        val viewModel = viewModel()

        val state = withTimeout(5_000) { viewModel.tracking.first { it.sessionId == sessionId } }
        assertEquals("the points it already has", line.size, state.pointCount)
        assertEquals(
            "and the distance they come to, so the readout and the coverage agree",
            polylineLengthMeters(line),
            state.distanceM,
            1.0
        )
    }

    /**
     * With no track chosen there is nothing to compare the recording with, so the recording
     * itself is drawn: that is the "record a new line" case, where the line being made is the
     * only thing there is to see.
     */
    @Test
    fun aRecordingWithNoTrackChosenIsDrawnAsTheLineItIs() = runBlocking {
        val viewModel = viewModel()
        val sessionId = recordings.startRecording(name = "Spray run")
        TrackingState.begin(sessionId, System.currentTimeMillis())
        val walked = fixesAlong(40.0)

        walked.forEach { recordings.appendPoint(sessionId, it) }

        val map = withTimeout(5_000) {
            viewModel.recordedGeoJson.first { redPart(it).contains(drawn(walked.last())) }
        }
        assertFalse("there is no track to colour in: $map", map.contains(AssetColors.GREEN))
    }

    /**
     * Following the phone while a pass is being driven, and giving way when the operator takes
     * the map back.
     *
     * The map tab is deliberately *not* a map that follows: it is for reading work, and a
     * camera that keeps swinging back to the phone is one you cannot pan across a block. On
     * this screen the opposite is true - the operator is driving the line the map is showing,
     * one-handed - so a fresh pass turns following on, and the two things that turn it off are
     * the operator's own: a drag, or the control.
     */
    @Test
    fun aPassBeingDrivenFollowsThePhoneUntilTheMapIsDragged() = runBlocking {
        // The session is closed before the view model exists, so its init has nothing to
        // reattach to and cannot put the recording status back under this test's feet: what is
        // being tested is the map, not the reattach.
        val sessionId = recordings.startRecording(name = "Spray run")
        recordings.finishRecording(sessionId, distanceM = 0.0)
        val viewModel = viewModel()

        TrackingState.begin(sessionId, System.currentTimeMillis())
        awaitFollow("a session beginning", expected = true, viewModel = viewModel)

        TrackingState.setStatus(RecordingStatus.PAUSED)
        awaitFollow("paused, with both hands free and the map the operator's to read",
            expected = false, viewModel = viewModel)

        TrackingState.setStatus(RecordingStatus.RECORDING)
        awaitFollow("carrying on is a pass being driven again",
            expected = true, viewModel = viewModel)

        viewModel.onMapPanned()
        awaitFollow("a drag is the operator taking the map back", expected = false, viewModel = viewModel)
        assertFalse(viewModel.following.value)

        viewModel.setFollowing(true)
        awaitFollow("the control is how they ask for it again", expected = true, viewModel = viewModel)
    }

    /**
     * Waits for the map to be following the phone, or not, and names the stage that did not
     * happen: a bare timeout says nothing about which of these five the map disagreed with.
     */
    private suspend fun awaitFollow(
        stage: String,
        expected: Boolean,
        viewModel: RecordingViewModel
    ) {
        val reached = runCatching {
            withTimeout(5_000) { viewModel.followPhone.first { it == expected } }
        }
        assertTrue(
            "$stage: the map should ${if (expected) "follow" else "not follow"} the phone, " +
                "but the operator asked for ${viewModel.following.value} and the map was last " +
                "told ${viewModel.followPhone.value}",
            reached.isSuccess
        )
    }

    /**
     * The map and the numbers a moment after Save.
     *
     * Pressing Finish used to wipe the screen it was pressed on - the line out of the drawing,
     * the distance and points back to zero, the coverage and the track's length gone - so the
     * operator was left reading a sentence about a pass they could no longer see. "Just
     * aesthetics", as it was reported, but the pass is the thing they came to the screen to
     * do, and it should be the thing they are looking at when it is done.
     */
    @Test
    fun finishingAPassLeavesItOnTheScreenWithItsNumbers() = runBlocking {
        val viewModel = viewModel()
        val (assetId, walking, walked) = trackMadeAndPassUnderWay()
        viewModel.selectTrack(assetId)
        withTimeout(5_000) { viewModel.selectedAssetId.first { it == assetId } }

        walked.forEach { recordings.appendPoint(walking, it) }
        TrackingState.onAcceptedFix(pointCount = walked.size, distanceM = 250.0, accuracyM = 4f)
        withTimeout(5_000) {
            viewModel.recordedGeoJson.first { greenPart(it).contains(drawn(walked.last())) }
        }
        // The coverage is debounced by three quarters of a second; give it the beat it asks for
        // so that what is saved is what the screen was showing.
        delay(1_500)

        viewModel.finish()
        val saved = withTimeout(5_000) { viewModel.finished.first { it != null } }!!

        // The live session is closed by the time the pass is saved, so let the drawing settle on
        // the one that replaced it.
        delay(500)
        val drawnAfterSave = viewModel.recordedGeoJson.value
        assertTrue(
            "the line stays where the pass left it: $drawnAfterSave",
            drawnAfterSave.contains(AssetColors.GREEN) && drawnAfterSave.contains(AssetColors.RED)
        )
        assertTrue(
            "with the sprayed part still the part that was driven: $drawnAfterSave",
            greenPart(drawnAfterSave).contains(drawn(walked.last()))
        )

        val numbers = withTimeout(5_000) { viewModel.summary.first { it.pointCount == walked.size } }
        assertEquals("the distance the pass came to", 250.0, numbers.distanceM, 0.001)
        assertEquals("its own accuracy", 4f, numbers.accuracyM!!, 0.001f)
        assertEquals("and it is finished, not running", RecordingStatus.FINISHED, numbers.status)
        assertNotNull("with a stop time, so its clock has stopped", numbers.endedAtEpochMs)

        assertEquals(
            "the coverage stays on the screen",
            0.5,
            withTimeout(5_000) { viewModel.coverage.first { it != null } }!!,
            0.08
        )
        assertEquals(
            "and so does the track's length: the pair of numbers is what says whether the " +
                "job was done",
            polylineLengthMeters(fixesAlong(500.0)),
            withTimeout(5_000) { viewModel.trackLengthM.first { it != null } }!!,
            1.0
        )
        assertEquals(
            "and the name it was driven under, so the sentence above it still names the track",
            "Track 18 Sep",
            saved.trackName
        )
    }

    /** A saved pass is the last thing that happened, not a permanent fixture of the screen. */
    @Test
    fun choosingAnotherTrackPutsTheSavedPassAway() = runBlocking {
        val viewModel = viewModel()
        val (assetId, walking, walked) = trackMadeAndPassUnderWay()
        viewModel.selectTrack(assetId)
        withTimeout(5_000) { viewModel.selectedAssetId.first { it == assetId } }
        walked.forEach { recordings.appendPoint(walking, it) }
        viewModel.finish()
        withTimeout(5_000) { viewModel.finished.first { it != null } }

        viewModel.selectTrack(assetRepository.createAsset("Block B", line))

        assertNull(
            "a new job means the saved pass is finished with",
            withTimeout(5_000) { viewModel.finished.first { it == null } }
        )
    }

    /**
     * The report from the field: after Save, the card kept the last pass's distance, its points
     * and the sentence about saving it for as long as the app lived - through a tab away and back,
     * and left overnight - so a card that was ready to start the next job read as though the last
     * one were still on. Killing the app was the only way to clear it.
     *
     * Leaving the recorder is what finishes with that pass: it is on the screen while the operator
     * is there to see it, and the next visit opens ready to record.
     */
    @Test
    fun leavingTheRecorderPutsTheSavedPassAway() = runBlocking {
        val viewModel = viewModel()
        val (assetId, walking, walked) = trackMadeAndPassUnderWay()
        viewModel.selectTrack(assetId)
        withTimeout(5_000) { viewModel.selectedAssetId.first { it == assetId } }
        walked.forEach { recordings.appendPoint(walking, it) }
        viewModel.finish()
        withTimeout(5_000) { viewModel.finished.first { it != null } }
        withTimeout(5_000) { viewModel.message.first { it?.startsWith("Saved") == true } }

        viewModel.onScreenLeft()

        assertNull(
            "the pass belongs to the visit, not to the screen",
            withTimeout(5_000) { viewModel.finished.first { it == null } }
        )
        val ready = withTimeout(5_000) { viewModel.summary.first { it.pointCount == 0 } }
        assertNull("so the card is ready to record, not still reading the last one: $ready", ready.status)
        assertEquals("with its clock back to the start", 0.0, ready.distanceM, 0.001)

        assertNull(
            "and the sentence about saving it does not survive the screen either",
            withTimeout(5_000) { viewModel.message.first { it == null } }
        )
        assertNull(
            "nor the coverage, which was the saved pass's number",
            withTimeout(5_000) { viewModel.coverage.first { it == null } }
        )
        assertNull(
            "nor the length of the track it was driven against",
            withTimeout(5_000) { viewModel.trackLengthM.first { it == null } }
        )
        // Watched the way the screen watches it, so "nothing is drawn" is the map's own answer
        // rather than the initial value of a flow nobody is collecting.
        val empty = withTimeout(5_000) {
            viewModel.recordedGeoJson.first { !it.contains(drawn(walked.last())) }
        }
        assertFalse(
            "and none of that pass is left in the drawing: $empty",
            empty.contains(AssetColors.GREEN)
        )
    }

    /**
     * Leaving the screen mid-spray is not leaving the pass, which is the other half of what
     * [RecordingViewModel.onScreenLeft] has to get right: the service owns the recording and the
     * database has its fixes, so a tab away loses nothing - and the message on the card belongs to
     * the pass that is still on rather than being a leftover to be cleared.
     */
    @Test
    fun leavingTheRecorderMidSprayKeepsThePassAndItsMessage() = runBlocking {
        val viewModel = viewModel()
        val sessionId = recordingSession()
        withTimeout(5_000) { viewModel.tracking.first { it.sessionId == sessionId } }
        viewModel.onPermissionDenied()
        val told = viewModel.message.value
        assertNotNull("the card has something on it to lose", told)

        viewModel.onScreenLeft()

        assertEquals(
            "the pass is still the one in hand",
            sessionId,
            viewModel.tracking.value.sessionId
        )
        assertNull("and nothing has been saved", viewModel.finished.value)
        assertEquals("with the card still saying what it was saying", told, viewModel.message.value)
        assertNull("and the session still open in the database", recordings.getSession(sessionId)!!.endedAtEpochMs)
    }
}
