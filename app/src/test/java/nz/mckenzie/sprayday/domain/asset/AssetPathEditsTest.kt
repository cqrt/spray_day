package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a path drawn on a computer has to be before the phone will store it.
 *
 * The desk's drawing is JavaScript and the phone's own drawing is Kotlin, so this is the border
 * between them: a page is a client, and a client that sends a line of one point, a place with five,
 * or a coordinate from a map that was asked to unproject before it had a size, is answered rather
 * than obeyed. The sentences are held here too, because they are what the operator reads.
 */
class AssetPathEditsTest {

    /** Two points, eastwards: a line, and the shortest one there is. */
    private val line = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.6, 173.9))

    private fun ok(shape: AssetShape, points: List<GeoPoint>): List<GeoPoint> {
        val result = AssetPathEdits.apply(shape, points)
        assertTrue("expected these to be taken: $result", result is AssetPathResult.Ok)
        return (result as AssetPathResult.Ok).points
    }

    private fun refused(shape: AssetShape, points: List<GeoPoint>): String {
        val result = AssetPathEdits.apply(shape, points)
        assertTrue("expected these to be refused: $result", result is AssetPathResult.Invalid)
        return (result as AssetPathResult.Invalid).message
    }

    @Test
    fun `a path of two points or more is stored as it was drawn, in order`() {
        val drawn = line + GeoPoint(-41.4, 174.0)

        assertEquals(drawn, ok(AssetShape.LINE, drawn))
    }

    @Test
    fun `a line of one point is refused in the app's own words`() {
        // `AssetRepository.importAssetGpx` says this about a one-point GPX, word for word: a desk must
        // not be told about a one-point line in words nothing else in the app uses.
        assertEquals("A line needs at least two points", refused(AssetShape.LINE, line.take(1)))
        // And a page that finished a drawing with nothing on it says the same thing, which is right:
        // what it drew is a line of no points.
        assertEquals("A line needs at least two points", refused(AssetShape.LINE, emptyList()))
    }

    @Test
    fun `a place is one spot, and a place drawn as a path is refused`() {
        assertEquals(1, ok(AssetShape.POINT, listOf(line[0])).size)

        val said = refused(AssetShape.POINT, line + GeoPoint(-41.4, 174.0))
        assertTrue("counts them: $said", said.contains("3 points"))
        assertTrue("says what to do instead: $said", said.contains("Change it to a path"))
        // A place with nothing clicked yet says how to make one, rather than counting nothing.
        assertTrue(refused(AssetShape.POINT, emptyList()).contains("click where it goes"))
    }

    @Test
    fun `a double click on one spot is one vertex, not two`() {
        assertEquals(line, ok(AssetShape.LINE, listOf(line[0], line[0], line[1])))
        // Dragging a vertex onto its neighbour and letting go gives the same thing, and so does a
        // closed loop: the last point is only dropped when it repeats the one before it, so a track
        // that ends where it started keeps its closing vertex.
        assertEquals(line, ok(AssetShape.LINE, line + line[1]))
    }

    @Test
    fun `two vertices a hair apart are the operator's own doing and are kept`() {
        // The desktop snaps a vertex onto another by copying its coordinates, so two vertices meant to
        // be the same place *are* the same two numbers. Rounding here instead - the way a recording's
        // fixes are filtered - would quietly move a line somebody drew by hand.
        val near = listOf(line[0], GeoPoint(line[0].lat + 0.0000005, line[0].lng), line[1])

        assertEquals(3, ok(AssetShape.LINE, near).size)
    }

    @Test
    fun `a point that is not on the earth is refused rather than stored`() {
        val said = refused(AssetShape.LINE, listOf(line[0], GeoPoint(lat = 91.0, lng = 173.8)))
        assertTrue("says nothing was kept: $said", said.contains("will not keep it"))

        // NaN is what a map hands over when it is asked to unproject a click before it knows its own
        // size - a blank rectangle with the list beside it is a worse answer than a sentence.
        assertTrue(refused(AssetShape.LINE, listOf(line[0], GeoPoint(Double.NaN, 173.8))).contains("not on the earth"))
        assertTrue(refused(AssetShape.LINE, listOf(line[0], GeoPoint(-41.5, 181.0))).contains("not on the earth"))
    }

    @Test
    fun `a line longer than the phone keeps is refused, with both numbers in the sentence`() {
        val drawn = (0..AssetPathEdits.MAX_POINTS).map { GeoPoint(-41.5, 173.8 + it * 0.0001) }

        val said = refused(AssetShape.LINE, drawn)

        assertTrue("says how many arrived: $said", said.contains("${AssetPathEdits.MAX_POINTS + 1} points"))
        assertTrue("says how many it keeps: $said", said.contains("up to ${AssetPathEdits.MAX_POINTS}"))
    }

    @Test
    fun `the cap is a whole farm's worth of track and then some`() {
        // A drawn track is tens of points. The number is pinned so that a later change to it is a
        // decision somebody makes here rather than a constant nobody looks at again.
        assertEquals(2000, AssetPathEdits.MAX_POINTS)
        assertEquals(
            "and a path at the cap is still one vertex per point, in order",
            2000,
            ok(AssetShape.LINE, (0 until 2000).map { GeoPoint(-41.5, 173.8 + it * 0.0001) }).size
        )
    }
}
