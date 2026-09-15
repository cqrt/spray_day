package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * How a spray method is spoken about, and what a stored value means.
 *
 * Two wordings, on purpose: a record says nothing when nobody has said how the work
 * was done, while the picker has to offer "not recorded" as something the operator can
 * choose. Getting those two the wrong way round would either put "Not recorded" in a
 * client's spreadsheet or leave a chip with no label.
 */
class MethodPhraseTest {

    @Test
    fun `a record names the method, and stays silent when there is none`() {
        assertEquals("Boom", MethodPhrase.of(SprayMethod.BOOM))
        assertEquals("Knapsack", MethodPhrase.of(SprayMethod.KNAPSACK))
        assertEquals("", MethodPhrase.of(SprayMethod.UNSET))
    }

    @Test
    fun `the picker can show not recorded, because it is a choice`() {
        assertEquals("Not recorded", MethodPhrase.choice(SprayMethod.UNSET))
        assertEquals("Boom", MethodPhrase.choice(SprayMethod.BOOM))
        assertEquals("Knapsack", MethodPhrase.choice(SprayMethod.KNAPSACK))
    }

    @Test
    fun `every method is offered, and nothing else is`() {
        assertEquals(SprayMethod.entries.toList(), MethodPhrase.choices)
    }

    @Test
    fun `a stored value is read back as itself`() {
        assertEquals(SprayMethod.BOOM, SprayMethod.fromStorage("BOOM"))
        assertEquals(SprayMethod.KNAPSACK, SprayMethod.fromStorage("KNAPSACK"))
        assertEquals(SprayMethod.UNSET, SprayMethod.fromStorage("UNSET"))
    }

    @Test
    fun `a value this build does not know reads as not recorded rather than throwing`() {
        // A database edited by hand, or a method named by a later build. The one thing
        // certainly true is that nobody in this build recorded how the work was done.
        assertEquals(SprayMethod.UNSET, SprayMethod.fromStorage(null))
        assertEquals(SprayMethod.UNSET, SprayMethod.fromStorage(""))
        assertEquals(SprayMethod.UNSET, SprayMethod.fromStorage("AIRBLADE"))
        assertEquals(SprayMethod.UNSET, SprayMethod.fromStorage("boom"))
    }
}
