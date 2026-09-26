package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.domain.asset.AssetGrouping
import nz.mckenzie.sprayday.domain.asset.GroupTotals
import nz.mckenzie.sprayday.domain.asset.GroupableAsset
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.ui.formatArea
import nz.mckenzie.sprayday.ui.formatDistance

/**
 * One line of the asset list: either a block's tile, or an asset.
 *
 * A flat list of these rather than a tree, because that is what the screen draws - which is
 * also what lets a block's tile stay where it is while the assets under it come and go.
 */
internal sealed interface AssetListRow {
    /** Stable across reordering, so a row animates rather than being rebuilt as another. */
    val key: String

    /** A block, collapsed or open. */
    data class Block(
        val name: String,
        val totals: GroupTotals,
        val expanded: Boolean
    ) : AssetListRow {
        override val key: String get() = "block:${name.lowercase()}"
    }

    /** One asset: inside an open block, or on its own. */
    data class Asset(val item: AssetWithDue) : AssetListRow {
        override val key: String get() = "asset:${item.asset.id}"
    }
}

/**
 * The asset list as blocks and loose assets.
 *
 * Blocks come first, most urgent first, then by name; assets that are in no block follow in
 * the order the database gives them, which is by name. The order is a decision rather than
 * an accident: the list is read for what needs going over, and a block's tile is the one
 * thing on it that says that about more than one asset. Loose assets are the leftovers, and
 * putting them last keeps them out of the way of the blocks without hiding them.
 *
 * Blocks are closed to begin with. Opening one is what the operator does when they want the
 * rows; until then the tile is the answer, which is the whole point of it carrying totals.
 */
internal object AssetListRows {

    fun build(assets: List<AssetWithDue>, expanded: Set<String>): List<AssetListRow> {
        // Compared without case, on both sides, because the database makes names unique that
        // way: two spellings of one block can only arrive from something having gone wrong, and
        // that is exactly when a list should not quietly draw the same block twice - the row
        // keys would collide, and a LazyColumn is entitled to refuse them.
        val open = expanded.map { it.lowercase() }.toSet()
        val members = linkedMapOf<String, MutableList<AssetWithDue>>()
        val names = linkedMapOf<String, String>()
        val loose = mutableListOf<AssetWithDue>()

        assets.forEach { item ->
            val block = item.groupName?.trim()?.takeIf { it.isNotEmpty() }
            if (block == null) {
                loose += item
            } else {
                val key = block.lowercase()
                names.putIfAbsent(key, block)
                members.getOrPut(key) { mutableListOf() } += item
            }
        }

        val blocks = members.map { (key, list) ->
            Block(key = key, name = names.getValue(key), totals = AssetGrouping.totals(list.map(::asGroupable)))
        }.sortedWith(
            compareByDescending<Block> { AssetGrouping.urgency(it.totals.status) }
                .thenBy { it.name.lowercase() }
        )

        val rows = mutableListOf<AssetListRow>()
        blocks.forEach { block ->
            rows += AssetListRow.Block(
                name = block.name,
                totals = block.totals,
                expanded = block.key in open
            )
            if (block.key in open) rows += members.getValue(block.key).map { AssetListRow.Asset(it) }
        }
        rows += loose.map { AssetListRow.Asset(it) }
        return rows
    }

    /** One block mid-fold: its key, the spelling to show, and what it adds up to. */
    private data class Block(val key: String, val name: String, val totals: GroupTotals)

    private fun asGroupable(item: AssetWithDue) = GroupableAsset(
        status = item.due.status,
        lengthM = item.asset.lengthM,
        swathWidthM = item.asset.swathWidthM,
        passesRequired = item.asset.passesRequired,
        groundSqm = item.asset.groundSqm
    )
}

/**
 * What a block is, on the collapsed tile: how many assets, how much line, how much area.
 *
 * Every part is left out when it is not known rather than printed as a zero. "0 m" on a
 * block of troughs reads as a measurement of something, and a block of spots genuinely has
 * no length - the same reasoning the asset rows already carry.
 */
internal fun blockSizeLine(totals: GroupTotals): String {
    val parts = mutableListOf("${totals.assetCount} asset" + if (totals.assetCount == 1) "" else "s")
    if (totals.lengthM > 0.0) parts += formatDistance(totals.lengthM)
    if (totals.areaSqm > 0.0) {
        // Said as an estimate, and said to be partial when some lines have no swath width:
        // a number quietly covering three of five assets is the kind of figure that ends up
        // in a spray diary as though it were surveyed.
        val partial = totals.areaAssetCount < totals.assetCount
        parts += "about ${formatArea(totals.areaSqm)}" + if (partial) " from ${totals.areaAssetCount} of them" else ""
    }
    return parts.joinToString(" \u00b7 ")
}

/**
 * What is left to do in a block, in the operator's words.
 *
 * The count is always given even when the percentage says it too: "0% left" and "all done"
 * are the same figure and not the same statement, and which one a block is in is exactly
 * what somebody scanning the list is looking for.
 */
internal fun blockWorkLine(totals: GroupTotals): String {
    if (totals.isAllDone) return "Nothing left to spray"
    val counted = "${totals.leftCount} of ${totals.assetCount} left to spray (${totals.leftPercent}%)"
    return if (totals.leftLengthM > 0.0) "$counted \u00b7 ${formatDistance(totals.leftLengthM)}" else counted
}

/** Operator wording for a block's worst state, so its colour is never a mystery. */
internal fun blockStatusLine(totals: GroupTotals): String = when (totals.status) {
    DueStatus.NEVER_SPRAYED, DueStatus.OVERDUE -> "Overdue"
    DueStatus.DUE_SOON -> "Due soon"
    DueStatus.NOT_DUE -> "Not due"
}
