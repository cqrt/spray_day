package nz.mckenzie.sprayday.offline

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The maths behind the area picker: what the operator is told a download will cost,
 * while they are still choosing it.
 */
class OfflineAreaDraftTest {

    /** Roughly a 1 km x 1 km block near Invercargill. */
    private val southWest = GeoPoint(-46.4200, 168.3400)
    private val northEast = GeoPoint(-46.4100, 168.3550)

    /**
     * A farm-sized block, ~11 km across. Cost tests need this: a 1 km box is one tile
     * at both zoom 12 and zoom 13, which shows nothing about the fourfold step.
     */
    private fun bigDraft(minZoom: Int = 12, maxZoom: Int = 12) = OfflineAreaDraft(
        corners = listOf(GeoPoint(-46.47, 168.30), GeoPoint(-46.37, 168.45)),
        minZoom = minZoom,
        maxZoom = maxZoom
    )

    private fun draft(
        corners: List<GeoPoint> = listOf(southWest, northEast),
        minZoom: Int = 10,
        maxZoom: Int = 12
    ) = OfflineAreaDraft(corners = corners, minZoom = minZoom, maxZoom = maxZoom)

    @Test
    fun `an area needs two corners before it has bounds`() {
        val empty = OfflineAreaDraft()
        assertNull(empty.bounds)
        assertFalse(empty.isComplete)
        assertEquals(0L, empty.tileCount)

        val half = empty.withCorner(southWest)
        assertNull("one corner is not a box", half.bounds)
        assertFalse(half.isComplete)
        assertEquals("but the first corner is remembered", southWest, half.firstCorner)

        val whole = half.withCorner(northEast)
        assertTrue(whole.isComplete)
        assertEquals(-46.4200, whole.bounds!!.minLat, 1e-9)
        assertEquals(168.3400, whole.bounds!!.minLng, 1e-9)
        assertEquals(-46.4100, whole.bounds!!.maxLat, 1e-9)
        assertEquals(168.3550, whole.bounds!!.maxLng, 1e-9)
    }

    @Test
    fun `corners can be tapped in any order or direction`() {
        val forwards = draft(corners = listOf(southWest, northEast))
        val backwards = draft(corners = listOf(northEast, southWest))

        assertEquals(forwards.bounds, backwards.bounds)
    }

    @Test
    fun `a third tap starts a new box rather than pinning an invalid one`() {
        val restarted = draft().withCorner(GeoPoint(-46.50, 168.30))

        assertFalse(restarted.isComplete)
        assertEquals(1, restarted.corners.size)
        assertEquals(-46.50, restarted.firstCorner!!.lat, 1e-9)
    }

    @Test
    fun `clearing removes both corners`() {
        val cleared = draft().withoutCorners()

        assertTrue(cleared.corners.isEmpty())
        assertNull(cleared.bounds)
    }

    @Test
    fun `each extra zoom level costs about four times as much`() {
        val upToTwelve = bigDraft(minZoom = 10, maxZoom = 12)
        val oneMore = bigDraft(minZoom = 10, maxZoom = 13)
        val oneFewer = bigDraft(minZoom = 10, maxZoom = 11)

        assertTrue("the count should be positive", upToTwelve.tileCount > 0)
        assertTrue(oneFewer.tileCount < upToTwelve.tileCount)
        assertTrue(
            "depth is expensive: ${oneMore.tileCount} tiles to 13 vs ${upToTwelve.tileCount} to 12",
            oneMore.tileCount > upToTwelve.tileCount * 2
        )

        // One level on its own: the step is multiplicative, not additive. It is not
        // exactly fourfold in practice - a box that spans 2.8 tiles at one zoom can
        // land on 3 containers there and 5 at the next zoom - so this pins the shape
        // of the growth (and the fact that a warning is worth having) rather than a
        // number that grid alignment moves around.
        val levelTwelve = bigDraft(minZoom = 12, maxZoom = 12).tileCount
        val levelThirteen = bigDraft(minZoom = 13, maxZoom = 13).tileCount
        assertTrue("more than double: $levelThirteen vs $levelTwelve", levelThirteen > levelTwelve * 2)
        assertTrue("less than sixfold: $levelThirteen vs $levelTwelve", levelThirteen < levelTwelve * 6)
    }

    @Test
    fun `the sliders cannot cross`() {
        assertEquals(12 to 12, draft().withZoomRange(12, 9).let { it.minZoom to it.maxZoom })
        assertEquals(10 to 18, draft().withZoomRange(10, 18).let { it.minZoom to it.maxZoom })
        assertEquals(8 to 19, draft().withZoomRange(0, 99).let { it.minZoom to it.maxZoom })
    }

    @Test
    fun `an area past the limit is refused before it is downloaded`() {
        // A couple of degrees at zoom 19 is millions of tiles.
        val huge = OfflineAreaDraft(
            corners = listOf(GeoPoint(-47.0, 167.0), GeoPoint(-45.0, 169.0)),
            minZoom = 10,
            maxZoom = 19
        )

        assertTrue("the screen must be able to say this is too big", huge.isTooBig)
        assertFalse(draft().isTooBig)
    }

    @Test
    fun `the plan carries the typed name, or a fallback`() {
        val named = draft().plan(name = "  Top block  ", fallbackName = "Unused")
        assertEquals("Top block", named!!.name)
        assertEquals(10, named.minZoom)
        assertEquals(12, named.maxZoom)

        val unnamed = draft().plan(name = "   ", fallbackName = "Area 14 Sep")
        assertEquals("Area 14 Sep", unnamed!!.name)

        assertNull("no box, no plan", OfflineAreaDraft().plan("x", "y"))
    }

    @Test
    fun `the estimate is shown in the units an operator reads`() {
        val single = draft(minZoom = 12, maxZoom = 12)
        val deeper = bigDraft(minZoom = 10, maxZoom = 16)

        assertEquals(single.tileCount * 45_000L, single.estimatedBytes)
        assertTrue("a whole block at depth reads in megabytes: ${deeper.estimatedSizeLabel}", deeper.estimatedSizeLabel.endsWith("MB"))
        assertTrue("and is clearly the bigger download", deeper.estimatedBytes > single.estimatedBytes)
    }
}
