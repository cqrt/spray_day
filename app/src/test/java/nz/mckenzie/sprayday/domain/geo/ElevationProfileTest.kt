package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The height of a line, worked out from its own points.
 *
 * The arithmetic is what a range on a card and a profile on a map are both drawn from, so it is held
 * here rather than by reading a chart off a screen: a climb, a descent, a flat run, a height missing
 * in the middle, and a line with too few heights to say anything at all.
 */
class ElevationProfileTest {

    /** A point on a line running north at a known step, with the height [height] or none. */
    private fun at(step: Int, height: Double?) =
        GeoPoint(lat = 0.001 * step, lng = 0.0, altitudeM = height)

    @Test
    fun `a line that climbs and comes back says how far each way`() {
        val profile = ElevationProfile.of(listOf(at(0, 100.0), at(1, 130.0), at(2, 120.0)))!!

        assertEquals(100.0, profile.lowestM, 1e-9)
        assertEquals(130.0, profile.highestM, 1e-9)
        assertEquals(30.0, profile.rangeM, 1e-9)
        assertEquals(30.0, profile.climbM, 1e-9)
        assertEquals(10.0, profile.descentM, 1e-9)
        assertEquals(3, profile.samples.size)
    }

    @Test
    fun `a height missing in the middle is a step, not a break`() {
        val profile = ElevationProfile.of(listOf(at(0, 100.0), at(1, null), at(2, 200.0)))!!

        // Two heights, so two samples: the ground still rose 100 m between them.
        assertEquals(2, profile.samples.size)
        assertEquals(100.0, profile.climbM, 1e-9)
        assertEquals(0.0, profile.descentM, 1e-9)
        // And the second sample sits at the end of the line, because the length is the line's own -
        // the missing point's metres are still travelled.
        assertEquals(
            haversineMeters(0.0, 0.0, 0.002, 0.0),
            profile.samples[1].alongM,
            1e-6
        )
    }

    @Test
    fun `a flat line climbs and falls by nothing`() {
        val profile = ElevationProfile.of(listOf(at(0, 50.0), at(1, 50.0)))!!

        assertEquals(0.0, profile.climbM, 1e-9)
        assertEquals(0.0, profile.descentM, 1e-9)
        assertEquals(0.0, profile.rangeM, 1e-9)
    }

    @Test
    fun `fewer than two heights is nothing to draw`() {
        assertNull("one height among three points", ElevationProfile.of(listOf(at(0, 100.0), at(1, null), at(2, null))))
        assertNull("no heights at all", ElevationProfile.of(listOf(at(0, null), at(1, null))))
        assertNull("one point", ElevationProfile.of(listOf(at(0, 100.0))))
        assertNull("an empty line", ElevationProfile.of(emptyList()))
    }

    @Test
    fun `the range is said in metres an operator reads`() {
        val profile = ElevationProfile.of(listOf(at(0, 553.6), at(1, 663.6), at(2, 560.0)))!!

        assertEquals("≈554–664 m, up 110 m", ElevationPhrase.range(profile))
        assertNull("nothing to say about a line with no height", ElevationPhrase.range(null))
    }

    @Test
    fun `a line that mostly falls says so`() {
        val profile = ElevationProfile.of(listOf(at(0, 600.0), at(1, 500.0)))!!

        assertEquals("≈500–600 m, down 100 m", ElevationPhrase.range(profile))
    }
}
