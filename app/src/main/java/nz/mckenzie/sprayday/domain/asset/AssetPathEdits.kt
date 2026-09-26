package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.Ring

/**
 * What a drawn path turned into: the paths to store, or why they will not do.
 *
 * A list of paths rather than one, because an asset is a line and its side tracks - see
 * [nz.mckenzie.sprayday.domain.geo.AssetGeometry] - and because the desk sends a whole line at once:
 * the map hands over every vertex every time rather than a difference, which is what makes an undo on
 * the desk cost the phone nothing.
 */
sealed interface AssetPathResult {

    /** The paths to store, line first and its side tracks after it, already cleaned up. */
    data class Ok(val paths: List<List<GeoPoint>>) : AssetPathResult

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
     * The paths to store for a shape, or why these ones will not do.
     *
     * Named `applyPaths` rather than an overload of [apply] because the two erase to the same JVM
     * signature - `List<List<GeoPoint>>` and `List<GeoPoint>` are both a `List` once compiled - and a
     * name is cheaper than a fight with the compiler. This is the real rule; [apply] is the one-line
     * door into it.
     *
     * Vertices that repeat exactly are dropped, which is what a double click on one spot means. The
     * comparison is exact on purpose: the desktop snaps a vertex onto another by copying its
     * coordinates, so two vertices that are meant to be the same place *are* the same two numbers,
     * while two vertices a hair apart are the operator's own doings - a
     * [nz.mckenzie.sprayday.domain.geo.TrackPointFilter]-style rounding belongs where a recording is
     * filtered, not here, where it would silently move a line somebody drew.
     *
     * **A side track has one rule of its own**: it has to start *on* the line it hangs off. Not near
     * it - on it. The phone's own drawing snaps the first vertex of a side track onto a vertex of the
     * line, so the join is the very same pair of numbers in both paths, and a path whose first vertex
     * is on nothing is a stray line the operator cannot see the join of. A line with one point is
     * therefore the only thing a side track may not be.
     */
    fun applyPaths(shape: AssetShape, paths: List<List<GeoPoint>>): AssetPathResult {
        for (path in paths) {
            for (point in path) {
                if (!onTheEarth(point)) {
                    return AssetPathResult.Invalid(
                        "One of those points is not on the earth, so the phone will not keep it. " +
                            "Wait for the map to finish drawing and try again."
                    )
                }
            }
        }

        val clean = paths.map { path -> path.dropConsecutiveRepeats() }

        // The count is checked before the shape, because "too many" is a refusal about the whole
        // request rather than about what kind of thing it was drawn as. It is the whole track's count:
        // the cap exists so that a desk's body cannot be bigger than the socket allows, and a track
        // with side tracks is one body.
        val total = clean.sumOf { it.size }
        if (total > MAX_POINTS) {
            return AssetPathResult.Invalid(
                "That is $total points, and the phone keeps up to $MAX_POINTS on one " +
                    "track. A track that came off a recording is filtered on the phone instead."
            )
        }

        return when (shape) {
            AssetShape.POINT -> when {
                clean.size == 1 && clean[0].size == 1 -> AssetPathResult.Ok(clean)
                total == 0 -> AssetPathResult.Invalid(
                    "A place is one spot on the map - click where it goes."
                )
                else -> AssetPathResult.Invalid(
                    "A place is one spot on the map, and that is $total points. " +
                        "Change it to a path first, or click one spot."
                )
            }

            // Ground with an edge. The app closes the ring rather than the drawing having to: a desk
            // hands back the corners it was given, and a line that has just been re-kinded into a
            // carpark arrives open by definition - so what is stored is always closed, and every
            // reader of it (the metres, the coverage, the map) walks it without asking the shape.
            AssetShape.AREA -> when {
                clean.size > 1 -> AssetPathResult.Invalid(
                    "A carpark is one boundary and has no side tracks - take them off it first."
                )
                Ring.cornerCount(clean.firstOrNull().orEmpty()) < Ring.MIN_CORNERS ->
                    AssetPathResult.Invalid(
                        "A carpark is the ground inside its boundary, so it needs three corners " +
                            "at least - and that one has " +
                            "${Ring.cornerCount(clean.firstOrNull().orEmpty())}."
                    )
                else -> AssetPathResult.Ok(listOf(Ring.closed(clean.firstOrNull().orEmpty())))
            }

            // The app's own sentence, word for word, from `AssetRepository.importAssetGpx`: a desk
            // must not be told about a one-point line in words no other part of the app uses.
            AssetShape.LINE -> when {
                clean.firstOrNull().orEmpty().size < 2 -> AssetPathResult.Invalid(
                    "A line needs at least two points"
                )
                else -> sideTrackProblem(clean)?.let { AssetPathResult.Invalid(it) }
                    ?: AssetPathResult.Ok(clean.filter { it.size >= 2 })
            }
        }
    }

    /** The points to store for a line on its own: what a desk's own write sends. */
    fun apply(shape: AssetShape, points: List<GeoPoint>): AssetPathResult =
        applyPaths(shape, listOf(points))

    /**
     * What is wrong with these side tracks, in the app's words, or null when they are all fine.
     *
     * Empty ones are not a problem - a path with nothing in it is dropped rather than refused, since
     * there is nothing in it to lose - and that is the one place this is deliberately forgiving.
     */
    private fun sideTrackProblem(paths: List<List<GeoPoint>>): String? {
        val line = paths.first()
        for (side in paths.drop(1)) {
            if (side.isEmpty()) continue
            if (side.size < 2) {
                return "A side track needs at least two points, and that one has " +
                    "${side.size} - give it another or take it off."
            }
            if (line.none { it == side.first() }) {
                return "A side track has to start on the track it hangs off. Tap the track where " +
                    "the side track leaves it."
            }
        }
        return null
    }

    /** A path with the vertices that repeat the one before them taken out, and nothing else changed. */
    private fun List<GeoPoint>.dropConsecutiveRepeats(): List<GeoPoint> =
        filterIndexed { index, point -> index == 0 || point != this[index - 1] }

    /** A latitude and longitude a map can be drawn with: NaN and the poles' outside are not places. */
    private fun onTheEarth(point: GeoPoint): Boolean =
        point.lat.isFinite() && point.lng.isFinite() &&
            point.lat in -90.0..90.0 && point.lng in -180.0..180.0
}
