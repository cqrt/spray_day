package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Coverage maths, which decides whether the app tells an operator they covered a
 * track. Wrong here means either "you missed a bit" when they did not, or worse,
 * a false all-clear.
 *
 * Everything is built around the equator, where 0.001 degrees of longitude is
 * 111.3 m and of latitude 111.1 m, so the expected lengths are easy to reason
 * about.
 */
class CoverageTest {

    private val tolerance = Coverage.DEFAULT_TOLERANCE_M

    /** A straight line east from (0,0), [lengthM] long, as a two-point polyline. */
    private fun lineEast(lengthM: Double, fromLng: Double = 0.0, lat: Double = 0.0) =
        listOf(
            GeoPoint(lat, fromLng),
            GeoPoint(lat, fromLng + lengthM / METRES_PER_DEG_LNG_AT_EQUATOR)
        )

    /** Recorded fixes along the same line, one every [stepM] metres. */
    private fun driveAlong(
        lengthM: Double,
        fromLng: Double = 0.0,
        lat: Double = 0.0,
        stepM: Double = 10.0
    ): List<GeoPoint> {
        val points = mutableListOf<GeoPoint>()
        var travelled = 0.0
        while (travelled <= lengthM) {
            points += GeoPoint(lat, fromLng + travelled / METRES_PER_DEG_LNG_AT_EQUATOR)
            travelled += stepM
        }
        return points
    }

    @Test
    fun `driving the whole line covers all of it`() {
        val planned = lineEast(1_000.0)

        val covered = Coverage.coveredFraction(planned, driveAlong(1_000.0), tolerance)

        assertTrue("expected a full pass, got $covered", covered > 0.99)
        assertEquals(1.0, covered, 0.01)
    }

    /**
     * A recorded fix [metres] along the line, [atMs] after the pass started, [northM] off it.
     *
     * Times matter now that a pause is told apart from a dropped signal by when it happened, so
     * the tests that care about that build their passes out of these rather than [driveAlong].
     */
    private fun fixAt(metres: Double, atMs: Long, northM: Double = 0.0) = GeoPoint(
        lat = northM / METRES_PER_DEG_LAT,
        lng = metres / METRES_PER_DEG_LNG_AT_EQUATOR,
        timeMs = atMs
    )

    /**
     * A fix every fifty metres - a pass at 70 km/h, or a phone that only gets a fix now and
     * then - over a line the whole of which was driven.
     *
     * Every metre of the ground between two fixes was driven and sprayed by the machine that
     * crossed it, and none of it counted: the metres between the fixes read as unsprayed, and a
     * pass down the whole line came back as a quarter of it. That is the report this fixes.
     */
    @Test
    fun `fixes every fifty metres still cover the line between them`() {
        val planned = lineEast(1_000.0)
        val recorded = (0..20).map { step -> fixAt(step * 50.0, atMs = step * 25_000L) }

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        assertEquals(1.0, covered, 0.01)
    }

    /**
     * A fix outage under trees: 200 m of the line with nothing in the recording at all.
     *
     * The pass carried on - there is no pause behind this - so the ground in between is ground
     * it drove. The line is drawn across the gap and the plan under it counts as sprayed, which
     * is the same thing the drawing says.
     */
    @Test
    fun `a gap in the fixes is ground the pass drove`() {
        val planned = lineEast(1_000.0)
        val recorded = listOf(
            fixAt(0.0, atMs = 0L),
            fixAt(400.0, atMs = 60_000L),
            fixAt(600.0, atMs = 180_000L),
            fixAt(1_000.0, atMs = 240_000L)
        )

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        assertEquals("the whole line, fixes or no fixes in the middle", 1.0, covered, 0.01)

        val stretches = Coverage.splitByCoverage(
            planned,
            listOf(RecordedPass(atEpochMs = 240_000L, points = recorded))
        )
        assertEquals("one stretch, sprayed the whole way", 1, stretches.size)
    }

    /** A jump too long to be one pass over one line, however far apart the fixes are in time. */
    @Test
    fun `a jump of twenty kilometres is not a pass over the line between`() {
        val planned = lineEast(1_200.0)
        // Left recording on the ute and back on the line two days later.
        val recorded = listOf(fixAt(0.0, atMs = 0L), fixAt(20_000.0, atMs = 172_800_000L))

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        assertEquals(
            "only the ground around the two fixes, as it was before any of this",
            0.02,
            covered,
            0.02
        )
    }

