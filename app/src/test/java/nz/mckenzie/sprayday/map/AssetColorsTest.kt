package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours the map and the list draw with.
 *
 * Two vocabularies share this file: the traffic light says when something is due, and
 * the kind colours say what it is. They must not overlap - an icon the operator could
 * read as "overdue" would be worse than no icon at all - and every kind must have a
 * colour of its own, or two kinds would be indistinguishable wherever icons are small.
 */
class AssetColorsTest {

    @Test
    fun `no kind colour is a due colour`() {
        val dueColours = DueStatus.entries.map { AssetColors.forStatus(it) }.toSet()

        AssetKind.entries.forEach { kind ->
            assertTrue(
                "$kind shares a colour with the traffic light",
                AssetColors.forKind(kind) !in dueColours
            )
        }
    }

    @Test
    fun `every kind has its own colour`() {
        val colours = AssetKind.entries.map { AssetColors.forKind(it) }

        assertEquals(colours.size, colours.toSet().size)
    }

    @Test
    fun `the traffic light still says what it always said`() {
        assertEquals(AssetColors.GREEN, AssetColors.forStatus(DueStatus.NOT_DUE))
        assertEquals(AssetColors.YELLOW, AssetColors.forStatus(DueStatus.DUE_SOON))
        assertEquals(AssetColors.RED, AssetColors.forStatus(DueStatus.OVERDUE))
        assertEquals(AssetColors.RED, AssetColors.forStatus(DueStatus.NEVER_SPRAYED))
    }
}
