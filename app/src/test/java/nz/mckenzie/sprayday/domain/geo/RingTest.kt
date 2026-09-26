package nz.mckenzie.sprayday.domain.geo

import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The ring a carpark's geometry is: closed on itself, and measured off its own corners.
 *
 * Both numbers a carpark exists for come out of here - the metres round it, and the ground inside it -
 * so they are pinned against a shape whose answer is known by hand rather than against whatever the
 * code happened to produce.
 */
class RingTest {

    /** A corner [eastM] east and [northM] north of the origin, on the app's own local flat. */
    private fun corner(eastM: Double, northM: Double): GeoPoint = GeoPoint(
        lat = LATITUDE + northM / METRES_PER_DEG_LAT,
        lng = eastM / (METRES_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(LATITUDE)))
    )

    /** A 100 m by 50 m yard: 5,000 m² of ground and 300 m round the outside. */
    private val yard = listOf(
        corner(0.0, 0.0),
        corner(100.0, 0.0),
        corner(100.0, 50.0),
        corner(0.0, 50.0)
    )

    @Test
    fun `a square of known size reads its own ground`() {
        assertEquals(
            "100 m by 50 m is 5,000 m², measured off its own corners",
            5_000.0,
            Ring.areaSqm(yard),
            // Half a percent: this is a measurement rather than a survey, and the flat it is worked out
            // on is the same one the coverage maths already uses.
            25.0
        )
    }

    @Test
    fun `the winding does not change the ground`() {
        assertEquals(Ring.areaSqm(yard), Ring.areaSqm(yard.reversed()), 0.001)
    }

    @Test
    fun `a point inside the boundary is on the ground, and one outside it is not`() {
        assertTrue("the middle of the yard is on the yard", Ring.contains(yard, corner(50.0, 25.0)))
        assertTrue("and the corner of it is inside too", Ring.contains(yard, corner(1.0, 1.0)))
        assertFalse("a step east of the boundary is off it", Ring.contains(yard, corner(101.0, 25.0)))
        assertFalse("and a step north of it is off", Ring.contains(yard, corner(50.0, 51.0)))
        assertTrue(
            "the corners on their own and the ring as it is stored answer the same",
            Ring.contains(Ring.closed(yard), corner(50.0, 25.0))
        )
        assertFalse(
            "a path that encloses nothing contains nothing",
            Ring.contains(listOf(corner(0.0, 0.0), corner(100.0, 0.0)), corner(50.0, 0.0))
        )
    }

    @Test
    fun `a point level with a corner is still read as outside`() {
        val triangle = listOf(corner(0.0, 0.0), corner(100.0, 0.0), corner(0.0, 100.0))

        assertTrue("well inside the triangle", Ring.contains(triangle, corner(30.0, 30.0)))
        assertFalse("well outside it, level with a corner", Ring.contains(triangle, corner(-10.0, 100.0)))
    }

    @Test
    fun `the metres round a ring include the side that closes it`() {
        val open = polylineLengthMeters(yard)
        val closed = polylineLengthMeters(Ring.closed(yard))

        assertEquals("four sides of 100 m, 100 m, 50 m and 50 m", 300.0, closed, 1.0)
        assertEquals(
            "and the side that closes it is the last of them, the 50 m one",
            50.0,
            closed - open,
            0.5
        )
    }

    @Test
    fun `closing adds the first corner, and only when it is not already there`() {
        val closed = Ring.closed(yard)

        assertEquals(5, closed.size)
        assertEquals(closed.first(), closed.last())
        assertEquals("closing a ring that is closed changes nothing", closed, Ring.closed(closed))
    }

    @Test
    fun `a ring knows how many corners it has, stored closed or open`() {
        assertEquals(4, Ring.cornerCount(yard))
        assertEquals(4, Ring.cornerCount(Ring.closed(yard)))
        assertEquals("a place is one corner and encloses nothing", 0.0, Ring.areaSqm(yard.take(1)), 1e-9)
    }

    @Test
    fun `a path there and back is not a ring`() {
        val thereAndBack = listOf(corner(0.0, 0.0), corner(100.0, 0.0), corner(0.0, 0.0))

        assertFalse("three vertices with the first joined to the last are two corners", Ring.isClosed(thereAndBack))
        assertEquals(2, Ring.cornerCount(thereAndBack))
        assertEquals(
            "and two corners enclose no ground at all, which is the honest answer for one",
            0.0,
            Ring.areaSqm(thereAndBack),
            1e-9
        )
    }

    @Test
    fun `the least a ring can be is three corners`() {
        val triangle = listOf(corner(0.0, 0.0), corner(100.0, 0.0), corner(0.0, 100.0))

        assertEquals(Ring.MIN_CORNERS, Ring.cornerCount(triangle))
        assertTrue("a right triangle of 100 m legs is 5,000 m²", Ring.areaSqm(triangle) > 4_900.0)
    }

    private companion object {
        /** Somewhere in the Marlborough sounds, so the longitude flats are not the equator's. */
        const val LATITUDE = -41.28
    }
}
