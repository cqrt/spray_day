package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Tapping a track on the map.
 *
 * The cases that matter are the ones where being wrong is annoying: a tap on empty
 * paddock must open nothing, and a tap between two tracks must pick the one it is
 * closest to rather than whichever happens to be first.
 */
class TrackHitTestTest {

    // A line running east-west at 41.5S, near Nelson.
    private val homeLine = listOf(
        GeoPoint(-41.5000, 173.9500),
        GeoPoint(-41.5000, 173.9600)
    )

    // A parallel line about 100 m north.
    private val riverLine = listOf(
        GeoPoint(-41.4991, 173.9500),
        GeoPoint(-41.4991, 173.9600)
    )

    @Test
    fun `a tap on a line picks that line`() {
        val geometry = mapOf(7L to homeLine, 9L to riverLine)

        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5000, lng = 173.9550))
        assertEquals(9L, TrackHitTest.nearest(geometry, lat = -41.4991, lng = 173.9550))
    }

    @Test
    fun `a near miss still counts, because a finger is not a mouse`() {
        val geometry = mapOf(7L to homeLine)

        // About 12 m off the line: well within a fingertip.
        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5001, lng = 173.9550))
    }

    @Test
    fun `a tap on empty paddock opens nothing`() {
        val geometry = mapOf(7L to homeLine)

        // About 500 m away.
        assertNull(TrackHitTest.nearest(geometry, lat = -41.5045, lng = 173.9550))
    }

    @Test
    fun `between two tracks the nearer one wins`() {
        // About 33 m apart, as two passes on neighbouring rows would be.
        val geometry = mapOf(
            7L to listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600)),
            9L to listOf(GeoPoint(-41.4997, 173.9500), GeoPoint(-41.4997, 173.9600))
        )

        // A few metres from track 9, the width of a swath from track 7.
        assertEquals(9L, TrackHitTest.nearest(geometry, lat = -41.4997, lng = 173.9550))
        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5000, lng = 173.9550))
    }

    @Test
    fun `tracks drawn over each other always resolve the same way`() {
        // Exactly the same line under two ids, which is the only way the distances can
        // be *exactly* equal - and so the only honest way to test the tie-break.
        val geometry = mapOf(
            9L to listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600)),
            7L to listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600))
        )

        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5000, lng = 173.9550))
    }

    @Test
    fun `the ends of a line are tappable, not just its middle`() {
        val geometry = mapOf(7L to homeLine)

        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5000, lng = 173.9500))
        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5000, lng = 173.9600))
    }

    @Test
    fun `an empty map, or a track with no points, opens nothing`() {
        assertNull(TrackHitTest.nearest(emptyMap(), lat = -41.5, lng = 173.95))
        assertNull(TrackHitTest.nearest(mapOf(7L to emptyList()), lat = -41.5, lng = 173.95))
    }

    @Test
    fun `a single-point track is still a target`() {
        val geometry = mapOf(7L to listOf(GeoPoint(-41.5000, 173.9550)))

        assertEquals(7L, TrackHitTest.nearest(geometry, lat = -41.5001, lng = 173.9550))
    }

    @Test
    fun `the tap radius follows the zoom, because a finger does not`() {
        // Zoomed in for spraying, a fingertip is tens of metres.
        val spraying = TrackHitTest.toleranceForZoom(zoom = 16.0, latitude = -41.5)
        assertTrue("at spray height: $spraying m", spraying in 20.0..100.0)

        // Zoomed out to see the farm, the same finger covers kilometres - without which
        // tapping a track at country scale would mean hitting a line narrower than a pixel.
        val overview = TrackHitTest.toleranceForZoom(zoom = 6.0, latitude = -41.5)
        assertTrue("at farm scale: $overview m", overview > 10_000.0)

        // It also never becomes *tighter* than the fixed default, so a very deep zoom
        // does not make tapping fussy.
        assertTrue(TrackHitTest.toleranceForZoom(22.0, -41.5) >= TrackHitTest.DEFAULT_TOLERANCE_M)
    }
}
