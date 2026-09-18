package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.RecordedPass
import java.time.ZoneId

/**
 * The coloured stretches a partly-sprayed asset is drawn as.
 *
 * The map colours an asset by when it was last sprayed, and that is the wrong answer for a
 * line that was only half done: the whole track goes green, including the half still waiting
 * for a tank. So each stretch of the planned line is coloured by *its own* last spray - the
 * pass whose fixes covered it, or a whole-asset spray with no recording behind it, whichever
 * is more recent - using the same interval and lead time the asset's own light uses.
 *
 * Three consequences worth knowing, because they are the whole behaviour:
 *
 * - **Two halves on two days is a sprayed line.** Each half keeps its own date, so the half
 *   done a fortnight ago is still green. Showing it red would be inventing a shortfall, and
 *   would train the operator to ignore the red.
 * - **A spray logged by hand covers the whole line**, because it has no fixes to disagree
 *   with and that is what logging it meant. Without that, every asset sprayed without a
 *   recording would turn red once this existed.
 * - **Evidence beats the claim.** If a pass was recorded but the phone never came within
 *   tolerance of the line, the line reads as still to spray - which is what the recording's
 *   own detail screen says about it too. The alternative is a map that goes green because a
 *   spray was typed in, which is the report this came from.
 *
 * Nothing here is stored. The stretches are recomputed from the plan against the recordings
 * every time the map is drawn, so a line that has been edited since is cut up as it is now,
 * exactly as the coverage percentage is.
 */
object AssetCoverageStretches {

    /**
     * The stretches to draw, or an empty list when the line is drawn in one colour.
     *
     * [passes] are the recorded passes that could still matter, [lastWithoutRecordingAtEpochMs]
     * the last spray with no recording behind it, and [nowEpochMs] the clock the colours are
     * worked out against - the same one the asset's own traffic light uses.
     */
    fun of(
        planned: List<GeoPoint>,
        passes: List<RecordedPass>,
        lastWithoutRecordingAtEpochMs: Long?,
        intervalDays: Int,
        leadDays: Int,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        toleranceM: Double = Coverage.DEFAULT_TOLERANCE_M
    ): List<AssetStretch> =
        Coverage.splitByCoverage(
            planned = planned,
            passes = passes,
            assetSprayedAtEpochMs = lastWithoutRecordingAtEpochMs,
            toleranceM = toleranceM
        ).map { stretch ->
            AssetStretch(
                colorHex = AssetColors.forStatus(
                    DueCalculator.calculate(
                        lastSprayedAtEpochMs = stretch.lastSprayedAtEpochMs,
                        intervalDays = intervalDays,
                        leadDays = leadDays,
                        nowEpochMs = nowEpochMs,
                        zoneId = zoneId
                    ).status
                ),
                points = stretch.points
            )
        }
}
