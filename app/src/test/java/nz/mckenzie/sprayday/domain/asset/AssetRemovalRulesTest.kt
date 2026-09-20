package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * When a computer may take a track away, and what it says first.
 *
 * The one thing being held here is that the desk never deletes something a season's record hangs off.
 * The plan said the desk should archive instead of deleting (`active = false`); the app has no screen
 * that shows an archived asset and no way to bring one back, so that would be a quiet removal - see
 * [AssetRemovalRules]. What is left is this: nothing attached, delete it; anything attached, say how
 * much and send the operator to the phone.
 */
class AssetRemovalRulesTest {

    @Test
    fun `a track with nothing recorded against it may be deleted, and the sentence says what goes`() {
        val removal = AssetRemovalRules.of("Estuary road", sprays = 0, recordings = 0)

        assertTrue(removal.allowed)
        assertEquals(
            "Nothing is recorded against \"Estuary road\", so deleting it here takes nothing else with it.",
            removal.sentence
        )
    }

    @Test
    fun `a track with sprays on it is refused, and how many is in the sentence`() {
        val removal = AssetRemovalRules.of("Estuary road", sprays = 3, recordings = 0)

        assertFalse(removal.allowed)
        assertEquals(
            "\"Estuary road\" has 3 sprays on the phone, so it is not deleted from here. " +
                "Delete it on the phone, where what goes with it can be seen first.",
            removal.sentence
        )
    }

    @Test
    fun `a recording alone is enough to refuse, because the link is the only thing holding it`() {
        val removal = AssetRemovalRules.of("Old track", sprays = 0, recordings = 1)

        assertFalse(removal.allowed)
        assertTrue("says what is on it: ${removal.sentence}", removal.sentence.contains("1 recording"))
    }

    @Test
    fun `both kinds of thing are counted, and one of each is said in the singular`() {
        assertTrue(
            AssetRemovalRules.of("Estuary road", sprays = 1, recordings = 1)
                .sentence.contains("1 spray and 1 recording")
        )
        assertTrue(
            AssetRemovalRules.of("Estuary road", sprays = 3, recordings = 2)
                .sentence.contains("3 sprays and 2 recordings")
        )
    }

    @Test
    fun `every refusal names the phone, because that is where the delete is done`() {
        listOf(0 to 1, 1 to 0, 4 to 9).forEach { (sprays, recordings) ->
            val removal = AssetRemovalRules.of("Estuary road", sprays, recordings)

            assertFalse(removal.allowed)
            assertTrue(
                "a refusal has to say where to go: ${removal.sentence}",
                removal.sentence.contains("Delete it on the phone")
            )
        }
    }
}
