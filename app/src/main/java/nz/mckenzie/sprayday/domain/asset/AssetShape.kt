package nz.mckenzie.sprayday.domain.asset

/**
 * Whether an asset is a line on the map or a single point.
 *
 * A track, a road and a fenceline are lines; a picnic table, a shelter or a trough is
 * a place, and its geometry is that one coordinate. Lines are the default, and only
 * infrastructure is offered the choice, because a point track is almost always a
 * mistake rather than an intention.
 */
enum class AssetShape {
    /** Two or more points: something you travel along. */
    LINE,

    /** Exactly one point: somewhere you stop and spray. */
    POINT
}
