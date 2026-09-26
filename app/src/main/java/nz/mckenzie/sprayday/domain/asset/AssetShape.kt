package nz.mckenzie.sprayday.domain.asset

/**
 * What an asset's geometry is: a line to travel along, a single point, or ground with an edge.
 *
 * A track, a road and a fenceline are lines; a building, a sign, a bench, a table or anything
 * else you stop at is a place, and its geometry is that one coordinate; a carpark is ground, and its
 * geometry is a ring - the boundary, closed on itself. Nothing offers this as a choice: the kind of
 * the asset decides it (see [AssetKind.shape]), because an answer that can disagree with the kind is
 * a picnic table drawn as a line across a paddock.
 */
enum class AssetShape {
    /** Two or more points: something you travel along. */
    LINE,

    /** Exactly one point: somewhere you stop and spray. */
    POINT,

    /**
     * Three or more corners with the last joining the first: ground with an edge.
     *
     * A carpark is the first of these. Its geometry is a path like a line's, and it is **stored
     * closed** - the last vertex is the first corner again, written by the app where the shape is
     * settled ([nz.mckenzie.sprayday.domain.geo.Ring]). That is what keeps every reader of a shape
     * shape-blind: the cached metres, the coverage walk, the GeoJSON the map draws and the GPX file
     * all walk the vertices they are given, and a closing side left implied by the kind would make
     * each of them ask what it was holding first.
     */
    AREA;

    companion object {
        /** The shape a stored value means; anything unrecognised is a line. */
        fun fromStorage(value: String?): AssetShape =
            entries.firstOrNull { it.name == value } ?: LINE
    }
}
