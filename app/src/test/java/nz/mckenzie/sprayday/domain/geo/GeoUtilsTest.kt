package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class GeoUtilsTest {

    private val wellington = GeoPoint(-41.2865, 174.7762)
    private val auckland = GeoPoint(-36.8485, 174.7633)

    @Test
    fun `haversine of a point with itself is zero`() {
        assertEquals(0.0, haversineMeters(-41.0, 174.0, -41.0, 174.0), 0.0001)
    }

    @Test
    fun `one degree of latitude is about 111 km`() {
        val distance = haversineMeters(0.0, 0.0, 1.0, 0.0)
        assertEquals(111_194.9, distance, 50.0)
    }

    @Test
    fun `one degree of longitude at the equator is about 111 km`() {
        val distance = haversineMeters(0.0, 0.0, 0.0, 1.0)
        assertEquals(111_194.9, distance, 50.0)
    }

    @Test
    fun `distance is symmetric`() {
        val forward = haversineMeters(wellington.lat, wellington.lng, auckland.lat, auckland.lng)
        val backward = haversineMeters(auckland.lat, auckland.lng, wellington.lat, wellington.lng)
        assertEquals(forward, backward, 0.0001)
    }

    @Test
    fun `wellington to auckland is about 493 km`() {
        val distance = haversineMeters(wellington.lat, wellington.lng, auckland.lat, auckland.lng)
        assertTrue("expected ~493 km but was ${distance / 1000} km", distance in 488_000.0..498_000.0)
    }

    @Test
    fun `polyline length sums its segments`() {
        val line = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.001), GeoPoint(0.0, 0.002))
        assertEquals(222.4, polylineLengthMeters(line), 1.0)
    }

    @Test
    fun `polyline length of fewer than two points is zero`() {
        assertEquals(0.0, polylineLengthMeters(emptyList()), 0.0)
        assertEquals(0.0, polylineLengthMeters(listOf(GeoPoint(0.0, 0.0))), 0.0)
    }

    @Test
    fun `distance to segment measures perpendicular offset`() {
        val start = GeoPoint(0.0, 0.0)
        val end = GeoPoint(0.0, 0.001)
        val beside = GeoPoint(0.0001, 0.0005)
        assertEquals(11.11, distanceToSegmentMeters(beside, start, end), 0.5)
    }

    @Test
    fun `distance to segment clamps beyond the segment ends`() {
        val start = GeoPoint(0.0, 0.0)
        val end = GeoPoint(0.0, 0.001)
        val beyond = GeoPoint(0.0, 0.002)
        assertEquals(111.19, distanceToSegmentMeters(beyond, start, end), 0.5)
    }

    @Test
    fun `distance to segment handles a degenerate segment`() {
        val point = GeoPoint(0.0, 0.0)
        val start = GeoPoint(0.0, 0.001)
        assertEquals(111.19, distanceToSegmentMeters(point, start, start), 0.5)
    }

    @Test
    fun `distance to polyline takes the nearest segment`() {
        val polyline = listOf(GeoPoint(0.0, 0.0), GeoPoint(0.0, 0.01), GeoPoint(0.01, 0.01))
        val nearFirstSegment = GeoPoint(0.0001, 0.005)
        assertEquals(11.11, distanceToPolylineMeters(nearFirstSegment, polyline), 0.5)
    }

    @Test
    fun `distance to an empty polyline is not a number`() {
        assertTrue(distanceToPolylineMeters(GeoPoint(0.0, 0.0), emptyList()).isNaN())
    }

    @Test
    fun `estimated area multiplies length by swath width`() {
        assertEquals(300.0, estimatedAreaSqm(100.0, 3.0), 0.0001)
        assertEquals(0.0, estimatedAreaSqm(0.0, 3.0), 0.0)
        assertEquals(0.0, estimatedAreaSqm(100.0, 0.0), 0.0)
        assertEquals(0.0, estimatedAreaSqm(-5.0, 3.0), 0.0)
    }
}
