package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How the asset list folds into blocks, and what a collapsed block says.
 *
 * The order is tested as well as the arithmetic, because the order is the feature: the block
 * that needs going over is the one at the top, and the leftovers are out of its way.
 */
class AssetListRowsTest {

    private var nextId = 1L

    private fun asset(
        name: String,
        block: String? = null,
        status: DueStatus = DueStatus.NOT_DUE,
        lengthM: Double = 1000.0,
        swathWidthM: Double? = 3.0
    ) = AssetWithDue(
        asset = AssetEntity(
            id = nextId++,
            name = name,
            createdAtEpochMs = 0L,
            lengthM = lengthM,
            swathWidthM = swathWidthM
        ),
        due = DueInfo(status = status, dueDateEpochMs = null, daysUntilDue = 10L),
        sprayCount = 0,
        groupName = block
    )

    @Test
    fun `assets in one block fold into a single tile, closed to begin with`() {
        val rows = AssetListRows.build(
            listOf(
                asset("Estuary road", block = "Estuary"),
                asset("Estuary lagoon", block = "Estuary"),
                asset("Lone track")
            ),
            expanded = emptySet()
        )

        assertEquals(2, rows.size)
        val tile = rows[0] as AssetListRow.Block
        assertEquals("Estuary", tile.name)
        assertEquals("the block's own rows wait until it is opened", 2, tile.totals.assetCount)
        assertTrue(!tile.expanded)
        assertEquals("Lone track", (rows[1] as AssetListRow.Asset).item.asset.name)
    }

    @Test
    fun `opening a block puts its assets under it`() {
        val rows = AssetListRows.build(
            listOf(
                asset("Estuary road", block = "Estuary"),
                asset("Estuary lagoon", block = "Estuary"),
                asset("Lone track")
            ),
            expanded = setOf("Estuary")
        )

        assertEquals(4, rows.size)
        assertTrue((rows[0] as AssetListRow.Block).expanded)
        assertEquals(
            listOf("Estuary road", "Estuary lagoon", "Lone track"),
            rows.drop(1).map { (it as AssetListRow.Asset).item.asset.name }
        )
    }

    @Test
    fun `the block that needs going over comes first, then blocks by name`() {
        val rows = AssetListRows.build(
            listOf(
                asset("B block", block = "Braemar", status = DueStatus.NOT_DUE),
                asset("A block", block = "Awatere", status = DueStatus.OVERDUE),
                asset("C block", block = "Clarence", status = DueStatus.DUE_SOON)
            ),
            expanded = emptySet()
        )

        assertEquals(
            listOf("Awatere", "Clarence", "Braemar"),
            rows.map { (it as AssetListRow.Block).name }
        )
    }

    @Test
    fun `loose assets come after every block, in the order they were given`() {
        val rows = AssetListRows.build(
            listOf(
                asset("Zulu track"),
                asset("In a block", block = "Estuary"),
                asset("Alpha track")
            ),
            expanded = emptySet()
        )

        assertEquals(3, rows.size)
        assertTrue(rows[0] is AssetListRow.Block)
        assertEquals(
            listOf("Zulu track", "Alpha track"),
            rows.drop(1).map { (it as AssetListRow.Asset).item.asset.name }
        )
    }

    @Test
    fun `an asset with a blank block name is on its own`() {
        val rows = AssetListRows.build(
            listOf(asset("Nowhere", block = "   "), asset("Empty", block = "")),
            expanded = emptySet()
        )

        assertEquals(2, rows.size)
        assertTrue(rows.all { it is AssetListRow.Asset })
    }

    @Test
    fun `two spellings of one block are drawn once, with one key`() {
        val rows = AssetListRows.build(
            listOf(asset("One", block = "Estuary"), asset("Two", block = "estuary")),
            expanded = emptySet()
        )

        assertEquals(1, rows.size)
        val tile = rows[0] as AssetListRow.Block
        assertEquals("Estuary", tile.name)
        assertEquals(2, tile.totals.assetCount)
    }

    @Test
    fun `a closed block is opened by name whatever the case`() {
        val rows = AssetListRows.build(
            listOf(asset("One", block = "Estuary")),
            expanded = setOf("estuary")
        )

        assertTrue((rows[0] as AssetListRow.Block).expanded)
        assertEquals(2, rows.size)
    }

