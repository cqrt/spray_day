package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LAT
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LNG_AT_EQUATOR
import nz.mckenzie.sprayday.domain.geo.haversineMeters
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import kotlin.math.acos
import kotlin.math.cos
import kotlin.math.hypot

/**
 * The name written beside a track, and the stretch of the track it is written on.
 *
 * Two decisions live here, both of them taken from what a map engine actually does with a name, which
 * was looked at on purpose before any of this was built (the reading is in
 * `build/verify/names-look-first.txt`):
 *
 *  - **Where the name goes is the app's decision rather than the map's.** Asking the map for a name at
 *    the middle of a whole track writes the name more than once: the map chops a long track into pieces
 *    of its own at some zooms, and each piece gets its own name. So the app hands the map one short
 *    stretch of the track's own line - so the name still follows the shape of the track - and only that
 *    stretch carries the name.
 *  - **That stretch has to be one a name can live on.** The map drops a name it cannot fit along its
 *    line, and drops one that would have to turn more sharply than its letters may. So the stretch is
 *    taken from around the middle of the track and grown outwards while the line stays gentle: a name
 *    has the best chance of landing there, once, near the middle, which is where it was asked for.
 *
 * The numbers the two maps write a name with are here too, for the same reason the dash patterns live
 * in [AssetLineStyles]: a name that reads one way on the phone and another on the desk is the drift this
 * file exists to stop, and a test pins the two maps to these numbers.
 */
object TrackNames {

    /**
     * The property saying that a feature is the stretch carrying the name.
     *
     * Read by both maps' name layers, and by the line layers to leave that stretch alone: a stretch
     * drawn twice - once as the track and once for the name - would paint over the colours of a track
     * that is part sprayed.
     */
    const val CARRIES_NAME = "carriesName"

    /**
     * The zoom the map has to be at before a name is drawn at all.
     *
     * The operator's own finding, after living with v0.6.57: on a wide view the names crowd together over
     * a work and read as a muddle, so a name now waits until the map is close in. The level shipped at
     * seventeen in v0.6.58 and is sixteen from v0.6.59 - the operator's own adjustment after living with
     * the floor. A level of the map's own zoom is the right kind of number rather than a distance on the
     * ground: how big the letters are against the ground under them is exactly what the zoom says, and
     * that is what decides whether a name can be read. Both maps hold their names back until this level -
     * the phone's own map and the desk's - so the two agree as they do about every other number here.
     */
    const val MIN_ZOOM = 16f

    /** How big the letters are, in style pixels: the same number on both maps. */
    const val SIZE = 12f

    /**
     * The dark outline around the letters.
     *
     * Thin. A heavy outline at a small size eats the letters it is meant to make readable, which is a
     * count rather than an opinion - see the notes above.
     */
    const val HALO_WIDTH = 0.7f

    /** How far off the line the name sits, measured in letter heights: beside the track, not on it. */
    const val OFFSET = 0.85f

    /**
     * How sharply a letter may turn.
     *
     * The engine's own default is forty-five degrees, which loses the name of a track with an ordinary
     * corner in the middle of it - measured rather than assumed. A name bent a little reads better than
     * a name that is not there, so this allows as much as the engine does.
     */
    const val MAX_ANGLE = 180f

    /**
     * White letters with a dark outline.
     *
     * White is the one colour the traffic light never uses, so a name never says anything about when the
     * work is due - the same argument the ground's own white rim is made of.
     */
    const val COLOUR = "#FFFFFF"
    const val HALO_COLOUR = "#000000"

    /**
     * How sharp a corner may be and still be written across. Forty degrees is a bend rather than a
     * corner: sharper than that and the name is left off that bit of the run.
     */
    private const val GENTLE_TURN = 40.0

    /**
     * The stretch of [path] to write the name on: the longest run of gentle bends around the middle of
     * the track, taken from the track's own points so the name follows the track's shape.
     *
     * Empty when there is no line to write on.
     */
    fun pieceOf(path: List<GeoPoint>): List<GeoPoint> {
        if (path.size < 2) return emptyList()
        // Two points are their own stretch: there is nothing to choose between.
        if (path.size == 2) return path.toList()

        val middle = nearestToHalfway(path)

        // A corner at the middle cannot be written across, however gentle the rest of the track is. The
        // name goes on whichever of the two lines leaving that corner is longer, so a track whose
        // middle happens to be a corner still carries its name rather than nothing at all.
        if (turnDegrees(path, middle) > GENTLE_TURN) {
            val before = haversineMeters(
                path[middle - 1].lat, path[middle - 1].lng, path[middle].lat, path[middle].lng
            )
            val after = haversineMeters(
                path[middle].lat, path[middle].lng, path[middle + 1].lat, path[middle + 1].lng
            )
            return if (before >= after) {
                listOf(path[middle - 1], path[middle])
            } else {
                listOf(path[middle], path[middle + 1])
            }
        }

        var first = middle - 1
        var last = middle + 1
        while (first > 0 && turnDegrees(path, first) <= GENTLE_TURN) first--
        while (last < path.lastIndex && turnDegrees(path, last) <= GENTLE_TURN) last++
        return path.subList(first, last + 1).toList()
    }

    /**
     * The point of the path nearest half way along it, in metres rather than in points: a track drawn as
     * two long straight runs and one fiddly corner has its middle in the middle of the metres, not in
     * the middle of the list of points.
     */
    private fun nearestToHalfway(path: List<GeoPoint>): Int {
        val half = polylineLengthMeters(path) / 2.0
        var walked = 0.0
        for (index in 0 until path.lastIndex) {
            walked += haversineMeters(
                path[index].lat, path[index].lng, path[index + 1].lat, path[index + 1].lng
            )
            if (walked >= half) return (index + 1).coerceIn(1, path.size - 2)
        }
        return path.size - 2
    }

    /**
     * How much the track changes direction at the point [at], in degrees: nothing at all along a
     * straight line, and a hundred and eighty where it doubles back on itself.
     *
     * Measured on the same local flat frame the app's distance maths uses, and it is the direction that
     * is wanted rather than the distance, so the frame's small error over a few hundred metres does not
     * reach the answer.
     */
    private fun turnDegrees(path: List<GeoPoint>, at: Int): Double {
        val here = path[at]
        val mPerDegLng = METRES_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(here.lat))
        fun east(from: GeoPoint, to: GeoPoint) = (to.lng - from.lng) * mPerDegLng
        fun north(from: GeoPoint, to: GeoPoint) = (to.lat - from.lat) * METRES_PER_DEG_LAT

        val inEast = east(path[at - 1], here)
        val inNorth = north(path[at - 1], here)
        val outEast = east(here, path[at + 1])
        val outNorth = north(here, path[at + 1])

        val product = hypot(inEast, inNorth) * hypot(outEast, outNorth)
        // A pair of fixes on top of each other has no direction to judge: that is a recording artefact
        // rather than a corner, and calling it a hairpin would cost the track its name.
        if (product == 0.0) return 0.0

        val cosine = ((inEast * outEast + inNorth * outNorth) / product).coerceIn(-1.0, 1.0)
        return Math.toDegrees(acos(cosine))
    }
}
