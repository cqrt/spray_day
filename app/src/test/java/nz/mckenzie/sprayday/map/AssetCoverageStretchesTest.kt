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
    private fun driveAlong(lengthM: Double, fromM: Double = 0.0, atMs: Long = 0L): List<GeoPoint> {
        val fixes = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled <= lengthM) {
            val at = fromM + travelled
            fixes += GeoPoint(
                lat = -41.5,
                lng = 173.9 + at / METRES_PER_DEG_LNG_AT_EQUATOR,
                timeMs = atMs + travelled.toLong()
            )
            travelled += 10.0
        }
        return fixes
    }

    /** The same line driven back the other way, from [fromM] towards the near end. */
    private fun driveBack(lengthM: Double, fromM: Double, atMs: Long = 0L): List<GeoPoint> {
        val fixes = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled <= lengthM) {
            val at = fromM - travelled
            fixes += GeoPoint(
                lat = -41.5,
                lng = 173.9 + at / METRES_PER_DEG_LNG_AT_EQUATOR,
                timeMs = atMs + travelled.toLong()
            )
            travelled += 10.0
        }
        return fixes
    }

    private fun colours(
        passes: List<RecordedPass>,
        lastWithoutRecordingAtEpochMs: Long? = null,
        planned: List<GeoPoint> = lineEast(),
        passesRequired: Int = 1,
        separationM: Double? = null
    ): List<String> = AssetCoverageStretches.of(
        planned = listOf(planned),
        passes = passes,
        lastWithoutRecordingAtEpochMs = lastWithoutRecordingAtEpochMs,
        intervalDays = intervalDays,
        leadDays = leadDays,
        nowEpochMs = now,
        zoneId = zone,
        passesRequired = passesRequired,
        separationM = separationM
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

    @Test
    fun `a line that takes two passes stays red when it has been walked once`() {
        // The report this whole feature came from: a track walked up one side and back down the
        // other went green after the first side, and the traffic light went out for four months.
        assertEquals(
            "one pass is not half sprayed, it is not sprayed",
            listOf(AssetColors.RED),
            colours(
                passes = listOf(RecordedPass(now, driveAlong(1_000.0, atMs = daysAgo(0)))),
                passesRequired = 2
            )
        )
    }

    @Test
    fun `a line that takes two passes goes green when it has been walked both ways`() {
        val up = RecordedPass(now, driveAlong(1_000.0, atMs = now - 60_000L))
        val back = RecordedPass(
            atEpochMs = now,
            points = driveBack(lengthM = 1_000.0, fromM = 1_000.0, atMs = now - 30_000L)
        )

        assertEquals(
            "one colour, because every metre of it was done by the same pass",
            listOf(AssetColors.GREEN),
            colours(passes = listOf(up, back), passesRequired = 2).distinct()
        )
    }

    @Test
    fun `a two-pass line walked once before is due again on its old date`() {
        // An old job, then one pass today: the line is not done today, so its colour is still the
        // old job's - which is what makes the second side worth going back for.
        assertEquals(
            "sprayed 130 days ago and walked once since: due again",
            listOf(AssetColors.RED),
            colours(
                passes = listOf(
                    RecordedPass(daysAgo(130), driveAlong(1_000.0, atMs = daysAgo(130))),
                    RecordedPass(
                        atEpochMs = daysAgo(130) + 1_000L,
                        points = driveBack(1_000.0, 1_000.0, daysAgo(130) + 1_000L)
                    ),
                    RecordedPass(now, driveAlong(1_000.0, atMs = now - 60_000L))
                ),
                passesRequired = 2
            ).distinct()
        )
    }
}
