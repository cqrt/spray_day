package nz.mckenzie.sprayday.domain.asset

/**
 * Whether an asset is a line on the map or a single point.
 *
 * A track, a road and a fenceline are lines; a building, a sign, a bench, a table or anything
 * else you stop at is a place, and its geometry is that one coordinate. Nothing offers this as a
 * choice: the kind of the asset decides it (see [AssetKind.shape]), because an answer that can
 * disagree with the kind is a picnic table drawn as a line across a paddock.
 */
enum class AssetShape {
    /** Two or more points: something you travel along. */
    LINE,

    /** Exactly one point: somewhere you stop and spray. */
    POINT;

    companion object {
        /** The shape a stored value means; anything unrecognised is a line. */
        fun fromStorage(value: String?): AssetShape =
            entries.firstOrNull { it.name == value } ?: LINE
    }
}