    @Test
    fun `driving half the line covers about half of it`() {
        val planned = lineEast(1_000.0)

        val covered = Coverage.coveredFraction(planned, driveAlong(500.0), tolerance)

        assertEquals(0.5, covered, 0.05)
    }

    /**
     * The pass that was stopped in the middle of it.
     *
     * Driven to 400 m, paused for a tank or a load, carried on from 800 m: the fixes on either
     * side of the stop, and the break the pause button left behind - which is the one gap in a
     * recording that is not ground the pass drove. The middle third is the part that was not
     * sprayed, and it is the part this has to keep saying was not.
     */
    @Test
    fun `a pause in the middle of a pass leaves the middle unsprayed`() {
        val planned = lineEast(1_200.0)
        val recorded = listOf(
            fixAt(0.0, atMs = 0L),
            fixAt(400.0, atMs = 60_000L),
            fixAt(800.0, atMs = 900_000L),
            fixAt(1_200.0, atMs = 960_000L)
        )
        val paused = listOf(RecordingBreak(fromEpochMs = 65_000L, toEpochMs = 890_000L))

        val covered = Coverage.coveredFraction(planned, recorded, tolerance, breaks = paused)

        // The two thirds driven, plus the tolerance reaching just past each end of a driven
        // stretch - roughly 824 m of 1,200 m, the same answer the two thirds always gave.
        assertEquals(0.69, covered, 0.03)

        val stretches = Coverage.splitByCoverage(
            planned,
            listOf(RecordedPass(atEpochMs = 960_000L, points = recorded, breaks = paused))
        )
        assertEquals("sprayed, the stop, sprayed again", 3, stretches.size)
        assertEquals(960_000L, stretches[0].lastSprayedAtEpochMs)
        assertNull("the ground the pass was stopped over is still to spray", stretches[1].lastSprayedAtEpochMs)
    }

    @Test
    fun `passing a few metres off the line still counts`() {
        val planned = lineEast(500.0)
        val alongside = driveAlong(500.0, lat = 5.0 / METRES_PER_DEG_LAT)

        val covered = Coverage.coveredFraction(planned, alongside, tolerance)

        assertTrue("a 5 m offset is within tolerance, got $covered", covered > 0.95)
    }

    @Test
    fun `a parallel pass well away from the line does not count`() {
        val planned = lineEast(500.0)
        val tooFar = driveAlong(500.0, lat = 40.0 / METRES_PER_DEG_LAT)

        val covered = Coverage.coveredFraction(planned, tooFar, tolerance)

        assertEquals(0.0, covered, 0.001)
    }

    @Test
    fun `crossing the line at one point is not coverage`() {
        val planned = lineEast(1_000.0)
        // A short north-south crossing of the midway point.
        val middayLng = 500.0 / METRES_PER_DEG_LNG_AT_EQUATOR
        val crossing = listOf(
            GeoPoint(-30.0 / METRES_PER_DEG_LAT, middayLng),
            GeoPoint(30.0 / METRES_PER_DEG_LAT, middayLng)
        )

        val covered = Coverage.coveredFraction(planned, crossing, tolerance)

        assertTrue("a crossing should cover only a little, got $covered", covered < 0.05)
    }

    @Test
    fun `a long section weighs more than a short one`() {
        // A 1 km leg not driven, then a 100 m leg driven: 100 of 1,100 metres.
        val planned = listOf(
            GeoPoint(0.0, 0.0),
            GeoPoint(0.0, 1_000.0 / METRES_PER_DEG_LNG_AT_EQUATOR),
            GeoPoint(0.0, 1_100.0 / METRES_PER_DEG_LNG_AT_EQUATOR)
        )
        val recorded = driveAlong(100.0, fromLng = 1_000.0 / METRES_PER_DEG_LNG_AT_EQUATOR)

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        assertEquals("length-weighted, not sample-weighted", 0.09, covered, 0.02)
    }

    @Test
    fun `nothing recorded covers nothing`() {
        val planned = lineEast(500.0)

        assertEquals(0.0, Coverage.coveredFraction(planned, emptyList(), tolerance), 1e-9)
        assertEquals(0.0, Coverage.coveredFraction(emptyList(), driveAlong(100.0), tolerance), 1e-9)
        assertEquals(0.0, Coverage.coveredFraction(listOf(GeoPoint(0.0, 0.0)), driveAlong(100.0)), 1e-9)
    }

