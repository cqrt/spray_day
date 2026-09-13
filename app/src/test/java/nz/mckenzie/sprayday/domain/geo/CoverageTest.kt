package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
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

    @Test
    fun `driving half the line covers about half of it`() {
        val planned = lineEast(1_000.0)

        val covered = Coverage.coveredFraction(planned, driveAlong(500.0), tolerance)

        assertEquals(0.5, covered, 0.05)
    }

    @Test
    fun `a gap in the middle is reported as a gap`() {
        val planned = lineEast(1_200.0)
        // The first and last thirds are driven; the middle third is missed.
        val recorded = driveAlong(400.0) +
            driveAlong(400.0, fromLng = 800.0 / METRES_PER_DEG_LNG_AT_EQUATOR)

        val covered = Coverage.coveredFraction(planned, recorded, tolerance)

        // The two thirds driven, plus the tolerance reaching just past each end of
        // a driven section - roughly 824 m of 1,200 m.
        assertEquals(0.69, covered, 0.03)
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
}
