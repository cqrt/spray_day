package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the asset list shows when a kind chip is tapped.
 *
 * The cases that matter are the ones where a filter is wrong in a way nobody can see: a row that
 * quietly disappears, and a track drawn before the kinds existed that answers to neither of the two
 * words it could be found under.
 */
class AssetKindFilterTest {

    @Test
    fun `everything is shown before a chip is tapped`() {
        assertTrue(AssetKindFilter.All.keeps("BUILDING", "POINT"))
        assertTrue(AssetKindFilter.All.keeps("TRACK", "LINE"))
        assertTrue("including a track drawn before the kinds existed", AssetKindFilter.All.keeps("INFRASTRUCTURE", "LINE"))
    }

    @Test
    fun `a kind shows its own rows and nothing else`() {
        val fencelines = AssetKindFilter(AssetKind.FENCELINE)

        assertTrue(fencelines.keeps("FENCELINE", "LINE"))
        assertFalse("a track is not a fenceline", fencelines.keeps("TRACK", "LINE"))
        assertFalse("and a place is not either", fencelines.keeps("BUILDING", "POINT"))
    }

    @Test
    fun `a track drawn before the kinds existed is found under what it is`() {
        // Stored as INFRASTRUCTURE by every build before v0.6.40, and read back by its shape: a line
        // is a fenceline or stopbank, a spot is some other place. The five spot kinds among
        // themselves cannot tell what such a spot was, which is why the phone reads only the shape.
        assertTrue(
            "a line reads as a fenceline",
            AssetKindFilter(AssetKind.FENCELINE).keeps("INFRASTRUCTURE", "LINE")
        )
        assertTrue(
            "and a spot reads as some other place",
            AssetKindFilter(AssetKind.OTHER_PLACE).keeps("INFRASTRUCTURE", "POINT")
        )
        assertFalse(
            "a line is not an other place",
            AssetKindFilter(AssetKind.OTHER_PLACE).keeps("INFRASTRUCTURE", "LINE")
        )
        assertFalse(
            "and a sign it never was",
            AssetKindFilter(AssetKind.SIGN).keeps("INFRASTRUCTURE", "POINT")
        )
    }

    @Test
    fun `tapping the chip already showing brings all of them back`() {
        val buildings = AssetKindFilter.afterTapping(AssetKind.BUILDING, AssetKindFilter.All)
        assertEquals("a chip tapped from All shows that kind", AssetKind.BUILDING, buildings.kind)

        assertEquals(
            "and the same chip tapped again is the way back out",
            AssetKindFilter.All,
            AssetKindFilter.afterTapping(AssetKind.BUILDING, buildings)
        )

        assertEquals(
            "another chip moves the filter rather than clearing it",
            AssetKind.SIGN,
            AssetKindFilter.afterTapping(AssetKind.SIGN, buildings).kind
        )
    }

    @Test
    fun `choosing all while a kind is showing clears it`() {
        val buildings = AssetKindFilter(AssetKind.BUILDING)

        assertEquals(AssetKindFilter.All, AssetKindFilter.afterTapping(null, buildings))
        assertEquals("and choosing it again changes nothing", AssetKindFilter.All, AssetKindFilter.afterTapping(null, AssetKindFilter.All))
    }
}