    @Test
    fun `a tighter tolerance is harder to satisfy`() {
        val planned = lineEast(500.0)
        val slightlyOff = driveAlong(500.0, lat = 8.0 / METRES_PER_DEG_LAT)

        assertTrue(Coverage.coveredFraction(planned, slightlyOff, 12.0) > 0.95)
        assertEquals(0.0, Coverage.coveredFraction(planned, slightlyOff, 3.0), 0.001)
    }

    @Test
    fun `coverage is reported as a percentage`() {
        assertEquals("0%", formatCoveragePercent(0.0))
        assertEquals("94%", formatCoveragePercent(0.9412))
        assertEquals("100%", formatCoveragePercent(1.0))
    }

    // --- Which part of the line was covered ---------------------------------------------
    //
    // This is what the map draws: half a track green and half of it red is this, not the
    // percentage above. The dates matter as much as the geometry, because a stretch covered
    // a fortnight ago and one covered this morning are both "done".

    @Test
    fun `a line nothing has covered is one stretch with no date`() {
        val planned = lineEast(1_000.0)

        val stretches = Coverage.splitByCoverage(planned, emptyList())

        assertEquals(1, stretches.size)
        assertNull("nothing has sprayed it", stretches.single().lastSprayedAtEpochMs)
        assertEquals(
            "and the one stretch is the whole line",
            polylineLengthMeters(planned),
            stretches.single().lengthM,
            1e-6
        )
    }

    @Test
    fun `driving half the line leaves half of it still to spray`() {
        val planned = lineEast(1_000.0)
        val pass = RecordedPass(atEpochMs = 1_000L, points = driveAlong(500.0))

        val stretches = Coverage.splitByCoverage(planned, listOf(pass))

        assertEquals("a driven half and a half that was not", 2, stretches.size)
        assertEquals(500.0, stretches[0].lengthM, 20.0)
        assertEquals(500.0, stretches[1].lengthM, 20.0)
        assertEquals(1_000L, stretches[0].lastSprayedAtEpochMs)
        assertNull("the rest of the line was never driven", stretches[1].lastSprayedAtEpochMs)
    }

    @Test
    fun `the stretches tile the plan, meeting where they were cut`() {
        val planned = lineEast(1_000.0)
        val pass = RecordedPass(atEpochMs = 1_000L, points = driveAlong(500.0))

        val stretches = Coverage.splitByCoverage(planned, listOf(pass))

        assertEquals(
            "the pieces add up to the line",
            polylineLengthMeters(planned),
            stretches.sumOf { it.lengthM },
            1.0
        )
        assertEquals(
            "and they meet, with no gap and no overlap for a shortfall to hide in",
            stretches[0].points.last(),
            stretches[1].points.first()
        )
        assertEquals(
            "the first piece starts where the line starts",
            planned.first(),
            stretches[0].points.first()
        )
        assertEquals("and the last ends where it ends", planned.last(), stretches.last().points.last())
    }

    @Test
    fun `two halves sprayed on two days are both sprayed`() {
        val planned = lineEast(1_000.0)
        val monday = RecordedPass(atEpochMs = 1_000L, points = driveAlong(500.0))
        val tuesday = RecordedPass(
            atEpochMs = 2_000L,
            points = driveAlong(500.0, fromLng = 500.0 / METRES_PER_DEG_LNG_AT_EQUATOR)
        )

        val stretches = Coverage.splitByCoverage(planned, listOf(monday, tuesday))

        assertEquals(2, stretches.size)
        assertEquals("Monday's half keeps Monday", 1_000L, stretches[0].lastSprayedAtEpochMs)
        assertEquals("and Tuesday's keeps Tuesday", 2_000L, stretches[1].lastSprayedAtEpochMs)
    }

    @Test
    fun `where two passes cover the same ground the newer one is the date`() {
        val planned = lineEast(1_000.0)
        val wholeLine = RecordedPass(atEpochMs = 1_000L, points = driveAlong(1_000.0))
        val again = RecordedPass(atEpochMs = 2_000L, points = driveAlong(300.0))

        val stretches = Coverage.splitByCoverage(planned, listOf(wholeLine, again))

        assertEquals(2, stretches.size)
        assertEquals(2_000L, stretches[0].lastSprayedAtEpochMs)
        assertEquals("the part only the first pass reached", 1_000L, stretches[1].lastSprayedAtEpochMs)
        assertEquals(300.0, stretches[0].lengthM, 20.0)
    }

