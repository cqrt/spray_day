package nz.mckenzie.sprayday.data.db

import nz.mckenzie.sprayday.domain.asset.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The ground a row says it has.
 *
 * One rule in one place, because several callers read it - the card, a block's tile, a recorded spray -
 * and a caller that worked it out its own way is how a line ends up claiming ground.
 */
class AssetGroundTest {

    private fun asset(kind: AssetKind, areaM2: Double) = AssetEntity(
        name = "Test",
        kind = kind.name,
        shape = kind.shape.name,
        areaM2 = areaM2,
        createdAtEpochMs = 0L
    )

    @Test
    fun `a carpark's ground is the area measured off its corners`() {
        assertEquals(3_500.0, asset(AssetKind.CARPARK, 3_500.0).groundSqm!!, 1e-9)
    }

    @Test
    fun `a line and a place have no ground at all`() {
        assertNull("a track encloses nothing", asset(AssetKind.TRACK, 0.0).groundSqm)
        assertNull("nor does a sign", asset(AssetKind.SIGN, 0.0).groundSqm)
    }

    @Test
    fun `a carpark that has not been measured says nothing rather than nothing much`() {
        assertNull(
            "a zero beside a real area is the sort of figure that gets added up and believed",
            asset(AssetKind.CARPARK, 0.0).groundSqm
        )
    }
}
