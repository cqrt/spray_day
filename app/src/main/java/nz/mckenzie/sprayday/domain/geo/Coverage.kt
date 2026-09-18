package nz.mckenzie.sprayday.domain.geo

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/**
 * One pass over a planned line that could account for part of it: when it happened, and
 * the fixes that prove it.
 *
 * A pass with no fixes proves nothing, and is ignored wherever one of these is read.
 */
data class RecordedPass(
    val atEpochMs: Long,
    val points: List<GeoPoint>
)

/**
 * A stretch of a planned line sharing one answer to *when was this last sprayed?*
 *
 * This is what lets the map draw a half-sprayed track as half done. The answer has to be
 * per stretch rather than per asset, because "I sprayed this line today" is true of the
 * part that was driven and false of the part that was not - and it is the second part the
 * operator is looking for when they open the map.
 */
data class CoverageStretch(
    /** The planned geometry of this stretch, cut exactly at its boundaries. */
    val points: List<GeoPoint>,
    /** The most recent spray of it, or null when nothing has ever covered it. */
    val lastSprayedAtEpochMs: Long?,
    val lengthM: Double
)

/**
 * How much of a planned track a recorded run actually covered - and, for the map, which
 * parts of it.
 *
 * This is the question an operator asks after a pass: *did I get the whole line?*
 * The answer is computed by walking the planned geometry and asking whether the recording
 * came within a tolerance of each part of it - so a section driven twice does not inflate
 * the number, and a section missed entirely shows up as a shortfall straight away.
 */
object Coverage {

    /**
     * How close a pass has to be to count. Tracks are sprayed from a vehicle
     * following the line, so a few metres of GPS wander and driving either side is
     * normal; 12 m covers that without pretending a parallel run 30 m away counts.
     */
    const val DEFAULT_TOLERANCE_M: Double = 12.0

    /**
     * Fraction (0..1) of [planned]'s length that passes within [toleranceM] of the
     * recorded line, weighted by length.
     *
     * The planned line is walked in steps of about half the tolerance and each step
     * is counted if the recording passes within tolerance of its midpoint. Weighting
     * is what makes the number mean "how much of the track", not "how many of my
     * samples": a long straight stretch counts for more than a short kink, and half
     * of a segment reports as half.
     */
    fun coveredFraction(
        planned: List<GeoPoint>,
        recorded: List<GeoPoint>,
        toleranceM: Double = DEFAULT_TOLERANCE_M
    ): Double {
        if (planned.size < 2 || recorded.isEmpty() || toleranceM <= 0.0) return 0.0

        val walk = PlannedWalk(planned, stepM = toleranceM / 2.0)
        if (walk.totalM <= 0.0) return 0.0

        val index = RecordedIndex(recorded, cellSizeM = toleranceM)
        var covered = 0.0
        for (step in walk.steps) {
            if (index.containsWithin(step.probe, toleranceM)) covered += step.lengthM
        }

        return (covered / walk.totalM).coerceIn(0.0, 1.0)
    }

    /**
     * The planned line cut into stretches, each carrying the date it was last sprayed, or
     * null where nothing has covered it.
     *
     * A stretch counts as covered by a [passes] entry whose fixes came within [toleranceM]
     * of it, and by [assetSprayedAtEpochMs] - a spray with no recording behind it, which is
     * a whole line by definition, because that is what the operator was saying when they
     * logged it. Where several of those could account for a stretch, the most recent one is
     * the answer, so two halves sprayed on two days both read as sprayed.
     *
     * Nothing is stored: the answer is recomputed from the plan against the recordings, so
     * a line that has been edited since is cut up as it is now.
     *
     * Returns an empty list when there is no line to walk - fewer than two points, all of
     * them in the same place, or no tolerance to probe with - which callers read as "draw
     * this one whole", the answer they had before any of this existed.
     */
    fun splitByCoverage(
        planned: List<GeoPoint>,
        passes: List<RecordedPass>,
        assetSprayedAtEpochMs: Long? = null,
        toleranceM: Double = DEFAULT_TOLERANCE_M
    ): List<CoverageStretch> {
        if (planned.size < 2 || toleranceM <= 0.0) return emptyList()

        val walk = PlannedWalk(planned, stepM = toleranceM / 2.0)
        if (walk.totalM <= 0.0 || walk.steps.isEmpty()) return emptyList()

        val indexes = passes
            .filter { it.points.isNotEmpty() }
            .map { it.atEpochMs to RecordedIndex(it.points, cellSizeM = toleranceM) }

        val stamped = walk.steps.map { step ->
            var latest = assetSprayedAtEpochMs
            for ((atEpochMs, index) in indexes) {
                // The newer date is the one that matters, so once a pass at least as recent
                // as anything found so far has been found, there is nothing left to look up.
                if ((latest == null || atEpochMs > latest) &&
                    index.containsWithin(step.probe, toleranceM)
                ) {
                    latest = atEpochMs
                }
            }
            latest
        }

        val stretches = mutableListOf<CoverageStretch>()
        var runStart = 0
        for (i in 1 until stamped.size) {
            if (stamped[i] != stamped[runStart]) {
                stretches += stretchOf(walk, runStart, i - 1, stamped[runStart])
                runStart = i
            }
        }
        stretches += stretchOf(walk, runStart, stamped.size - 1, stamped[runStart])
        return stretches
    }

