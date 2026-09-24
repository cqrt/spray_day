package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.AssetGeometry
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
class AssetHitTestTest {

    /**
     * The map's own shape: every track's geometry, keyed by id, each a line with no side tracks.
     *
     * Written once here rather than at every call, so a test that wants to know about a *side track*
     * can say so by handing in an [AssetGeometry] it built itself.
     */
    private fun tracksOf(vararg tracks: Pair<Long, List<GeoPoint>>): Map<Long, AssetGeometry> =
        tracks.associate { (id, points) -> id to AssetGeometry.of(points) }

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
        val geometry = tracksOf(7L to homeLine, 9L to riverLine)

        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5000, lng = 173.9550))
        assertEquals(9L, AssetHitTest.nearest(geometry, lat = -41.4991, lng = 173.9550))
    }

    @Test
    fun `a near miss still counts, because a finger is not a mouse`() {
        val geometry = tracksOf(7L to homeLine)

        // About 12 m off the line: well within a fingertip.
        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5001, lng = 173.9550))
    }

    @Test
    fun `a tap on empty paddock opens nothing`() {
        val geometry = tracksOf(7L to homeLine)

        // About 500 m away.
        assertNull(AssetHitTest.nearest(geometry, lat = -41.5045, lng = 173.9550))
    }

    @Test
    fun `between two tracks the nearer one wins`() {
        // About 33 m apart, as two passes on neighbouring rows would be.
        val geometry = tracksOf(
            7L to listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600)),
            9L to listOf(GeoPoint(-41.4997, 173.9500), GeoPoint(-41.4997, 173.9600))
        )

        // A few metres from track 9, the width of a swath from track 7.
        assertEquals(9L, AssetHitTest.nearest(geometry, lat = -41.4997, lng = 173.9550))
        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5000, lng = 173.9550))
    }

    @Test
    fun `tracks drawn over each other always resolve the same way`() {
        // Exactly the same line under two ids, which is the only way the distances can
        // be *exactly* equal - and so the only honest way to test the tie-break.
        val geometry = tracksOf(
            9L to listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600)),
            7L to listOf(GeoPoint(-41.5000, 173.9500), GeoPoint(-41.5000, 173.9600))
        )

        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5000, lng = 173.9550))
    }

    @Test
    fun `the ends of a line are tappable, not just its middle`() {
        val geometry = tracksOf(7L to homeLine)

        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5000, lng = 173.9500))
        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5000, lng = 173.9600))
    }

    @Test
    fun `an empty map, or a track with no points, opens nothing`() {
        assertNull(AssetHitTest.nearest(emptyMap(), lat = -41.5, lng = 173.95))
        assertNull(AssetHitTest.nearest(tracksOf(7L to emptyList()), lat = -41.5, lng = 173.95))
    }

    @Test
    fun `a single-point track is still a target`() {
        val geometry = tracksOf(7L to listOf(GeoPoint(-41.5000, 173.9550)))

        assertEquals(7L, AssetHitTest.nearest(geometry, lat = -41.5001, lng = 173.9550))
    }

    @Test
    fun `the tap radius follows the zoom, because a finger does not`() {
        // Zoomed in for spraying, a fingertip is tens of metres.
        val spraying = AssetHitTest.toleranceForZoom(zoom = 16.0, latitude = -41.5)
        assertTrue("at spray height: $spraying m", spraying in 20.0..100.0)

        // Zoomed out to see the farm, the same finger covers kilometres - without which
        // tapping a track at country scale would mean hitting a line narrower than a pixel.
        val overview = AssetHitTest.toleranceForZoom(zoom = 6.0, latitude = -41.5)
        assertTrue("at farm scale: $overview m", overview > 10_000.0)

        // It also never becomes *tighter* than the fixed default, so a very deep zoom
        // does not make tapping fussy.
        assertTrue(AssetHitTest.toleranceForZoom(22.0, -41.5) >= AssetHitTest.DEFAULT_TOLERANCE_M)
    }

    @Test
    fun `a tap on the line is measured in the line's own width, not in fingertips`() {
        // The two tolerances answer two questions and must never swap: a fingertip says which line a
        // tap was about, and the line's own width says where on it. The second is the narrower at every
        // zoom - a floor under it is what let a tap at the end of a line be read as a tap on it.
        listOf(6.0, 10.0, 12.0, 15.0, 16.0, 18.0, 22.0).forEach { zoom ->
            assertTrue(
                "at zoom $zoom, on the line should be the narrower of the two",
                AssetHitTest.onTheLineToleranceForZoom(zoom, -41.5) <
                    AssetHitTest.toleranceForZoom(zoom, -41.5)
            )
        }

        // It is the line's own width, so it shrinks with the ground under it: one zoom step halves it.
        assertEquals(
            "one zoom step should halve it",
            AssetHitTest.onTheLineToleranceForZoom(16.0, -41.5),
            AssetHitTest.onTheLineToleranceForZoom(17.0, -41.5) * 2.0,
            1e-9
        )

        // And at spray height it is metres - a line you can put a finger on - rather than tens.
        assertTrue(
            "at spray height: ${AssetHitTest.onTheLineToleranceForZoom(18.0, -41.5)} m",
            AssetHitTest.onTheLineToleranceForZoom(18.0, -41.5) < 10.0
        )
    }
}
