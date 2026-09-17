package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.estimatedAreaSqm

/**
 * One asset as the block arithmetic sees it: what it is worth to the block, and whether it
 * is still to be done.
 *
 * Not the whole asset, deliberately. What a block adds up to is length, area and work left,
 * and nothing else about an asset changes any of those three - so the arithmetic can be
 * tested without a database, a device or a screen.
 */
data class GroupableAsset(
    val status: DueStatus,
    val lengthM: Double,
    /** Null when nobody has said how wide the work is, which is why the area is partial. */
    val swathWidthM: Double?
) {
    /** Due, overdue or never done: this one still has to be driven. */
    val isLeft: Boolean get() = status != DueStatus.NOT_DUE
}

/**
 * What a block comes to, for the line under its name while it is collapsed.
 *
 * The tile exists so a block can be read at a glance without opening it: how big it is, and
 * how much of it is still to do. Everything here is a total of something already on the
 * asset, so there is nothing to keep in step with the rows below it.
 */
data class GroupTotals(
    val assetCount: Int,
    /** The lines' length added up. Spots have none, so this can be zero on a real block. */
    val lengthM: Double,
    /** Estimated treated area of the assets that know their swath width. */
    val areaSqm: Double,
    /** How many assets that area came from, so a partial estimate can say so. */
    val areaAssetCount: Int,
    /** Assets still to be sprayed: due, overdue, or never done. */
    val leftCount: Int,
    /** Their length, which is the distance actually left to drive. */
    val leftLengthM: Double,
    /** The most urgent state in the block. That, not an average, is what its colour is. */
    val status: DueStatus
) {
    /** Nothing is due in this block: everything in it has been done and is not up again. */
    val isAllDone: Boolean get() = leftCount == 0

    /**
     * The share of the block still to spray, as a whole percentage.
     *
     * Counted in assets rather than metres, and that is a decision worth the words. A block
     * of five lines of similar length reads the same either way, but a block of one long road
     * and one trough does not: by length, a block whose only due asset is a spot reports "0%
     * left" directly beside a line saying something is left, and a tile that contradicts
     * itself is worse than one that is merely coarse. The distance left is on the tile too,
     * so the metres are there to read for anyone who wants them.
     */
    val leftPercent: Int
        get() = if (assetCount == 0) 0 else (leftCount * 100 + assetCount / 2) / assetCount

    /** The same figure as a fraction, for the progress bar. */
    val leftFraction: Float get() = leftPercent / 100f
}

/**
 * The arithmetic behind a collapsed block.
 *
 * Kept apart from the screen and free of any database for the usual reason: what a block
 * comes to is the whole of this feature's judgement - how a partial area is reported, what
 * a block with no lines at all does, which status wins - and that is worth testing without
 * a device.
 */
object AssetGrouping {

    fun totals(assets: List<GroupableAsset>): GroupTotals {
        val lines = assets.filter { it.lengthM > 0.0 }
        val left = assets.filter { it.isLeft }
        val measured = assets.filter { it.swathWidthM != null && it.lengthM > 0.0 }

        return GroupTotals(
            assetCount = assets.size,
            lengthM = lines.sumOf { it.lengthM },
            areaSqm = measured.sumOf { asset -> estimatedAreaSqm(asset.lengthM, asset.swathWidthM ?: 0.0) },
            areaAssetCount = measured.size,
            leftCount = left.size,
            leftLengthM = left.filter { it.lengthM > 0.0 }.sumOf { it.lengthM },
            // No assets cannot happen from the list, but an empty block is not an overdue
            // one: it is nothing to say at all.
            status = assets.map { it.status }.maxByOrNull { urgency(it) } ?: DueStatus.NOT_DUE
        )
    }

    /**
     * How urgent a state is, higher being more urgent.
     *
     * A never-sprayed asset and an overdue one rank together, because they are the same
     * colour on the map for the same reason: both need going over. Splitting them here would
     * make a block's colour depend on which of two equally red things it happened to contain.
     */
    fun urgency(status: DueStatus): Int = when (status) {
        DueStatus.NEVER_SPRAYED, DueStatus.OVERDUE -> 3
        DueStatus.DUE_SOON -> 2
        DueStatus.NOT_DUE -> 1
    }
}
