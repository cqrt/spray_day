package nz.mckenzie.sprayday.domain.geo

import kotlin.math.asin
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.min
import kotlin.math.sin
import kotlin.math.sqrt

/** Mean Earth radius in metres (IUGG). */
const val EARTH_RADIUS_M: Double = 6_371_008.8

/**
 * Metres per degree of latitude, and per degree of longitude at the equator.
 * Used for the small local projections the coverage and proximity maths need.
 */
const val METRES_PER_DEG_LAT = 111_132.0
const val METRES_PER_DEG_LNG_AT_EQUATOR = 111_320.0
/**
 * Great-circle distance in metres between two coordinates (haversine).
 */
fun haversineMeters(lat1: Double, lng1: Double, lat2: Double, lng2: Double): Double {
    val dLat = Math.toRadians(lat2 - lat1)
    val dLng = Math.toRadians(lng2 - lng1)
    val a = sin(dLat / 2).let { it * it } +
        cos(Math.toRadians(lat1)) * cos(Math.toRadians(lat2)) * sin(dLng / 2).let { it * it }
    return 2.0 * EARTH_RADIUS_M * asin(min(1.0, sqrt(a)))
}

/** Cumulative length in metres of an ordered polyline. */
fun polylineLengthMeters(points: List<GeoPoint>): Double {
    if (points.size < 2) return 0.0
    var total = 0.0
    for (i in 1 until points.size) {
        val a = points[i - 1]
        val b = points[i]
        total += haversineMeters(a.lat, a.lng, b.lat, b.lng)
    }
    return total
}

/**
 * Shortest distance in metres from [point] to the segment [start]-[end].
 *
 * Uses a local equirectangular projection, which is accurate to well under a
 * metre over the distances involved in a sprayed track (tens to hundreds of
 * metres). This is the primitive used to work out how much of a planned track
 * a recorded spray actually covered.
 */
fun distanceToSegmentMeters(point: GeoPoint, start: GeoPoint, end: GeoPoint): Double {
    val midLatRad = Math.toRadians((start.lat + end.lat) / 2.0)
    val mPerDegLat = METRES_PER_DEG_LAT
    val mPerDegLng = METRES_PER_DEG_LNG_AT_EQUATOR * cos(midLatRad)

    fun x(g: GeoPoint) = (g.lng - start.lng) * mPerDegLng
    fun y(g: GeoPoint) = (g.lat - start.lat) * mPerDegLat

    val bx = x(end)
    val by = y(end)
    val px = x(point)
    val py = y(point)

    val lengthSquared = bx * bx + by * by
    if (lengthSquared == 0.0) return hypot(px, py)

    val t = ((px * bx + py * by) / lengthSquared).coerceIn(0.0, 1.0)
    return hypot(px - t * bx, py - t * by)
}

/** Shortest distance in metres from [point] to a polyline (0 if the polyline is empty). */
fun distanceToPolylineMeters(point: GeoPoint, polyline: List<GeoPoint>): Double {
    if (polyline.isEmpty()) return Double.NaN
    if (polyline.size == 1) {
        return haversineMeters(point.lat, point.lng, polyline[0].lat, polyline[0].lng)
    }
    var best = Double.MAX_VALUE
    for (i in 1 until polyline.size) {
        val d = distanceToSegmentMeters(point, polyline[i - 1], polyline[i])
        if (d < best) best = d
    }
    return best
}

/**
 * Estimated area treated for a track of [lengthM] sprayed with a swath of
 * [swathWidthM] metres. Note this assumes no overlap between passes.
 */
fun estimatedAreaSqm(lengthM: Double, swathWidthM: Double): Double =
    if (lengthM <= 0.0 || swathWidthM <= 0.0) 0.0 else lengthM * swathWidthM
