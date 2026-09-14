package nz.mckenzie.sprayday.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

class QuantitiesTest {

    @Test
    fun `whole millilitres parse`() {
        assertEquals(1450.0, parseQuantityMl("1450")!!, 0.001)
        assertEquals(120.0, parseQuantityMl("  120  ")!!, 0.001)
    }

    @Test
    fun `decimal millilitres parse with either separator`() {
        assertEquals(120.5, parseQuantityMl("120.5")!!, 0.001)
        assertEquals(120.5, parseQuantityMl("120,5")!!, 0.001)
    }

    @Test
    fun `blank and invalid amounts are rejected rather than defaulted`() {
        assertNull(parseQuantityMl(""))
        assertNull(parseQuantityMl("   "))
        assertNull(parseQuantityMl("abc"))
        assertNull(parseQuantityMl("0"))
        assertNull(parseQuantityMl("-50"))
    }

    @Test
    fun `amounts drop a pointless decimal`() {
        assertEquals("1450", formatQuantityMl(1450.0))
        assertEquals("120.5", formatQuantityMl(120.5))
        // The same rule serves form fields, where "6.0" has to read back as "6".
        assertEquals("6", formatPlainNumber(6.0))
        assertEquals("4.5", formatPlainNumber(4.5))
    }

    @Test
    fun `litres are used once the amount is large`() {
        assertEquals("500 mL", formatQuantityWithUnit(500.0))
        assertEquals("999 mL", formatQuantityWithUnit(999.0))
        assertEquals("1.45 L", formatQuantityWithUnit(1450.0))
    }
}
