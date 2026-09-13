package nz.mckenzie.sprayday.domain.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class TileMathTest {

    private val world = LatLngBounds(minLat = -85.0, minLng = -180.0, maxLat = 85.0, maxLng = 180.0)

    @Test
    fun `zoom zero is a single tile`() {
        assertEquals(1L, TileMath.tileRange(world, 0).count)
    }

    @Test
    fun `zoom one is four tiles for the whole world`() {
        assertEquals(4L, TileMath.tileRange(world, 1).count)
    }

    @Test
    fun `prime meridian and equator fall on tile one one at zoom one`() {
        assertEquals(1, TileMath.lonToTileX(0.0, 1))
        assertEquals(1, TileMath.latToTileY(0.0, 1))
    }

    @Test
    fun `tile x increases eastwards`() {
        assertTrue(TileMath.lonToTileX(170.0, 10) < TileMath.lonToTileX(175.0, 10))
    }

    @Test
    fun `tile y increases southwards`() {
        // Web Mercator Y is inverted: northern latitudes map to smaller tile rows.
        assertTrue(TileMath.latToTileY(-35.0, 10) < TileMath.latToTileY(-45.0, 10))
    }

    @Test
    fun `longitude outside the normal range is wrapped`() {
        assertEquals(TileMath.lonToTileX(179.0, 8), TileMath.lonToTileX(179.0 + 360.0, 8))
        assertEquals(TileMath.lonToTileX(-179.0, 8), TileMath.lonToTileX(-179.0 - 360.0, 8))
    }

    @Test
    fun `poles are clamped to the mercator limit`() {
        assertEquals(TileMath.latToTileY(TileMath.MAX_MERCATOR_LAT, 6), TileMath.latToTileY(89.9, 6))
        assertEquals(TileMath.latToTileY(-TileMath.MAX_MERCATOR_LAT, 6), TileMath.latToTileY(-89.9, 6))
    }

    @Test
    fun `tile count is the sum of each zoom level`() {
        val nz = LatLngBounds(minLat = -41.5, minLng = 174.5, maxLat = -41.0, maxLng = 175.0)
        val expected = (12..16).sumOf { TileMath.tileRange(nz, it).count }
        assertEquals(expected, TileMath.tileCount(nz, 12, 16))
    }

    @Test
    fun `each extra zoom level covers at least as many tiles`() {
        val nz = LatLngBounds(minLat = -41.5, minLng = 174.5, maxLat = -41.0, maxLng = 175.0)
        val z12 = TileMath.tileCount(nz, 12, 12)
        val z16 = TileMath.tileCount(nz, 12, 16)
        assertTrue("z12=$z12 z12..16=$z16", z16 > z12)
    }

    @Test
    fun `an inverted zoom range yields no tiles`() {
        assertEquals(0L, TileMath.tileCount(world, 16, 12))
    }

    @Test
    fun `size estimate multiplies tile count by average tile size`() {
        val nz = LatLngBounds(minLat = -41.5, minLng = 174.5, maxLat = -41.0, maxLng = 175.0)
        val bytes = TileMath.estimateBytes(nz, 12, 16, averageTileBytes = 40_000L)
        assertEquals(TileMath.tileCount(nz, 12, 16) * 40_000L, bytes)
    }

    @Test
    fun `default tile size estimate is sane for a spray block`() {
        // ~20 x 20 km around a rural spray area.
        val block = LatLngBounds(minLat = -41.4, minLng = 174.6, maxLat = -41.22, maxLng = 174.85)
        val bytes = TileMath.estimateBytes(block, 12, 16)
        val mb = bytes / 1_000_000.0
        assertTrue("expected roughly 10-200 MB but was $mb MB", mb in 10.0..200.0)
    }

    @Test
    fun `bounding box rejects inverted coordinates`() {
        assertThrows(IllegalArgumentException::class.java) {
            LatLngBounds(minLat = 10.0, minLng = 0.0, maxLat = -10.0, maxLng = 1.0)
        }
    }
}