    /** One stretch, from its first step to its last, with the plan's own geometry between. */
    private fun stretchOf(
        walk: PlannedWalk,
        firstStep: Int,
        lastStep: Int,
        lastSprayedAtEpochMs: Long?
    ): CoverageStretch {
        val startM = walk.steps[firstStep].startM
        val endM = walk.steps[lastStep].endM
        return CoverageStretch(
            points = walk.slice(startM, endM),
            lastSprayedAtEpochMs = lastSprayedAtEpochMs,
            lengthM = endM - startM
        )
    }

    /**
     * The planned line walked in steps of about half the tolerance, which is what turns
     * geometry into a series of questions that each have an answer.
     *
     * Half the tolerance is the step because a step is judged from its midpoint: a shorter
     * step would ask the same question twice, and a longer one could straddle a gap in the
     * recording and call the whole of itself covered.
     */
    private class PlannedWalk(planned: List<GeoPoint>, private val stepM: Double) {

        /** A step of the plan: where it starts and ends, and the point it is judged at. */
        class Step(val startM: Double, val endM: Double, val probe: GeoPoint) {
            val lengthM: Double get() = endM - startM
        }

        /** The plan's own points, with repeats dropped so that no segment is zero length. */
        private val vertices: List<GeoPoint>

        /** Distance along the plan in metres at each of [vertices]. */
        private val along: DoubleArray

        val totalM: Double
        val steps: List<Step>

        init {
            val keptPoints = mutableListOf<GeoPoint>()
            val keptDistances = mutableListOf<Double>()
            var travelled = 0.0
            for (index in planned.indices) {
                if (index > 0) {
                    val previous = planned[index - 1]
                    val point = planned[index]
                    val segment = haversineMeters(previous.lat, previous.lng, point.lat, point.lng)
                    // A repeated point is not a segment, and a segment with no length in it
                    // would divide by zero a few lines further down.
                    if (segment <= 0.0) continue
                    travelled += segment
                }
                keptPoints += planned[index]
                keptDistances += travelled
            }
            vertices = keptPoints
            along = keptDistances.toDoubleArray()
            totalM = travelled

            // One list of cut points for the whole walk, so that two steps meeting at one
            // are the same coordinate and not two coordinates an ulp apart: stretches are
            // drawn as separate lines, and a seam between them would be a gap.
            //
            // Every vertex ends a step, whatever the step size. A line recorded by this app
            // has a fix every few metres, so most segments of a recorded track are shorter
            // than a step - half the tolerance - and subdivide into nothing at all. Without
            // the vertex as a boundary they merged into one long step judged from a single
            // probe in the middle of the run, which is how a 160 m track driven over its
            // eastern 72 m read as 100% covered and the map drew the whole line as sprayed:
            // the one probe fell within tolerance of the recording, so every metre of the
            // plan in that merged step counted. Cutting at the vertices costs one probe per
            // recorded fix and makes the answer follow the recording along the line.
            val cuts = mutableListOf(0.0)
            for (i in 1 until vertices.size) {
                val segment = along[i] - along[i - 1]
                if (segment <= 0.0) continue
                val count = max(1, ceil(segment / stepM).toInt())
                val stepLength = segment / count
                for (k in 1 until count) cuts += along[i - 1] + k * stepLength
                cuts += along[i]
            }
            // The last vertex is the end of the plan; a plan whose tail was a repeated point
            // still has to reach its own end, or the last step would fall short of it.
            if (cuts.last() < totalM) cuts += totalM

            val built = mutableListOf<Step>()
            for (j in 0 until cuts.size - 1) {
                built += Step(cuts[j], cuts[j + 1], at((cuts[j] + cuts[j + 1]) / 2.0))
            }
            steps = built
        }

