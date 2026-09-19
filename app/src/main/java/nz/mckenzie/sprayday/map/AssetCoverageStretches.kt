package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.due.DueCalculator
import nz.mckenzie.sprayday.domain.geo.Coverage
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.RecordedPass
import nz.mckenzie.sprayday.domain.geo.TwoPasses
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
     *
     * [passesRequired] and [separationM] are the asset's own two-pass settings. A line sprayed
     * twice is coloured by when it was last *done* - both passes - so one pass over it leaves the
     * line as it was rather than turning it green: see [TwoPasses].
     */
    fun of(
        planned: List<GeoPoint>,
        passes: List<RecordedPass>,
        lastWithoutRecordingAtEpochMs: Long?,
        intervalDays: Int,
        leadDays: Int,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault(),
        toleranceM: Double = Coverage.DEFAULT_TOLERANCE_M,
        passesRequired: Int = 1,
        separationM: Double? = null
    ): List<AssetStretch> =
        dated(
            planned = planned,
            passes = passes,
            lastWithoutRecordingAtEpochMs = lastWithoutRecordingAtEpochMs,
            toleranceM = toleranceM,
            passesRequired = passesRequired,
            separationM = separationM
        ).map { stretch ->
            AssetStretch(
                colorHex = AssetColors.forStatus(
                    DueCalculator.calculate(
                        lastSprayedAtEpochMs = stretch.first,
                        intervalDays = intervalDays,
                        leadDays = leadDays,
                        nowEpochMs = nowEpochMs,
                        zoneId = zoneId
                    ).status
                ),
                points = stretch.second
            )
        }

    /**
     * The plan cut up, each stretch with the date that colours it.
     *
     * Two shapes of the same answer: a line sprayed once is dated by the pass that covered it, and
     * a line sprayed twice by the two passes that made it done. The two-pass reading is asked for
     * first when the asset says it needs two passes, and it falls back to the single-pass one
     * when there is no line to walk at all.
     */
    private fun dated(
        planned: List<GeoPoint>,
        passes: List<RecordedPass>,
        lastWithoutRecordingAtEpochMs: Long?,
        toleranceM: Double,
        passesRequired: Int,
        separationM: Double?
    ): List<Pair<Long?, List<GeoPoint>>> {
        if (passesRequired >= AssetEntity.TWO_PASSES_REQUIRED) {
            val twice = TwoPasses.split(
                planned = planned,
                passes = passes,
                handSprayedAtEpochMs = lastWithoutRecordingAtEpochMs,
                separationM = separationM,
                toleranceM = toleranceM
            )
            if (twice != null) {
                return twice.stretches.map { it.completedAtEpochMs to it.points }
            }
        }

        return Coverage.splitByCoverage(
            planned = planned,
            passes = passes,
            assetSprayedAtEpochMs = lastWithoutRecordingAtEpochMs,
            toleranceM = toleranceM
        ).map { it.lastSprayedAtEpochMs to it.points }
    }
}