    @Test
    fun `a spray logged by hand covers the whole line`() {
        val stretches = Coverage.splitByCoverage(
            planned = lineEast(1_000.0),
            passes = emptyList(),
            assetSprayedAtEpochMs = 5_000L
        )

        assertEquals("a spray with no recording behind it is the whole line", 1, stretches.size)
        assertEquals(5_000L, stretches.single().lastSprayedAtEpochMs)
    }

    @Test
    fun `a hand spray newer than a pass takes the line over`() {
        val half = RecordedPass(atEpochMs = 1_000L, points = driveAlong(500.0))

        val stretches = Coverage.splitByCoverage(
            planned = lineEast(1_000.0),
            passes = listOf(half),
            assetSprayedAtEpochMs = 5_000L
        )

        assertEquals(1, stretches.size)
        assertEquals(5_000L, stretches.single().lastSprayedAtEpochMs)
    }

    @Test
    fun `a recorded line that never came near the plan covers none of it`() {
        val alongside = driveAlong(1_000.0, lat = 40.0 / METRES_PER_DEG_LAT)

        val stretches = Coverage.splitByCoverage(
            planned = lineEast(1_000.0),
            passes = listOf(RecordedPass(atEpochMs = 1_000L, points = alongside))
        )

        assertEquals(
            "the recording is the evidence, and it says the line was not driven",
            1,
            stretches.size
        )
        assertNull(stretches.single().lastSprayedAtEpochMs)
    }

    @Test
    fun `a pass with no fixes proves nothing`() {
        val stretches = Coverage.splitByCoverage(
            planned = lineEast(1_000.0),
            passes = listOf(RecordedPass(atEpochMs = 1_000L, points = emptyList()))
        )

        assertNull(stretches.single().lastSprayedAtEpochMs)
    }

    @Test
    fun `a stretch of a bent track is bent, because it follows the plan`() {
        val corner = GeoPoint(0.0, 500.0 / METRES_PER_DEG_LNG_AT_EQUATOR)
        val planned = listOf(
            GeoPoint(0.0, 0.0),
            corner,
            GeoPoint(500.0 / METRES_PER_DEG_LAT, corner.lng)
        )
        // The first leg driven: the corner, and the tolerance's worth past it.
        val pass = RecordedPass(atEpochMs = 1_000L, points = driveAlong(500.0))

        val stretches = Coverage.splitByCoverage(planned, listOf(pass))

        assertEquals(2, stretches.size)
        assertTrue(
            "the corner is inside the driven stretch rather than cut off in a straight line",
            stretches[0].points.contains(corner)
        )
    }

    /**
     * A jump that leaves the line: through a gate, 40 m off it, and back on.
     *
     * The straight line between the two fixes is what vouches for the ground in between, and
     * the tolerance is what says how far off the line that ground may be. A bridge that carried
     * the spray with it wherever it went would turn this into a sprayed line.
     */
    @Test
    fun `a jump off the line covers only the ground the bridge crosses`() {
        val planned = lineEast(1_200.0)
        val recorded = listOf(
            fixAt(0.0, atMs = 0L),
            fixAt(1_200.0, atMs = 60_000L, northM = 40.0)
        )

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        assertTrue("the line under the bridge is covered, got $covered", covered > 0.2)
        assertTrue("but most of the line is not, got $covered", covered < 0.6)
    }

    /** A break that never ended: the pass was finished while it was paused. */
    @Test
    fun `a pause that is still open still divides the pass`() {
        val planned = lineEast(1_200.0)
        val recorded = listOf(
            fixAt(0.0, atMs = 0L),
            fixAt(400.0, atMs = 60_000L),
            fixAt(800.0, atMs = 900_000L)
        )
        // Nothing after it: the operator stopped and saved the pass from where they were.
        val paused = listOf(RecordingBreak(fromEpochMs = 65_000L))

        val covered = Coverage.coveredFraction(planned, recorded, tolerance, breaks = paused)

        assertEquals("the 400 m before the stop, and nothing after it", 0.34, covered, 0.03)
    }