    @Test
    fun `a block's tile says what it is`() {
        val rows = AssetListRows.build(
            listOf(
                asset("One", block = "Estuary"),
                asset("Two", block = "Estuary"),
                asset("Three", block = "Estuary"),
                asset("Four", block = "Estuary")
            ),
            expanded = emptySet()
        )

        assertEquals(
            "4 assets \u00b7 4.00 km \u00b7 about 1.2 ha",
            blockSizeLine((rows[0] as AssetListRow.Block).totals)
        )
    }

    @Test
    fun `a partial area says how partial it is`() {
        val rows = AssetListRows.build(
            listOf(
                asset("One", block = "Estuary"),
                asset("Two", block = "Estuary", swathWidthM = null),
                asset("Three", block = "Estuary", swathWidthM = null),
                asset("Four", block = "Estuary", swathWidthM = null)
            ),
            expanded = emptySet()
        )

        val line = blockSizeLine((rows[0] as AssetListRow.Block).totals)
        assertTrue("the area is not claimed for all four: $line", line.contains("from 1 of them"))
    }

    @Test
    fun `a block of spots is described without a length`() {
        val rows = AssetListRows.build(
            listOf(
                asset("Trough", block = "Yards", lengthM = 0.0, swathWidthM = null),
                asset("Gate", block = "Yards", lengthM = 0.0, swathWidthM = null)
            ),
            expanded = emptySet()
        )

        assertEquals("2 assets", blockSizeLine((rows[0] as AssetListRow.Block).totals))
    }

    @Test
    fun `one asset is one asset, in the singular`() {
        val rows = AssetListRows.build(listOf(asset("Alone", block = "Estuary")), expanded = emptySet())

        assertTrue(blockSizeLine((rows[0] as AssetListRow.Block).totals).startsWith("1 asset \u00b7"))
    }

    @Test
    fun `the work line counts assets and shows the distance left`() {
        val rows = AssetListRows.build(
            listOf(
                asset("One", block = "Estuary", status = DueStatus.OVERDUE),
                asset("Two", block = "Estuary", status = DueStatus.DUE_SOON),
                asset("Three", block = "Estuary"),
                asset("Four", block = "Estuary")
            ),
            expanded = emptySet()
        )

        assertEquals(
            "2 of 4 left to spray (50%) \u00b7 2.00 km",
            blockWorkLine((rows[0] as AssetListRow.Block).totals)
        )
    }

    @Test
    fun `the work line leaves the distance out when there is none`() {
        val rows = AssetListRows.build(
            listOf(
                asset("Trough", block = "Yards", status = DueStatus.DUE_SOON, lengthM = 0.0, swathWidthM = null),
                asset("Gate", block = "Yards", lengthM = 0.0, swathWidthM = null)
            ),
            expanded = emptySet()
        )

        assertEquals("1 of 2 left to spray (50%)", blockWorkLine((rows[0] as AssetListRow.Block).totals))
    }

    @Test
    fun `a block with nothing left says so`() {
        val rows = AssetListRows.build(
            listOf(asset("One", block = "Estuary"), asset("Two", block = "Estuary")),
            expanded = emptySet()
        )

        assertEquals("Nothing left to spray", blockWorkLine((rows[0] as AssetListRow.Block).totals))
    }

    @Test
    fun `a block's status is said in the operator's words`() {
        val rows = AssetListRows.build(
            listOf(
                asset("Spotty", block = "Yards", status = DueStatus.NEVER_SPRAYED),
                asset("Soon", block = "Yards", status = DueStatus.DUE_SOON)
            ),
            expanded = emptySet()
        )

        assertEquals("Overdue", blockStatusLine((rows[0] as AssetListRow.Block).totals))
    }

    @Test
    fun `every row has a key of its own`() {
        val rows = AssetListRows.build(
            listOf(
                asset("One", block = "Estuary"),
                asset("Two", block = "Estuary"),
                asset("Loose")
            ),
            expanded = setOf("Estuary")
        )

        assertEquals(rows.size, rows.map { it.key }.toSet().size)
    }
}
