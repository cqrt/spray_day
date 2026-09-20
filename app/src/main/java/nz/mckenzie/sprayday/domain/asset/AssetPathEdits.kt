package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * What a drawn path turned into: the points to store, or why they will not do.
 *
 * A list rather than a single point, because the desk sends a whole line at once - the map hands
 * over every vertex every time rather than a difference, which is what makes an undo on the desk
 * cost the phone nothing.
 */
sealed interface AssetPathResult {

    /** The points to store, in order, already cleaned up. */
    data class Ok(val points: List<GeoPoint>) : AssetPathResult

    /** What to tell the operator, in words that say what to fix. */
    data class Invalid(val message: String) : AssetPathResult
}

/**
 * The rules a path drawn on a computer has to keep.
 *
 * The desk's drawing is in JavaScript and the phone's own drawing is in Kotlin, and a path that
 * arrives over the wire is data like any other: what it means is decided here, once, in the app's own
 * words, rather than being trusted because the page drew it. A page is a client, and a client that
 * says "here is a line of one point" has to be answered rather than obeyed.
 *
 * Pure, like [AssetEdits]: no database, no Android, and every sentence testable on its own.
 */
object AssetPathEdits {

    /**
     * How many vertices one line may have.
     *
     * A drawn track is tens of points, and a track that came off a recording is a few thousand -
     * which is the point of this number rather than a round figure picked out of the air. It is well
     * under what the socket layer's own 256 KB body limit allows, so a path refused here is refused
     * with the phone's own sentence about the path, not with a refusal about the size of a request
     * that never reached these rules.
     */
    const val MAX_POINTS = 2000

    /**
     * The points to store for a shape, or why these ones will not do.
     *
     * Vertices that repeat exactly are dropped, which is what a double click on one spot means. The
     * comparison is exact on purpose: the desktop snaps a vertex onto another by copying its
     * coordinates, so two vertices that are meant to be the same place *are* the same two numbers,
     * while two vertices a hair apart are the operator's own doings - a
     * [nz.mckenzie.sprayday.domain.geo.TrackPointFilter]-style rounding belongs where a recording is
     * filtered, not here, where it would silently move a line somebody drew.
     */
    fun apply(shape: AssetShape, points: List<GeoPoint>): AssetPathResult {
        for (point in points) {
            if (!onTheEarth(point)) {
                return AssetPathResult.Invalid(
                    "One of those points is not on the earth, so the phone will not keep it. " +
                        "Wait for the map to finish drawing and try again."
                )
            }
        }

        val clean = points.filterIndexed { index, point ->
            index == 0 || point.lat != points[index - 1].lat || point.lng != points[index - 1].lng
        }

        // The count is checked before the shape, because "too many" is a refusal about the whole
        // request rather than about what kind of thing it was drawn as.
        if (clean.size > MAX_POINTS) {
            return AssetPathResult.Invalid(
                "That is ${clean.size} points, and the phone keeps up to $MAX_POINTS on one " +
                    "line. A track that came off a recording is filtered on the phone instead."
            )
        }

        return when (shape) {
            AssetShape.POINT -> when (clean.size) {
                0 -> AssetPathResult.Invalid("A place is one spot on the map - click where it goes.")
                1 -> AssetPathResult.Ok(clean)
                else -> AssetPathResult.Invalid(
                    "A place is one spot on the map, and that is ${clean.size} points. " +
                        "Change it to a path first, or click one spot."
                )
            }

            // The app's own sentence, word for word, from `AssetRepository.importAssetGpx`: a desk
            // must not be told about a one-point line in words no other part of the app uses.
            AssetShape.LINE -> if (clean.size >= 2) {
                AssetPathResult.Ok(clean)
            } else {
                AssetPathResult.Invalid("A line needs at least two points")
            }
        }
    }

    /** A latitude and longitude a map can be drawn with: NaN and the poles' outside are not places. */
    private fun onTheEarth(point: GeoPoint): Boolean =
        point.lat.isFinite() && point.lng.isFinite() &&
            point.lat in -90.0..90.0 && point.lng in -180.0..180.0
}