        /** The point [distanceM] along the plan, interpolated within the segment it falls in. */
        private fun at(distanceM: Double): GeoPoint {
            val clamped = distanceM.coerceIn(0.0, totalM)
            for (i in 1 until vertices.size) {
                val end = along[i]
                if (clamped > end) continue
                val start = along[i - 1]
                val span = end - start
                val t = if (span <= 0.0) 0.0 else (clamped - start) / span
                val a = vertices[i - 1]
                val b = vertices[i]
                return GeoPoint(
                    lat = a.lat + (b.lat - a.lat) * t,
                    lng = a.lng + (b.lng - a.lng) * t
                )
            }
            return vertices.last()
        }

        /**
         * The plan between two distances, keeping whichever of its own vertices lie between
         * them.
         *
         * That is what stops a stretch being drawn as a straight line across a corner: a
         * stretch of a bent track is bent, because it is the plan that it followed.
         */
        fun slice(startM: Double, endM: Double): List<GeoPoint> {
            val points = mutableListOf(at(startM))
            for (i in 1 until vertices.size - 1) {
                val where = along[i]
                // A vertex sitting exactly on a boundary is already there as that boundary.
                if (where > startM + BOUNDARY_EPS_M && where < endM - BOUNDARY_EPS_M) {
                    points += vertices[i]
                }
            }
            points += at(endM)
            return points
        }
    }

    /**
     * The recorded line bucketed into squares the size of the tolerance, so a probe
     * only has to be compared with the handful of fixes that could possibly be close
     * enough. Recomputing coverage as a recording grows is then cheap enough to do
     * live on screen.
     */
    private class RecordedIndex(points: List<GeoPoint>, cellSizeM: Double) {

        // One longitude scale for the whole recording: a sprayed track is small
        // enough that the variation with latitude is far below the tolerance.
        private val meanLat = points.sumOf { it.lat } / points.size
        private val latStep = cellSizeM / METRES_PER_DEG_LAT
        private val lngStep = cellSizeM /
            (METRES_PER_DEG_LAT * max(0.05, cos(Math.toRadians(meanLat))))

        private val cells = HashMap<Long, MutableList<GeoPoint>>()

        init {
            points.forEach { point ->
                cells.getOrPut(key(row(point.lat), column(point.lng))) { mutableListOf() } += point
            }
        }

        fun containsWithin(probe: GeoPoint, toleranceM: Double): Boolean {
            val row = row(probe.lat)
            val column = column(probe.lng)
            for (dr in -1..1) {
                for (dc in -1..1) {
                    val bucket = cells[key(row + dr, column + dc)] ?: continue
                    for (point in bucket) {
                        if (haversineMeters(probe.lat, probe.lng, point.lat, point.lng) <= toleranceM) {
                            return true
                        }
                    }
                }
            }
            return false
        }

        private fun row(lat: Double): Int = Math.floor(lat / latStep).toInt()

        private fun column(lng: Double): Int = Math.floor(lng / lngStep).toInt()

        private fun key(row: Int, column: Int): Long =
            (row.toLong() shl 32) xor (column.toLong() and 0xFFFFFFFFL)
    }
}

/**
 * How close a plan vertex has to be to a stretch boundary to count as being on it. A
 * micrometre: far below any geometry a phone can record, and enough that a vertex on a
 * boundary is not drawn twice in the same line.
 */
private const val BOUNDARY_EPS_M = 1e-6

/** "94%" / "100%" - coverage as the operator reads it. */
fun formatCoveragePercent(fraction: Double): String =
    "${Math.round(fraction * 100.0).toInt()}%"
