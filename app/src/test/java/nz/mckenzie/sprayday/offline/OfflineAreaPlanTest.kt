package nz.mckenzie.sprayday.offline

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

class OfflineAreaPlanTest {

    private val blenheim = GeoPoint(-41.51, 173.96)

    @Test
    fun `a plan around a centre spans twice the radius`() {
        val plan = OfflineAreaPlan.aroundCentre("Block A", blenheim, radiusKm = 10.0)

        // 20 km of latitude is about 0.18 degrees, whatever the longitude.
        assertEquals(0.18, plan.bounds.maxLat - plan.bounds.minLat, 0.001)
        assertEquals(blenheim.lat, (plan.bounds.minLat + plan.bounds.maxLat) / 2, 1e-9)
    }

    @Test
    fun `longitude span widens away from the equator`() {
        val atEquator = OfflineAreaPlan.aroundCentre("Equator", GeoPoint(0.0, 0.0), radiusKm = 10.0)
        val atBlenheim = OfflineAreaPlan.aroundCentre("Blenheim", blenheim, radiusKm = 10.0)

        val equatorSpan = atEquator.bounds.maxLng - atEquator.bounds.minLng
        val blenheimSpan = atBlenheim.bounds.maxLng - atBlenheim.bounds.minLng

        assertTrue("expected a wider span at higher latitude", blenheimSpan > equatorSpan)
        // cos(41.5 degrees) is about 0.75.
        assertEquals(equatorSpan / 0.75, blenheimSpan, 0.01)
    }

    @Test
    fun `the plan reports how wide it is in kilometres`() {
        val plan = OfflineAreaPlan.aroundCentre("Block A", blenheim, radiusKm = 10.0)

        assertEquals(20.0, plan.approxWidthKm, 0.5)
    }

    @Test
    fun `tile count grows with the zoom range`() {
        val narrow = OfflineAreaPlan.aroundCentre("Block A", blenheim, radiusKm = 2.0)
        val wide = narrow.copy(maxZoom = narrow.maxZoom + 2)

        assertTrue(wide.tileCount > narrow.tileCount)
        assertEquals(0L, OfflineAreaPlan.aroundCentre("Bad", blenheim, 2.0, minZoom = 16, maxZoom = 12).tileCount)
    }

    @Test
    fun `a ten kilometre block at the default zooms is tens of megabytes`() {
        val plan = OfflineAreaPlan.aroundCentre("Block A", blenheim, radiusKm = 10.0)

        val megabytes = plan.estimatedBytes / 1_000_000.0
        assertTrue("expected 10-300 MB but was $megabytes MB", megabytes in 10.0..300.0)
        assertTrue(plan.estimatedSizeLabel.endsWith("MB"))
    }

    @Test
    fun `byte sizes are formatted for humans`() {
        assertEquals("512 B", formatBytes(512))
        assertEquals("1 KB", formatBytes(1024))
        assertEquals("1.5 KB", formatBytes(1536))
        assertEquals("1 MB", formatBytes(1024L * 1024L))
        assertEquals("2.5 GB", formatBytes(2_684_354_560L))
    }

    @Test
    fun `latitude is clamped at the mercator limits`() {
        val nearPole = OfflineAreaPlan.aroundCentre("Polar", GeoPoint(84.9, 0.0), radiusKm = 100.0)

        assertTrue(nearPole.bounds.maxLat <= 85.0)
    }
}
