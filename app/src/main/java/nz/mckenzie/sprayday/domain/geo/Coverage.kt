package nz.mckenzie.sprayday.domain.geo

import kotlin.math.ceil
import kotlin.math.cos
import kotlin.math.max

/**
 * How much of a planned track a recorded run actually covered.
 *
 * This is the question an operator asks after a pass: *did I get the whole line?*
 * The answer is a fraction, computed by walking the planned geometry and asking
 * whether the recording came within a tolerance of each part of it - so a section
 * driven twice does not inflate the number, and a section missed entirely shows up
 * as a shortfall straight away.
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

        val index = RecordedIndex(recorded, cellSizeM = toleranceM)
        var total = 0.0
        var covered = 0.0

        for (i in 1 until planned.size) {
            val start = planned[i - 1]
            val end = planned[i]
            val length = haversineMeters(start.lat, start.lng, end.lat, end.lng)
            if (length <= 0.0) continue

            val steps = max(1, ceil(length / (toleranceM / 2.0)).toInt())
            val stepLength = length / steps
            for (step in 0 until steps) {
                val t = (step + 0.5) / steps
                val probe = GeoPoint(
                    lat = start.lat + (end.lat - start.lat) * t,
                    lng = start.lng + (end.lng - start.lng) * t
                )
                total += stepLength
                if (index.containsWithin(probe, toleranceM)) covered += stepLength
            }
        }

        return if (total <= 0.0) 0.0 else (covered / total).coerceIn(0.0, 1.0)
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

/** "94%" / "100%" - coverage as the operator reads it. */
fun formatCoveragePercent(fraction: Double): String =
    "${Math.round(fraction * 100.0).toInt()}%"
