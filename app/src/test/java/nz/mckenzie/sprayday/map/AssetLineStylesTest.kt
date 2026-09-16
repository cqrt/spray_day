package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which kind of asset is drawn how.
 *
 * A dash pattern is in multiples of the line width, so these are shapes rather than
 * measurements: a solid line, a dash with a gap, and a dot with a gap. What matters,
 * and what is asserted here, is that the three are recognisably different - two kinds
 * drawn the same way would make the map say less than it claims to.
 */
class AssetLineStylesTest {

    @Test
    fun `a track is drawn solid`() {
        assertNull(AssetLineStyles.forKind(AssetKind.TRACK))
    }

    @Test
    fun `a road is dashed, with a dash longer than its gap`() {
        val road = AssetLineStyles.forKind(AssetKind.ROAD)!!

        assertTrue("a road's dash should read as a dash: ${road.toList()}", road[0] > road[1])
    }

    @Test
    fun `infrastructure is dotted, with a speck far shorter than its gap`() {
        val dotted = AssetLineStyles.forKind(AssetKind.INFRASTRUCTURE)!!

        assertTrue(
            "a dot has to be far shorter than the gap after it: ${dotted.toList()}",
            dotted[0] < dotted[1] / 10f
        )
    }

    @Test
    fun `a fenceline is a dash and a dot, so it is neither a road nor dotted`() {
        val fence = AssetLineStyles.forKind(AssetKind.FENCELINE)!!

        assertEquals("a dash, a gap, a dot, a gap", 4, fence.size)
        assertTrue("it should carry a dash: ${fence.toList()}", fence.any { it > 1f })
        assertTrue("and a dot: ${fence.toList()}", fence.any { it < 0.1f })
    }

    @Test
    fun `no two kinds are drawn the same way`() {
        val patterns = AssetKind.entries.map { AssetLineStyles.forKind(it)?.toList() }

        assertEquals(
            "two kinds sharing a pattern would be indistinguishable",
            AssetKind.entries.size,
            patterns.toSet().size
        )
    }
}
