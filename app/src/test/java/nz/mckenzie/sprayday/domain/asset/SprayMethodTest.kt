package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The width a spray method starts from.
 *
 * These are starting points, not rules: the operator's boom adjusts, so the number
 * only has to be sensible - and "not recorded" must offer nothing at all rather than
 * a made-up width that would end up in a treated-area figure.
 */
class SprayMethodTest {

    @Test
    fun `each way of spraying offers its usual width`() {
        assertEquals(3.0, SprayMethod.BOOM.defaultSwathM!!, 1e-9)
        assertEquals(1.0, SprayMethod.KNAPSACK.defaultSwathM!!, 1e-9)
    }

    @Test
    fun `a method nobody has recorded offers no width`() {
        assertNull(SprayMethod.UNSET.defaultSwathM)
    }

    @Test
    fun `every method either has a width or is the unrecorded one`() {
        val withoutAWidth = SprayMethod.entries.filter { it.defaultSwathM == null }
        assertEquals(listOf(SprayMethod.UNSET), withoutAWidth)
    }
}
