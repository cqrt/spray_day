package nz.mckenzie.sprayday.domain.geo

/**
 * What an asset's geometry is: **a line, and the side tracks hanging off it**.
 *
 * A track on the ground is not always one path. A fenceline has a spur into a gully; a road has a
 * siding; a lane has a gateway you have to drive up and back. The app used to have one shape for an
 * asset's geometry - an ordered list of vertices, start to finish - so the only way to draw one of
 * those was to walk up the spur and back down it as part of the line. That costs twice over:
 *
 *  - **the length**, because the metres of the spur are in the line twice, and the length is what the
 *    handover and the coverage denominator use;
 *  - **the two-pass reading**, because [TwoPasses] treats opposite directions over the same metres as
 *    "up one side and back down the other" - which is right for a road and wrong for a dead end. One
 *    trip up a spur came back as both passes done, for a reason that had nothing to do with the spur.
 *
 * So an asset's geometry is a list of paths. **[paths]`[0]` is the line** and the rest are side
 * tracks, in the order they were drawn - the storage's own order (`asset_points.pathIndex`), so
 * nothing has to be sorted out on the way in or out, and a desk or a backup reads the same shape the
 * database holds.
 *
 * **One level, on purpose.** A side track hangs off the line and has no side tracks of its own: the
 * operator's case is a little side track off a track, and one level keeps the drawing, the storage and
 * the arithmetic honest without pretending to be a road network. [hasSideTracks] is therefore the
 * whole of the branching this app knows about.
 *
 * [lengthM] counts every metre of every path **once** - the ground the job covers - which is the
 * number that stops being wrong the moment a spur leaves the line by itself.
 */
data class AssetGeometry(val paths: List<List<GeoPoint>> = emptyList()) {

    /** The line itself: empty when nothing is drawn, or a place with no spot placed yet. */
    val line: List<GeoPoint> get() = paths.firstOrNull().orEmpty()

    /** The side tracks, in the order they were drawn. */
    val sideTracks: List<List<GeoPoint>> get() = if (paths.isEmpty()) emptyList() else paths.drop(1)

    /** Every vertex of every path, line first: what a count or a fingerprint wants. */
    val points: List<GeoPoint> get() = paths.flatten()

    val pointCount: Int get() = paths.sumOf { it.size }

    val hasSideTracks: Boolean get() = paths.size > 1

    /** A place is one spot; a line is two or more points on one path. */
    val isLine: Boolean get() = line.size >= 2

    /**
     * The metres the job covers: every path's own length, each counted once.
     *
     * A zero-length path contributes nothing, which is what a place comes to - it has no length to
     * spray - and is why a spot is drawn as a point rather than a line of no length.
     */
    val lengthM: Double get() = paths.sumOf { polylineLengthMeters(it) }

    companion object {
        /** Nothing drawn: what an asset with no geometry reads as, and what the drawing starts from. */
        val NONE = AssetGeometry()

        fun of(line: List<GeoPoint>, sideTracks: List<List<GeoPoint>> = emptyList()): AssetGeometry =
            if (line.isEmpty() && sideTracks.isEmpty()) {
                NONE
            } else {
                AssetGeometry(listOf(line) + sideTracks.filter { it.isNotEmpty() })
            }
    }
}