package nz.mckenzie.sprayday.domain.geo

import kotlin.math.abs
import kotlin.math.cos


/**
 * The ring an area asset's geometry is: its corners, the last joining the first.
 *
 * A carpark is ground with an edge, and the app has exactly one walk to make of it: along the
 * boundary, which is what gives the metres you travel, and - by the shoelace on the same local flat
 * the coverage and proximity maths already use - the ground inside. Both come out of the same list of
 * corners, so neither can be right while the other is wrong.
 *
 * **Stored closed.** [closed] is applied where a shape is written, so every reader of the geometry -
 * the cached length, the coverage walk, the GeoJSON the map draws, the GPX file - gets the closing
 * side without asking what shape it is holding. The app's own precedent is the phone's accuracy ring,
 * which closes itself for the same reason: a ring that does not close is not a ring.
 */
object Ring {

    /** Fewer corners than this and there is no ground inside: a path, not an area. */
    const val MIN_CORNERS = 3

    /** The same corners with the first repeated at the end, when it is not already there. */
    fun closed(corners: List<GeoPoint>): List<GeoPoint> {
        val first = corners.firstOrNull() ?: return corners
        return if (corners.size >= 2 && corners.last() == first) corners else corners + first
    }

    /** True when the last vertex is the first corner again, which is how a ring is stored. */
    fun isClosed(points: List<GeoPoint>): Boolean =
        points.size >= MIN_CORNERS + 1 && points.first() == points.last()

    /** How many corners a ring holds, whether it is stored closed or open. */
    fun cornerCount(points: List<GeoPoint>): Int =
        if (points.size >= 2 && points.first() == points.last()) points.size - 1 else points.size

    /**
     * The ground inside the ring, in square metres.
     *
     * The shoelace formula on an equirectangular flat taken at the ring's own mean latitude: a carpark
     * is tens of metres across, where a degree of longitude is the same length at both ends to within a
     * hand's width, so the answer is square-metre accurate. Zero for fewer than three corners, which is
     * what a straggling path comes to and the honest answer for one.
     */
    fun areaSqm(points: List<GeoPoint>): Double {
        val corners = if (points.size >= 2 && points.first() == points.last()) points.dropLast(1) else points
        if (corners.size < MIN_CORNERS) return 0.0

        val meanLat = corners.sumOf { it.lat } / corners.size
        val metresPerDegLng = METRES_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(meanLat))

        var twiceTheArea = 0.0
        for (index in corners.indices) {
            val here = corners[index]
            val next = corners[(index + 1) % corners.size]
            twiceTheArea += (here.lng * metresPerDegLng) * (next.lat * METRES_PER_DEG_LAT) -
                (next.lng * metresPerDegLng) * (here.lat * METRES_PER_DEG_LAT)
        }
        return abs(twiceTheArea) / 2.0
    }

    /**
     * Whether the point is inside the boundary.
     *
     * The one question about ground that a line cannot be asked: a tap *inside* a carpark is a tap on
     * the carpark, while a tap near a track is a tap on the track. A ray-cast on lng/lat rather than
     * a distance, because this is about inside-ness and not about nearness - the edges are straight
     * in lng/lat, and a tap *on* the boundary is answered by how near it was, in
     * [nz.mckenzie.sprayday.map.AssetHitTest], before anybody asks this.
     *
     * Half-open in latitude, so a ray through a corner crosses once rather than twice: counting the
     * corner twice is the classic way a point that is plainly inside comes out outside. An open path
     * is closed for the question, since the ground is what the corners enclose - the same corners
     * [areaSqm] measures - and false for fewer than three of them, which enclose nothing at all.
     */
    fun contains(points: List<GeoPoint>, point: GeoPoint): Boolean {
        val corners = if (points.size >= 2 && points.first() == points.last()) points.dropLast(1) else points
        if (corners.size < MIN_CORNERS) return false

        var inside = false
        for (index in corners.indices) {
            val here = corners[index]
            val next = corners[(index + 1) % corners.size]
            if ((here.lat > point.lat) != (next.lat > point.lat)) {
                val crossingLng = here.lng +
                    (point.lat - here.lat) / (next.lat - here.lat) * (next.lng - here.lng)
                if (point.lng < crossingLng) inside = !inside
            }
        }
        return inside
    }
}