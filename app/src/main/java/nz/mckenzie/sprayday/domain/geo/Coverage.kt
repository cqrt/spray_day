package nz.mckenzie.sprayday.domain.geo

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

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
 *
 * A recording is read as the line it drew, not as the fixes it happens to hold: the ground
 * between one fix and the next is ground the pass crossed, so it is walked and counted too.
 * That matters because fixes go missing - rejected for poor accuracy under trees, or simply
 * not delivered for a minute in a gully - and a pass that read 92% because the phone lost
 * the sky for 200 m was telling the operator they had missed a bit of a line they drove.
 * The one gap that is not ground driven is a pause, which the app knows about because the
 * operator pressed the button: see [RecordingBreak].
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
     *
     * [breaks] are the stretches of the pass that were paused; without them a jump in the
     * fixes is read as ground the pass crossed, which is what it usually is. Pass them
     * wherever the recording has them - see [RecordedPass] - or the number and the drawing
     * will disagree with the same pass read anywhere else.
     */
    fun coveredFraction(
        planned: List<GeoPoint>,
        recorded: List<GeoPoint>,
        toleranceM: Double = DEFAULT_TOLERANCE_M,
        breaks: List<RecordingBreak> = emptyList()
    ): Double {
        if (planned.size < 2 || recorded.isEmpty() || toleranceM <= 0.0) return 0.0

        val walk = PlannedWalk(planned, stepM = toleranceM / 2.0)
        if (walk.totalM <= 0.0) return 0.0

        val index = RecordedIndex(recorded, cellSizeM = toleranceM, breaks = breaks)
        var covered = 0.0
        for (step in walk.steps) {
            if (index.containsWithin(step.probe, toleranceM)) covered += step.lengthM
        }

        return (covered / walk.totalM).coerceIn(0.0, 1.0)
    }

    /**
     * The same question asked of a whole track: the line **and its side tracks**.
     *
     * The length of every path, each counted once, is the denominator - so a spur into the gully is
     * as much a part of "did I get the whole track" as the line it leaves, and driving up it and back
     * down does not count it twice. Weighting by length is what keeps the number an answer about the
     * ground: a hundred-metre side track moves it as much as a hundred metres of the line.
     */
    fun coveredFraction(
        planned: AssetGeometry,
        recorded: List<GeoPoint>,
        toleranceM: Double = DEFAULT_TOLERANCE_M,
        breaks: List<RecordingBreak> = emptyList()
    ): Double {
        val total = planned.lengthM
        if (total <= 0.0) return 0.0
        return (planned.paths.sumOf { path ->
            coveredFraction(path, recorded, toleranceM, breaks) * polylineLengthMeters(path)
        } / total).coerceIn(0.0, 1.0)
    }

    /**
     * The same, cut into stretches: every path of the track, each cut up on its own.
     *
     * Per path rather than over a flattened list of points, because the walk's whole idea is distance
     * *along* a line - and joining a spur onto the end of the line would make the first metres of the
     * spur read as the last metres of the line, which is a stretch drawn in the wrong colour.
     */
    fun splitByCoverage(
        planned: AssetGeometry,
        passes: List<RecordedPass>,
        assetSprayedAtEpochMs: Long? = null,
        toleranceM: Double = DEFAULT_TOLERANCE_M
    ): List<CoverageStretch> = planned.paths.flatMap { path ->
        splitByCoverage(path, passes, assetSprayedAtEpochMs, toleranceM)
    }

    /**
     * The planned line cut into stretches, each carrying the date it was last sprayed, or
     * null where nothing has covered it.
     *
     * A stretch counts as covered by a [passes] entry whose fixes came within [toleranceM]
     * of it, and by [assetSprayedAtEpochMs] - a spray with no recording behind it, which is
     * a whole line by definition, because that is what the operator was saying when they
     * logged it. Where several of those could account for a stretch, the most recent one is
     * the answer, so two halves sprayed on two days both read as sprayed. A pass is read as
     * the line it drew rather than as its fixes, and its own [RecordedPass.breaks] are the
     * gaps in it that are not - see [coveredFraction].
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
            .map { it.atEpochMs to RecordedIndex(it.points, cellSizeM = toleranceM, breaks = it.breaks) }

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
     *
     * Not private, because a line sprayed twice is measured against the same walk: see
     * [TwoPasses], which asks a harder question of each step and has to walk the plan in
     * exactly the same places to give an answer that lines up with this one.
     */
    internal class PlannedWalk(planned: List<GeoPoint>, private val stepM: Double) {

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
     *
     * The buckets hold the fixes *and the ground between them*: the line is walked between
     * one fix and the next and samples are put in at [bridgeStepM] intervals, so a plan
     * metre that lies between two fixes is vouched for by the pass that crossed it. Without
     * that, only the metres near an actual fix count, and a recording with a fix every 50 m
     * - a fast pass, or a phone that only gets a fix now and then - reads as a quarter of
     * the line sprayed.
     *
     * Two things stop the walk. A [RecordingBreak] between the two fixes means the operator
     * was stopped, so nothing is claimed across it. And a jump longer than
     * [MAX_BRIDGE_SAMPLES] steps is not one pass over one line at all - it is two different
     * places - so the fixes vouch for what they are near and nothing more.
     *
     * Not private, and with [nearby] as well as [containsWithin], because a line sprayed twice
     * is read from the same buckets: [TwoPasses] needs the fixes themselves - which way each
     * one was heading, and which side of the line it was on - rather than only whether there
     * was one.
     */
    internal class RecordedIndex(
        points: List<GeoPoint>,
        cellSizeM: Double,
        breaks: List<RecordingBreak> = emptyList()
    ) {

        // One longitude scale for the whole recording: a sprayed track is small
        // enough that the variation with latitude is far below the tolerance.
        private val meanLat = points.sumOf { it.lat } / points.size
        private val latStep = cellSizeM / METRES_PER_DEG_LAT
        private val lngStep = cellSizeM /
            (METRES_PER_DEG_LAT * max(0.05, cos(Math.toRadians(meanLat))))

        private val cells = HashMap<Long, MutableList<GeoPoint>>()

        init {
            points.forEach { point -> add(point) }

            val bridgeStepM = cellSizeM * BRIDGE_STEP_FRACTION
            for (index in 1 until points.size) {
                val from = points[index - 1]
                val to = points[index]
                if (breaks.any { it.fallsBetween(from.timeMs, to.timeMs) }) continue

                val gapM = haversineMeters(from.lat, from.lng, to.lat, to.lng)
                if (gapM <= bridgeStepM) continue
                val steps = ceil(gapM / bridgeStepM).toInt()
                if (steps > MAX_BRIDGE_SAMPLES) continue

                // The two fixes are already in the buckets; this is the ground between them.
                for (step in 1 until steps) {
                    val t = step.toDouble() / steps
                    // A sample carries the time it was crossed, interpolated between the two fixes,
                    // so that a pass whose fixes are far apart is still read as a pass with a
                    // direction and a side: at twenty-five metres between fixes - a slow fix rate,
                    // or a fast drive - only the ground between them is within reach of the plan's
                    // steps, and a fix that is out of reach says nothing about which way the pass
                    // was heading. See [TwoPasses].
                    val crossesAtMs = if (from.timeMs > 0L && to.timeMs > from.timeMs) {
                        from.timeMs + ((to.timeMs - from.timeMs) * t).toLong()
                    } else {
                        0L
                    }
                    add(
                        GeoPoint(
                            lat = from.lat + (to.lat - from.lat) * t,
                            lng = from.lng + (to.lng - from.lng) * t,
                            timeMs = crossesAtMs
                        )
                    )
                }
            }
        }

        private fun add(point: GeoPoint) {
            cells.getOrPut(key(row(point.lat), column(point.lng))) { mutableListOf() } += point
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

        /**
         * The recorded fixes within [toleranceM] of [probe], in the order they were recorded.
         *
         * The fixes themselves, without the samples walked between them: a line sprayed twice
         * is read for which way each pass was heading and which side of the plan it was on, and
         * a sample interpolated between two fixes has neither. An interpolated sample is there
         * to vouch for the ground under it, which is what [containsWithin] reads; its time is
         * zero, which is how the two are told apart.
         */
        fun nearby(probe: GeoPoint, toleranceM: Double): List<GeoPoint> {
            val row = row(probe.lat)
            val column = column(probe.lng)
            val found = mutableListOf<GeoPoint>()
            for (dr in -1..1) {
                for (dc in -1..1) {
                    val bucket = cells[key(row + dr, column + dc)] ?: continue
                    for (point in bucket) {
                        if (point.timeMs <= 0L) continue
                        if (haversineMeters(probe.lat, probe.lng, point.lat, point.lng) <= toleranceM) {
                            found += point
                        }
                    }
                }
            }
            return found.sortedBy { it.timeMs }
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

/**
 * How far apart the samples walked between two fixes are, as a fraction of the tolerance:
 * half, so that any point on the line between them is within a quarter of a tolerance of a
 * sample - comfortably inside it, whatever the plan's own step size lands on.
 */
private const val BRIDGE_STEP_FRACTION = 0.5

/**
 * The most samples one gap between two fixes may be walked with.
 *
 * A gap of a few hundred metres - the length of a fix outage under trees or in a gully -
 * takes a handful of samples. The cap is what keeps a recording that was left on during a
 * drive to town from walking hundreds of thousands of them: at the default tolerance it is
 * 6 km, and a jump longer than that is not one pass over one line, it is two different
 * places, so the fixes vouch for what they are near and nothing more - which is what a
 * coverage measured from the fixes alone used to say.
 */
private const val MAX_BRIDGE_SAMPLES = 1_000

/** "94%" / "100%" - coverage as the operator reads it. */
fun formatCoveragePercent(fraction: Double): String =
    "${Math.round(fraction * 100.0).toInt()}%"
