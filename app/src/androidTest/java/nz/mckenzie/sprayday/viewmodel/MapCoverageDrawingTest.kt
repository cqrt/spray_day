package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LNG_AT_EQUATOR
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.tracking.LocationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * What the map draws after a track is sprayed: the whole path, from a recorded pass in the
 * database to the GeoJSON MapLibre is handed.
 *
 * The report this comes from: spray half a track, save, and the whole line on the map was
 * green - the half still waiting for a tank looked done. Nothing below is mocked, because
 * what went wrong was the join rather than any one piece of it.
 */
@RunWith(AndroidJUnit4::class)
class MapCoverageDrawingTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var assetRepository: AssetRepository
    private lateinit var sprays: SprayRepository
    private lateinit var recordings: RecordingRepository

    /** The due clock, held still: these tests are about colours, not about midnight. */
    private val dueNow = MutableStateFlow(System.currentTimeMillis())

    /** A 1 km line east, which is long enough to be cut in half. */
    private val plannedLine = listOf(
        GeoPoint(-41.5, 173.9),
        GeoPoint(-41.5, 173.9 + 1_000.0 / METRES_PER_DEG_LNG_AT_EQUATOR)
    )

    /** Fixes along that line, from [fromM] for [lengthM], one every ten metres. */
    private fun driveAlong(lengthM: Double, fromM: Double = 0.0): List<GeoPoint> {
        val fixes = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled <= lengthM) {
            val at = fromM + travelled
            fixes += GeoPoint(-41.5, 173.9 + at / METRES_PER_DEG_LNG_AT_EQUATOR)
            travelled += 10.0
        }
        return fixes
    }

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
        // No db.close(): a view model still observing a table would crash the process, which
        // is a lesson this suite has learned once already.
    }

    private fun viewModel() = MapViewModel(
        assetRepository = assetRepository,
        settingsRepository = SettingsRepository(context),
        locationSource = NoLocation,
        dueNow = dueNow
    )

    /** The colours in the drawn GeoJSON, in the order they are written. */
    private fun colours(geoJson: String): List<String> =
        Regex("\"stroke\":\"(#[0-9A-Fa-f]{6})\"")
            .findAll(geoJson)
            .map { it.groupValues[1] }
            .toList()

    /**
     * How long each drawn line is, in metres, read back out of the GeoJSON.
     *
     * The colours say which stretch is which; this says that the cut between them is where the
     * recording stopped rather than somewhere else on the line.
     *
     * One feature at a time, cut at the end of its geometry, rather than by finding the end of
     * the coordinate list: the list is closed by two brackets in a row, and a cut on those eats
     * the last point's bracket - which reads as a line of one point, and a length of zero.
     */
    private fun drawnLengthsM(geoJson: String): List<Double> =
        geoJson.split("\"type\":\"Feature\",").drop(1).map { feature ->
            val geometry = feature.substringBefore("}}")
            val points = Regex("\\[(-?[\\d.]+),(-?[\\d.]+)]").findAll(geometry)
                .map { GeoPoint(lat = it.groupValues[2].toDouble(), lng = it.groupValues[1].toDouble()) }
                .toList()
            assertTrue("a drawn line of ${points.size} point(s): $geometry", points.size >= 2)
            polylineLengthMeters(points)
        }

    /** Waits for the map to draw exactly these colours, or fails the test. */
    private suspend fun awaitColours(viewModel: MapViewModel, expected: List<String>) {
        withTimeout(5_000) {
            viewModel.assetGeoJson.first { colours(it) == expected }
        }
    }

    /** Records a pass over part of a line, and the spray that goes with it. */
    private suspend fun sprayAlong(assetId: Long, lengthM: Double, fromM: Double = 0.0) {
        val sessionId = recordings.startRecording("Spray run", assetId = assetId)
        driveAlong(lengthM, fromM).forEach { recordings.appendPoint(sessionId, it) }
        recordings.finishRecording(sessionId, distanceM = lengthM)
        sprays.recordSpray(assetId = assetId, recordedSessionId = sessionId)
    }

    @Test
    fun halfASprayedTrackIsDrawnHalfDoneAndHalfStillToSpray() = runBlocking {
        val assetId = assetRepository.createAsset("Half done", plannedLine)
        sprayAlong(assetId, lengthM = 500.0)

        val viewModel = viewModel()
        awaitColours(viewModel, listOf(AssetColors.GREEN, AssetColors.RED))

        assertEquals(
            "both parts are the one asset, so a tap on either opens it",
            2,
            Regex("\"id\":$assetId").findAll(viewModel.assetGeoJson.value).count()
        )

        val drawn = drawnLengthsM(viewModel.assetGeoJson.value)
        val planned = polylineLengthMeters(plannedLine)
        assertEquals("a driven half and a half that was not, drawn $drawn", 2, drawn.size)
        assertEquals(
            "the cut is at the middle of the line, give or take the tolerance that counts a pass " +
                "as having covered the ground under it",
            0.5,
            drawn[0] / planned,
            0.05
        )
        assertEquals(
            "and the two pieces add up to the line, with nothing drawn twice or left out",
            planned,
            drawn.sum(),
            5.0
        )
    }

    @Test
    fun aTrackSprayedWithNoRecordingIsStillDrawnInOneColour() = runBlocking {
        val assetId = assetRepository.createAsset("Done by hand", plannedLine)
        sprays.recordSpray(assetId = assetId)

        awaitColours(viewModel(), listOf(AssetColors.GREEN))
    }

    @Test
    fun theSecondHalfDoneTheNextDayLeavesOneSprayedLine() = runBlocking {
        val assetId = assetRepository.createAsset("In two bites", plannedLine)
        sprayAlong(assetId, lengthM = 500.0)
        sprayAlong(assetId, lengthM = 500.0, fromM = 500.0)

        awaitColours(viewModel(), listOf(AssetColors.GREEN))
    }

    @Test
    fun aSprayJustRecordedChangesWhatTheMapDraws() = runBlocking {
        val assetId = assetRepository.createAsset("Half then the rest", plannedLine)
        val viewModel = viewModel()
        awaitColours(viewModel, listOf(AssetColors.RED))

        // The operator sprays half the line and saves: the report, in four lines.
        sprayAlong(assetId, lengthM = 500.0)

        awaitColours(viewModel, listOf(AssetColors.GREEN, AssetColors.RED))
    }

    /** The map tests are about tracks, not about where the phone is. */
    private object NoLocation : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()
        override suspend fun currentLocation(): GeoPoint? = null
    }
}
