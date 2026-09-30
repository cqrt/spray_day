package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.domain.asset.GroupableAsset
import nz.mckenzie.sprayday.domain.asset.GroupTotals
import nz.mckenzie.sprayday.ui.formatArea
import nz.mckenzie.sprayday.ui.formatDistance

/**
 * One asset as the farm's arithmetic sees it.
 *
 * The mapping is written once rather than once per screen, because it is a decision: what a farm
 * comes to is the length of its lines, the ground and the estimates that make its area, and the
 * work still left - and a second copy of that mapping is a second chance to leave a field out of
 * it, which is how a screen quietly stops agreeing with the block tiles about what a farm is.
 */
internal fun AssetWithDue.asGroupable(): GroupableAsset = GroupableAsset(
    status = due.status,
    lengthM = asset.lengthM,
    swathWidthM = asset.swathWidthM,
    passesRequired = asset.passesRequired,
    groundSqm = asset.groundSqm
)

/**
 * What the whole farm comes to, in the words the map's corner box says it in.
 *
 * The box has always answered "how much, and how urgent"; these lines add "how big", worked out by
 * the same arithmetic a block's tile uses so the two cannot give different answers to one question.
 * Every line is left out when it is not known rather than shown as a zero - a farm of places has no
 * length at all, and an area covering only some of the assets says so, because a figure quietly
 * covering half the farm is the kind that ends up in a spray diary as though somebody had surveyed it.
 */
internal fun farmStatsLines(totals: GroupTotals): List<String> {
    val lines = mutableListOf<String>()
    if (totals.lengthM > 0.0) lines += "Total length ${formatDistance(totals.lengthM)}"
    if (totals.areaSqm > 0.0) {
        val partial = totals.areaAssetCount < totals.assetCount
        lines += "Total area about ${formatArea(totals.areaSqm)}" +
            (if (partial) " from ${totals.areaAssetCount} of them" else "")
    }
    // The counts above already say how much is left; this is the driving that still has to happen,
    // so it is said only when there is a distance to say.
    if (totals.leftLengthM > 0.0) lines += "Left to spray ${formatDistance(totals.leftLengthM)}"
    return lines
}
