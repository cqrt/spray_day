package nz.mckenzie.sprayday.domain.asset

/**
 * One layer of the work on the map, as the operator's switches see it.
 *
 * The map draws the work in four layers, and this is the list of them: what is sprayed along
 * (tracks and roads), and what infrastructure is - a fenceline you travel along, or a place you
 * stop at. Four switches rather than one, because a block can hold all four and the reason to
 * hide one is usually to read another: a road network under a set of fencelines is noise, and a
 * map full of places is not the one you read to see which tracks are due.
 *
 * **What a preference stores is what is hidden, not what is shown.** Everything is drawn until
 * somebody says otherwise, so the map is what it always was for anyone who never opens this, and
 * a layer added in a later build arrives visible rather than missing: an operator who has never
 * touched a switch must not lose a layer because a new one turned up.
 */
enum class AssetLayer(
    /** Stable id: it is what preferences store. Never renamed. */
    val id: String,
    /** What the switch calls it. */
    val displayName: String,
    /** One line under it, in the operator's terms rather than the map's. */
    val summary: String
) {
    TRACKS(
        id = "tracks",
        displayName = "Tracks",
        summary = "The lines you spray along, drawn solid."
    ),

    ROADS(
        id = "roads",
        displayName = "Roads",
        summary = "Dashed, so a road reads as a road."
    ),

    FENCELINES(
        id = "fencelines",
        displayName = "Fencelines and stopbanks",
        summary = "Infrastructure you travel along, drawn as a dotted line."
    ),

    PLACES(
        id = "places",
        displayName = "Places",
        summary = "Houses on the map: a trough, a shelter, anything that is just one spot."
    );

    companion object {

        /** Everything that can be shown or hidden, in the order the switches list them. */
        val ALL: List<AssetLayer> = entries

        /**
         * The layers a stored set of ids hides.
         *
         * Anything unrecognised is dropped rather than kept: a preference written by a build
         * that had a layer this one does not must not hide anything and must not stop the map
         * drawing - the same reasoning as
         * [nz.mckenzie.sprayday.domain.tiles.Basemap.fromStorage]. Nothing stored hides
         * nothing, which is what makes a fresh install and an untouched switch the same thing.
         */
        fun hiddenIn(stored: Collection<String>?): Set<AssetLayer> = stored
            ?.mapNotNullTo(mutableSetOf()) { id -> entries.firstOrNull { it.id == id } }
            .orEmpty()
    }
}
