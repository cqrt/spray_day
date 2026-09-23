package nz.mckenzie.sprayday.domain.asset

/**
 * What an asset is, in the operator's words rather than the database's.
 *
 * Eight types, and the type decides the shape: three lines to travel along, and five places to
 * stop at. That is why the shape is a property here rather than a second question on the form -
 * two questions that can disagree is a picnic table drawn as a line across a paddock, with
 * nothing on the screen saying the two answers were different.
 *
 * The distinction matters for the map, which draws a fenceline and a picnic table as different
 * things, and for the handover record, which has to say what was sprayed. It decides nothing
 * about due dates or spray method: those are their own fields, because a road can be blanketed
 * with a boom and a track can be knapsacked.
 */
enum class AssetKind(
    /** A line to travel along, or a single place to stop at. */
    val shape: AssetShape
) {
    /** A line walked or driven to spray: the original kind, and still the common one. */
    TRACK(AssetShape.LINE),

    /** A formed road. Sprayed like a track, but reported as its own kind of work. */
    ROAD(AssetShape.LINE),

    /** A fence line: a line too, and the only infrastructure that is one. */
    FENCELINE(AssetShape.LINE),

    /** A shed, a woolshed, a pump house: somewhere with a roof. */
    BUILDING(AssetShape.POINT),

    /** A sign to spray around the base of. */
    SIGN(AssetShape.POINT),

    /** A bench seat. */
    BENCH(AssetShape.POINT),

    /** A picnic table. */
    TABLE(AssetShape.POINT),

    /**
     * Anything else that is one spot: a trough, a tank, a gate.
     *
     * The catch-all, and its glyph is the plainest of the eight on purpose: what it has to say is
     * "something is here" and nothing beyond that. Everything on a farm sprayed before these
     * types existed that was a single spot is read as one of these, because which of the five it
     * was is a thing nobody wrote down.
     */
    OTHER_PLACE(AssetShape.POINT);

    companion object {

        /**
         * What infrastructure was stored as before there were eight types.
         *
         * Every fenceline, trough, shed and table on a farm sprayed before this release carries
         * this one word, and the shape beside it is the only thing that says which of the two
         * things it was - a line, or a place. Read, never written: the row keeps the old word
         * until somebody edits it, so an update touches nothing in bulk.
         */
        const val LEGACY_INFRASTRUCTURE = "INFRASTRUCTURE"

        /**
         * The kind a stored value means, or null when nothing here does.
         *
         * Null is for the desk, which refuses a write naming a kind this build has never heard of
         * rather than storing it as something else. The app's own reads use [fromStorage], which
         * always has to answer: a record that cannot be read is a record that cannot be looked at.
         */
        fun known(value: String?, shape: AssetShape): AssetKind? = when (value) {
            // A line of old infrastructure was a fenceline. A spot was one of the five places,
            // and the one that claims nothing is the honest reading of a thing nobody named.
            LEGACY_INFRASTRUCTURE -> if (shape == AssetShape.POINT) OTHER_PLACE else FENCELINE
            else -> entries.firstOrNull { it.name == value }
        }

        /**
         * The kind a stored value means.
         *
         * Anything unrecognised reads as [TRACK]: a value from a later build, or a database
         * edited by hand, must not stop the app opening its own records - and every asset that
         * came from the old track list is one.
         */
        fun fromStorage(value: String?, shape: AssetShape): AssetKind = known(value, shape) ?: TRACK
    }
}