    /** A break that falls outside the pass altogether says nothing about it. */
    @Test
    fun `a pause with no fixes around it changes nothing`() {
        val planned = lineEast(600.0)
        val recorded = (0..6).map { step -> fixAt(step * 100.0, atMs = step * 10_000L) }
        val afterwards = listOf(RecordingBreak(fromEpochMs = 500_000L, toEpochMs = 600_000L))

        val covered = Coverage.coveredFraction(planned, recorded, tolerance, breaks = afterwards)

        assertEquals(1.0, covered, 0.01)
    }

    @Test
    fun `a plan with nowhere to walk has nothing to cut up`() {
        val nowhere = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.0))
        val pass = RecordedPass(atEpochMs = 1_000L, points = driveAlong(100.0))

        assertEquals(emptyList<CoverageStretch>(), Coverage.splitByCoverage(nowhere, listOf(pass)))
        assertEquals(
            emptyList<CoverageStretch>(),
            Coverage.splitByCoverage(listOf(GeoPoint(0.0, 0.0)), listOf(pass))
        )
        assertEquals(
            "and no tolerance to probe with is the same answer",
            emptyList<CoverageStretch>(),
            Coverage.splitByCoverage(lineEast(100.0), listOf(pass), toleranceM = 0.0)
        )
    }

    // --- The reported case -------------------------------------------------------------

    /**
     * A 160 m track made from 41 fixes, then a pass over its eastern 72 m only, at the
     * latitude it happened at. The screen said "Covered 100%" while the map drew the
     * western 88 m of the same track as still to do.
     *
     * Every case above is built on the equator out of two-point lines, which is exactly
     * the geometry this one is not.
     */
    @Test
    fun `a pass over part of a many-point track away from the equator is not a full pass`() {
        val lat = -41.5450
        val perDegLng = METRES_PER_DEG_LNG_AT_EQUATOR * Math.cos(Math.toRadians(lat))
        fun at(metres: Double) = GeoPoint(lat, 174.0020 + metres / perDegLng)

        // The track: 41 fixes 4 m apart, 160 m long - what a recorded line looks like.
        val planned = (0..40).map { step -> at(step * 4.0) }
        // The pass: 19 fixes over the eastern 72 m of it.
        val recorded = (0..18).map { step -> at(160.0 - step * 4.0) }

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        // Driven over the eastern 72 m, plus the tolerance reaching 12 m past where the
        // recording starts, over a 160 m line: about half of it, and no more.
        assertEquals(0.52, covered, 0.03)
    }

    @Test
    fun `a recorded track driven over half of it splits where the pass stops`() {
        val lat = -41.5450
        val perDegLng = METRES_PER_DEG_LNG_AT_EQUATOR * Math.cos(Math.toRadians(lat))
        fun at(metres: Double) = GeoPoint(lat, 174.0020 + metres / perDegLng)

        // The track as this app records one, and a pass over its western 72 m.
        val planned = (0..40).map { step -> at(step * 4.0) }
        val pass = RecordedPass(atEpochMs = 7L, points = (0..18).map { step -> at(step * 4.0) })

        val stretches = Coverage.splitByCoverage(planned, listOf(pass))

        assertEquals(
            "a driven part and a part still to spray, not one stretch for the whole line",
            2,
            stretches.size
        )
        assertEquals(
            "the driven part, drawn to where the tolerance reaches past where the pass stopped",
            84.0,
            stretches[0].lengthM,
            3.0
        )
        assertEquals(76.0, stretches[1].lengthM, 3.0)
        assertEquals(7L, stretches[0].lastSprayedAtEpochMs)
        assertNull(stretches[1].lastSprayedAtEpochMs)
    }

    // --- The recorded line, divided where the operator stopped -------------------------

    /** The recorded line is drawn in one piece until a pause divides it, and no further. */
    @Test
    fun `a pause divides the recorded line in two`() {
        val points = listOf(
            fixAt(0.0, atMs = 0L),
            fixAt(100.0, atMs = 10_000L),
            fixAt(200.0, atMs = 20_000L),
            fixAt(300.0, atMs = 30_000L)
        )

        val pieces = points.splitAtBreaks(listOf(RecordingBreak(fromEpochMs = 12_000L, toEpochMs = 18_000L)))

        assertEquals("one line either side of the stop", 2, pieces.size)
        assertEquals(points.take(2), pieces[0])
        assertEquals(points.drop(2), pieces[1])
        assertEquals(
            "and a pass that was never paused is one line",
            1,
            points.splitAtBreaks(emptyList()).size
        )
    }

    /**
     * Two fixes with the stop between them: dropping a piece of one fix each would lose the line
     * altogether, and a recording that cannot be seen is worse than one drawn in one piece.
     */
    @Test
    fun `a pass too short to divide is still drawn whole`() {
        val points = listOf(fixAt(0.0, atMs = 0L), fixAt(100.0, atMs = 10_000L))

        val paused = listOf(RecordingBreak(fromEpochMs = 5_000L, toEpochMs = 6_000L))

        val pieces = points.splitAtBreaks(paused)

        assertEquals(1, pieces.size)
        assertEquals(points, pieces.single())
    }

    /* ---- A track is a line and its side tracks ----------------------------------------- */

    @Test
    fun `how much of a track a pass covered is asked of the side tracks too`() {
        // A line 111 m long with a 111 m side track off its middle: 222 m of track, half of it the spur.
        val origin = GeoPoint(0.0, 0.0)
        val junction = GeoPoint(0.0, 0.0005)
        val east = GeoPoint(0.0, 0.001)
        val spurEnd = GeoPoint(0.001, 0.0005)
        val track = AssetGeometry.of(listOf(origin, junction, east), listOf(listOf(junction, spurEnd)))

        // Driving the line and never going up the spur is **not** the whole track, which is the answer
        // this exists to give. It is a little over half: the tolerance around the junction reaches a
        // dozen metres up the spur, and that dozen metres was driven.
        val alongTheLine = (0..10).map { step -> GeoPoint(0.0, step * 0.0001) }
        val lineDone = Coverage.coveredFraction(track, alongTheLine)
        assertTrue("a little over half: $lineDone", lineDone > 0.5 && lineDone < 0.6)

        // And driving only the spur is not the whole track either.
        val upTheSpur = listOf(junction, spurEnd)
        val spurDone = Coverage.coveredFraction(track, upTheSpur)
        assertTrue("the spur's half, and the junction: $spurDone", spurDone > 0.5 && spurDone < 0.7)

        // Both: all of it.
        assertEquals(1.0, Coverage.coveredFraction(track, alongTheLine + upTheSpur), 0.02)
    }

    @Test
    fun `a track with no side tracks reads exactly as one path always did`() {
        val origin = GeoPoint(0.0, 0.0)
        val east = GeoPoint(0.0, 0.001)
        val recorded = (0..10).map { step -> GeoPoint(0.0, step * 0.0001) }

        assertEquals(
            Coverage.coveredFraction(listOf(origin, east), recorded),
            Coverage.coveredFraction(AssetGeometry.of(listOf(origin, east)), recorded),
            0.0
        )
    }

    @Test
    fun `the stretches of a track come back per path, so a spur keeps its own date`() {
        val origin = GeoPoint(0.0, 0.0)
        val junction = GeoPoint(0.0, 0.0005)
        val east = GeoPoint(0.0, 0.001)
        val spurEnd = GeoPoint(0.001, 0.0005)
        val track = AssetGeometry.of(listOf(origin, junction, east), listOf(listOf(junction, spurEnd)))

        // The line sprayed at nine and the spur at ten. Each path is cut on its own, so the part of the
        // track that only got the second pass carries the second date - rather than the spur's metres
        // being read as the far end of the line.
        val stretches = Coverage.splitByCoverage(
            planned = track,
            passes = listOf(
                RecordedPass(atEpochMs = 9_000L, points = listOf(origin, junction, east)),
                RecordedPass(atEpochMs = 10_000L, points = listOf(junction, spurEnd))
            )
        )

        val fromTheSpurPass = stretches.filter { it.lastSprayedAtEpochMs == 10_000L }
        assertTrue(
            "the spur's own pass is on the map: ${stretches.map { it.lastSprayedAtEpochMs }}",
            fromTheSpurPass.isNotEmpty()
        )
        val spurMetres = fromTheSpurPass.sumOf { it.lengthM }
        assertTrue(
            "and it accounts for the spur: $spurMetres m of a 222 m track, which is the spur " +
                "plus what the tolerance reaches either side of the junction",
            spurMetres > 111.0 && spurMetres < 145.0
        )
        assertEquals(
            "every metre of the track is in exactly one stretch",
            track.lengthM,
            stretches.sumOf { it.lengthM },
            0.2
        )
    }
}
