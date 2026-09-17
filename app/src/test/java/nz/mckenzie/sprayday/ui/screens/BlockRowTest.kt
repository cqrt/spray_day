package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.domain.asset.AssetGrouping
import nz.mckenzie.sprayday.domain.asset.GroupableAsset
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.viewmodel.BlockRow
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What a block's row says on the screen that keeps blocks tidy.
 *
 * Two cases only, and the second is the reason the function exists: a block that nobody is in
 * any more is a leftover rather than a mistake, and a row that read "0 assets · nothing left to
 * spray" would look like a sum rather than like something to clear up.
 */
class BlockRowTest {

    private fun row(assets: List<GroupableAsset>, notes: String? = null) = BlockRow(
        id = 1L,
        name = "Estuary",
        notes = notes,
        totals = AssetGrouping.totals(assets)
    )

    private fun line(status: DueStatus, lengthM: Double = 1000.0) =
        GroupableAsset(status = status, lengthM = lengthM, swathWidthM = 3.0)

    @Test
    fun `a block with assets says what it is and what is left`() {
        val lines = blockListLines(
            row(listOf(line(DueStatus.OVERDUE), line(DueStatus.NOT_DUE)))
        )

        assertEquals(2, lines.size)
        assertEquals("2 assets \u00b7 2.00 km \u00b7 about 6000 m\u00b2", lines[0])
        assertEquals("1 of 2 left to spray (50%) \u00b7 1.00 km", lines[1])
    }

    @Test
    fun `a block with nothing in it says so, rather than reading as a sum`() {
        assertEquals(listOf("No assets in it yet"), blockListLines(row(emptyList())))
    }
}
