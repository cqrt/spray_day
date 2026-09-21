package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A track is a line and the side tracks hanging off it.
 *
 * The one claim worth holding here is the length: every path counted **once**. That is the number that
 * was wrong while a spur had to be drawn as part of the line - up it and back down it - and wrong in
 * the direction that matters least visibly and most expensively, because the length is what the
 * handover and the coverage percentage are worked out against.
 */
class AssetGeometryTest {

    /** 0.001 degrees of longitude at the equator: about 111.19 m, as the app's own arithmetic has it. */
    private val east = GeoPoint(0.0, 0.001)
    private val origin = GeoPoint(0.0, 0.0)

    @Test
    fun `a line on its own is one path, and its length is that path's`() {
        val geometry = AssetGeometry.of(listOf(origin, east))

        assertEquals(1, geometry.paths.size)
        assertFalse(geometry.hasSideTracks)
        assertEquals(111.19, geometry.lengthM, 0.1)
        assertTrue(geometry.isLine)
    }

    @Test
    fun `a side track adds its own length once, not twice`() {
        // A 111 m line with a 111 m side track hanging off the middle of it: 222 m of track.
        val junction = GeoPoint(0.0, 0.0005)
        val line = listOf(origin, junction, east)
        val spur = listOf(junction, GeoPoint(0.001, 0.0005))

        val geometry = AssetGeometry.of(line, listOf(spur))

        assertEquals(2, geometry.paths.size)
        assertEquals(listOf(spur), geometry.sideTracks)
        assertEquals("the join is a vertex of the line", junction, geometry.line[1])
        assertEquals(
            "the line plus the spur, each counted once",
            111.19 + 111.19,
            geometry.lengthM,
            0.2
        )
    }

    @Test
    fun `the walk up and back the old way read is longer than the track is`() {
        // What the workaround produced: one path that goes out to the spur, up it, back down it, and on
        // to the end. The spur's metres are in there twice, which is the whole reason for paths.
        val junction = GeoPoint(0.0, 0.0005)
        val spurEnd = GeoPoint(0.001, 0.0005)
        val asOnePath = AssetGeometry.of(listOf(origin, junction, spurEnd, junction, east))
        val asPaths = AssetGeometry.of(
            listOf(origin, junction, east),
            listOf(listOf(junction, spurEnd))
        )

        // 111 m of line plus 111 m of spur, up and back: the old reading is a whole spur too long.
        assertEquals(333.6, asOnePath.lengthM, 0.5)
        assertEquals("the same track, drawn as the paths it is", 222.4, asPaths.lengthM, 0.5)
        assertEquals(1, asOnePath.paths.size)
        assertEquals(2, asPaths.paths.size)
        assertEquals(
            "and the difference is the spur, counted twice when it is not its own path",
            111.2,
            asOnePath.lengthM - asPaths.lengthM,
            0.5
        )
    }

    @Test
    fun `a place has a path of one point and no length`() {
        val geometry = AssetGeometry.of(listOf(origin))

        assertEquals(1, geometry.pointCount)
        assertEquals(0.0, geometry.lengthM, 0.0)
        assertFalse("a place is not a line", geometry.isLine)
    }

    @Test
    fun `nothing drawn is no paths at all`() {
        val geometry = AssetGeometry.of(emptyList())

        assertEquals(AssetGeometry.NONE, geometry)
        assertEquals(0, geometry.pointCount)
        assertEquals(0.0, geometry.lengthM, 0.0)
        assertFalse(geometry.hasSideTracks)
    }

    @Test
    fun `an empty side track is not carried, because there is nothing in it to draw`() {
        val geometry = AssetGeometry.of(listOf(origin, east), listOf(emptyList()))

        assertEquals(1, geometry.paths.size)
        assertFalse(geometry.hasSideTracks)
    }
}
