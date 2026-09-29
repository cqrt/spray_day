package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The stretch of a track that carries its name.
 *
 * What is pinned here is that the stretch is *near the middle of the track* and *gentle enough to write
 * across* - the two things the map needs before it will draw a name at all - because both of those are
 * decisions taken without a map in front of them, and a track is where the operator will notice if they
 * are wrong: a name at the far end of a paddock, or a name that is not there.
 */
class TrackNamesTest {

    /** A point a whole number of thousandths of a degree from a corner of Wellington. */
    private fun at(east: Double, north: Double) =
        GeoPoint(lat = -41.0 + north * 0.001, lng = 174.0 + east * 0.001)

    private fun lengthsOf(piece: List<GeoPoint>): Int = piece.size

    @Test
    fun `a line with nothing to choose between is its own stretch`() {
        val straight = listOf(at(0.0, 0.0), at(1.0, 0.0))

        assertEquals(straight, TrackNames.pieceOf(straight))
    }

    @Test
    fun `there is no stretch on a line that is not a line`() {
        assertTrue(TrackNames.pieceOf(emptyList()).isEmpty())
        assertTrue(TrackNames.pieceOf(listOf(at(0.0, 0.0))).isEmpty())
    }

    @Test
    fun `a straight track is written on from end to end`() {
        val straight = listOf(at(0.0, 0.0), at(1.0, 0.0), at(2.0, 0.0), at(3.0, 0.0))

        assertEquals(straight, TrackNames.pieceOf(straight))
    }

    @Test
    fun `the stretch grows along a straight run and stops at the corner`() {
        // Half way along this track is a vertex in the middle of the long eastward run, not the corner
        // where it turns north: the stretch takes that whole run - the corner point included, so the name
        // has the run's own length to be written along - and stops before the leg going north.
        val track = listOf(
            at(0.0, 0.0), at(1.0, 0.0), at(2.0, 0.0), at(3.0, 0.0), at(4.0, 0.0), at(4.0, 1.0)
        )

        val piece = TrackNames.pieceOf(track)

        assertEquals("the eastward run, and not the leg turning north", 5, lengthsOf(piece))
        assertEquals(at(0.0, 0.0), piece.first())
        assertEquals(at(4.0, 0.0), piece.last())
    }

    @Test
    fun `a corner at the middle gives the name the longer of the two lines leaving it`() {
        // East for a step, then north for a longer one: the middle of the track is the corner, and the
        // name goes on the northward leg rather than being dropped.
        val track = listOf(at(0.0, 0.0), at(1.0, 0.0), at(1.0, 2.0))

        val piece = TrackNames.pieceOf(track)

        assertEquals(listOf(at(1.0, 0.0), at(1.0, 2.0)), piece)
    }

    @Test
    fun `a track that doubles back on itself is still written on`() {
        // A hairpin at the middle: the two legs are the same length, so the stretch is the one before
        // the turn - and a name is better on half the track than on none of it.
        val track = listOf(at(0.0, 0.0), at(1.0, 0.0), at(2.0, 0.0), at(1.0, 0.0))

        val piece = TrackNames.pieceOf(track)

        assertEquals(listOf(at(1.0, 0.0), at(2.0, 0.0)), piece)
    }

    @Test
    fun `the stretch is a piece of the track's own line, in its own order`() {
        val track = listOf(
            at(0.0, 0.0), at(1.0, 0.0), at(2.0, 0.0), at(3.0, 0.0), at(4.0, 0.0), at(4.0, 1.0)
        )

        val piece = TrackNames.pieceOf(track)

        assertTrue("the points are the track's own", track.containsAll(piece))
        val first = track.indexOf(piece.first())
        piece.forEachIndexed { index, point ->
            assertEquals("the piece runs along the track rather than jumping about it", point, track[first + index])
        }
    }

    @Test
    fun `the numbers the two maps write a name with are pinned`() {
        // Both maps read these, so a change here changes both - which is the point of them being in one
        // place. Twelve pixels with a thin outline and a wide allowance for corners is what the first
        // look measured as readable; see `build/verify/names-look-first.txt`. The zoom floor is the
        // operator's own finding after living with the first version: names crowded a wide view and read
        // as a muddle, so a name now waits until the map is close in - and both maps wait for the same
        // level, which is why the number is here rather than in either map's own file.
        assertEquals(16f, TrackNames.MIN_ZOOM)
        assertEquals(12f, TrackNames.SIZE)
        assertEquals(0.7f, TrackNames.HALO_WIDTH)
        assertEquals(180f, TrackNames.MAX_ANGLE)
        assertEquals("carriesName", TrackNames.CARRIES_NAME)
        assertEquals("#FFFFFF", TrackNames.COLOUR)
        assertEquals("#000000", TrackNames.HALO_COLOUR)
    }
}
