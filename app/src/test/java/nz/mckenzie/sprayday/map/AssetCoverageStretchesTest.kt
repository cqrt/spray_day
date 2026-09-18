package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LNG_AT_EQUATOR
import nz.mckenzie.sprayday.domain.geo.RecordedPass
import java.time.Instant
import java.time.ZoneOffset
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The colours the map draws for a track that was only partly sprayed.
 *
 * This is the report these exist for: spraying half a track left the whole line green, so the
 * half still waiting for a tank looked done. What the map says about each part of the line is
 * decided here.
 */
class AssetCoverageStretchesTest {

    private val zone = ZoneOffset.UTC
    private val intervalDays = 120
    private val leadDays = 14

    /** A fixed "now", so that these tests read in days rather than in milliseconds. */
    private val now = Instant.parse("2026-09-17T09:00:00Z").toEpochMilli()

    private fun daysAgo(days: Long): Long = now - days * 24L * 60L * 60L * 1000L

    /** A straight 1 km line east, as the planned track. */
    private fun lineEast(lengthM: Double = 1_000.0) = listOf(
        GeoPoint(-41.5, 173.9),
        GeoPoint(-41.5, 173.9 + lengthM / METRES_PER_DEG_LNG_AT_EQUATOR)
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

    private fun colours(
        passes: List<RecordedPass>,
        lastWithoutRecordingAtEpochMs: Long? = null,
        planned: List<GeoPoint> = lineEast()
    ): List<String> = AssetCoverageStretches.of(
        planned = planned,
        passes = passes,
        lastWithoutRecordingAtEpochMs = lastWithoutRecordingAtEpochMs,
        intervalDays = intervalDays,
        leadDays = leadDays,
        nowEpochMs = now,
        zoneId = zone
    ).map { it.colorHex }

    @Test
    fun `half a line sprayed today is half green and half red`() {
        assertEquals(
            listOf(AssetColors.GREEN, AssetColors.RED),
            colours(passes = listOf(RecordedPass(now, driveAlong(500.0))))
        )
    }

    @Test
    fun `two halves on two days are both green`() {
        assertEquals(
            "a half sprayed a month ago is not due again yet, whatever the other half did",
            listOf(AssetColors.GREEN, AssetColors.GREEN),
            colours(
                passes = listOf(
                    RecordedPass(daysAgo(30), driveAlong(500.0)),
                    RecordedPass(now, driveAlong(500.0, fromM = 500.0))
                )
            )
        )
    }

    @Test
    fun `each part turns on its own schedule, not the line's`() {
        assertEquals(
            "the half sprayed 110 days ago is nearly due while the other half was done today",
            listOf(AssetColors.GREEN, AssetColors.YELLOW),
            colours(
                passes = listOf(
                    RecordedPass(now, driveAlong(500.0)),
                    RecordedPass(daysAgo(110), driveAlong(500.0, fromM = 500.0))
                )
            )
        )
    }

    @Test
    fun `a half sprayed long enough ago is red again`() {
        assertEquals(
            listOf(AssetColors.GREEN, AssetColors.RED),
            colours(
                passes = listOf(
                    RecordedPass(now, driveAlong(500.0)),
                    RecordedPass(daysAgo(130), driveAlong(500.0, fromM = 500.0))
                )
            )
        )
    }

    @Test
    fun `a spray logged by hand keeps the whole line in one colour`() {
        assertEquals(
            "without this, every track sprayed without a recording would turn red",
            listOf(AssetColors.GREEN),
            colours(passes = emptyList(), lastWithoutRecordingAtEpochMs = daysAgo(3))
        )
    }

    @Test
    fun `a hand spray does not hide a half the recording shows was missed`() {
        assertEquals(
            "the hand spray covers the whole line, but it is older than the pass that missed a bit",
            listOf(AssetColors.RED, AssetColors.GREEN),
            colours(
                passes = listOf(RecordedPass(now, driveAlong(500.0, fromM = 500.0))),
                lastWithoutRecordingAtEpochMs = daysAgo(130)
            )
        )
    }

    @Test
    fun `a line nothing has ever covered is one red stretch`() {
        assertEquals(listOf(AssetColors.RED), colours(passes = emptyList()))
    }
}
