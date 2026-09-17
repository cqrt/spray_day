package nz.mckenzie.sprayday.ui

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules for renaming a block and writing its notes.
 *
 * The one that matters is the collision. The name column is unique without case, so a rename
 * onto another block's name has to come back as a sentence naming the block that is in the way,
 * rather than as a constraint failure from underneath. And a block's own name is not in its own
 * way: fixing the spelling or the case of a name is the commonest rename there is.
 */
class BlockEditsTest {

    private val others = listOf("Estuary", "Braemar")

    @Test
    fun `a block can be renamed, and keeps its notes`() {
        val result = BlockEdits.apply(
            current = "Estuary",
            fields = BlockEditFields(name = "  Estuary flats ", notes = " Road and lagoon "),
            otherBlocks = others
        )

        assertEquals(BlockEditResult.Ok("Estuary flats", "Road and lagoon"), result)
    }

    @Test
    fun `typing its own name again is not a clash with itself`() {
        val result = BlockEdits.apply(
            current = "Estuary",
            fields = BlockEditFields(name = "estuary", notes = ""),
            otherBlocks = others
        )

        assertEquals(BlockEditResult.Ok("estuary", null), result)
    }

    @Test
    fun `another block's name is refused, whatever case it is typed in`() {
        val result = BlockEdits.apply(
            current = "Estuary",
            fields = BlockEditFields(name = "braemar", notes = ""),
            otherBlocks = others
        )

        assertTrue("should be refused: $result", result is BlockEditResult.Invalid)
        assertTrue(
            "the sentence names the block in the way",
            (result as BlockEditResult.Invalid).message.contains("Braemar")
        )
    }

    @Test
    fun `a blank name is refused, and says what to do instead`() {
        val result = BlockEdits.apply(
            current = "Estuary",
            fields = BlockEditFields(name = "   ", notes = ""),
            otherBlocks = others
        )

        assertTrue("should be refused: $result", result is BlockEditResult.Invalid)
        assertTrue(
            "deleting is the honest alternative to an unnamed block",
            (result as BlockEditResult.Invalid).message.contains("delete it")
        )
    }

    @Test
    fun `blank notes are stored as nothing rather than as an empty line`() {
        val result = BlockEdits.apply(
            current = "Estuary",
            fields = BlockEditFields(name = "Estuary", notes = "   "),
            otherBlocks = others
        )

        assertEquals(BlockEditResult.Ok("Estuary", null), result)
    }

    @Test
    fun `the delete sentence says how many assets stay where they are`() {
        val several = BlockEdits.deleteMessage("Estuary", 3)
        val one = BlockEdits.deleteMessage("Estuary", 1)
        val empty = BlockEdits.deleteMessage("Estuary", 0)

        assertTrue(several, several.contains("The 3 assets in \"Estuary\" stay where they are"))
        assertTrue(one, one.contains("The asset in \"Estuary\" stays where it is"))
        assertTrue(empty, empty.contains("holds no assets"))
        assertTrue(several, several.endsWith("this cannot be undone."))
    }
}
