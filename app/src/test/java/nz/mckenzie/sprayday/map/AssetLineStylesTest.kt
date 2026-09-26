package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
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
    fun `a fenceline is dotted, with a speck far shorter than its gap`() {
        val dotted = AssetLineStyles.forKind(AssetKind.FENCELINE)!!

        assertTrue(
            "a dot has to be far shorter than the gap after it: ${dotted.toList()}",
            dotted[0] < dotted[1] / 10f
        )
    }

    @Test
    fun `the three lines are told apart by their dashes, and a place has no dash at all`() {
        val lines = AssetKind.entries.filter { it.shape == AssetShape.LINE }
            .map { AssetLineStyles.forKind(it)?.toList() }

        assertTrue(
            "two lines sharing a dash would be indistinguishable: $lines",
            lines.toSet().size == 3
        )
        AssetKind.entries.filter { it.shape == AssetShape.POINT }.forEach { place ->
            assertNull("$place is a house, not a line", AssetLineStyles.forKind(place))
        }
    }

    @Test
    fun `a carpark's boundary is solid, because a boundary is not a dash pattern`() {
        assertNull(AssetLineStyles.forKind(AssetKind.CARPARK))
        // And the three that *are* told apart by their dashes are still three: ground with an edge is
        // not a fourth line, it is a shape drawn by a layer of its own.
        assertEquals(
            3,
            AssetKind.entries.filter { it.shape == AssetShape.LINE }.size
        )
    }
}
