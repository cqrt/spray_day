package nz.mckenzie.sprayday.domain.asset

/**
 * What an asset is, in the operator's words rather than the database's.
 *
 * The distinction matters for the map, where a fenceline and a picnic table are not
 * the same thing to draw, and for the handover record, which has to say what was
 * sprayed. It decides nothing about due dates or spray method: those are their own
 * fields, because a road can be blanketed with a boom and a track can be knapsacked.
 */
enum class AssetKind {
    /** A line driven or walked to spray: the original kind, and still the common one. */
    TRACK,

    /** A formed road. Sprayed like a track, but reported as its own kind of work. */
    ROAD,

    /** Fencelines and structures: short runs, and things that are a place not a path. */
    INFRASTRUCTURE
}
