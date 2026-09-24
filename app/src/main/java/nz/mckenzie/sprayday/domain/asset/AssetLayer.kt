package nz.mckenzie.sprayday.domain.asset

/**
 * One layer of the work on the map, as the operator's switches see it.
 *
 * **One layer per kind of asset** - eight of them, the same eight the list's chips offer - because
 * the reason to hide a layer is to read another: a road network under a set of fencelines is noise,
 * and forty troughs on a block bury the two buildings that are the thing being looked for. Hiding
 * "places" was not enough for the second of those, so a place kind can be hidden on its own.
 *
 * [kind] is the kind the layer draws, which is what the map's own style filters on, and what the
 * marker's shape and colour are read from. It is the same enum the picker, the list and the record
 * screens use: a layer per kind, a chip per kind, one vocabulary.
 *
 * **What a preference stores is what is hidden, not what is shown.** Everything is drawn until
 * somebody says otherwise, so the map is what it always was for anyone who never opens this, and a
 * layer added in a later build arrives visible rather than missing: an operator who has never touched
 * a switch must not lose a layer because a new one turned up.
 */
enum class AssetLayer(
    /** Stable id: it is what preferences store. Never renamed. */
    val id: String,
    /** What the switch calls it. */
    val displayName: String,
    /** One line under it, in the operator's terms rather than the map's. */
    val summary: String,
    /** What the layer draws. */
    val kind: AssetKind
) {
    TRACKS(
        id = "tracks",
        displayName = "Tracks",
        summary = "The lines you spray along, drawn solid.",
        kind = AssetKind.TRACK
    ),

    ROADS(
        id = "roads",
        displayName = "Roads",
        summary = "Dashed, so a road reads as a road.",
        kind = AssetKind.ROAD
    ),

    FENCELINES(
        id = "fencelines",
        displayName = "Fencelines and stopbanks",
        summary = "Infrastructure you travel along, drawn as a dotted line.",
        kind = AssetKind.FENCELINE
    ),

    BUILDINGS(
        id = "buildings",
        displayName = "Buildings",
        summary = "A roof over walls, in the colour its traffic light shows.",
        kind = AssetKind.BUILDING
    ),

    SIGNS(
        id = "signs",
        displayName = "Signs",
        summary = "A plate on a post.",
        kind = AssetKind.SIGN
    ),

    BENCHES(
        id = "bench-seats",
        displayName = "Bench seats",
        summary = "Two bars over straight legs.",
        kind = AssetKind.BENCH
    ),

    TABLES(
        id = "picnic-tables",
        displayName = "Picnic tables",
        summary = "One top on splayed legs - splayed is what tells it from a seat.",
        kind = AssetKind.TABLE
    ),

    OTHER_PLACES(
        id = "other-places",
        displayName = "Other places",
        summary = "A dot in a ring: a trough, a tank, a gate.",
        kind = AssetKind.OTHER_PLACE
    );

    companion object {

        /** Everything that can be shown or hidden, in the order the switches list them. */
        val ALL: List<AssetLayer> = entries

        /**
         * The id an older build stored for the one switch that hid every place.
         *
         * Before this release there were four switches and the fifth kind of thing was "places".
         * An operator who hid them meant the troughs, the sheds and the signs alike, so the id is
         * read back as the five place kinds rather than dropped - a hidden layer coming back is a
         * map that has quietly changed under somebody.
         */
        private const val LEGACY_PLACES = "places"

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
            ?.flatMapTo(mutableSetOf()) { id -> layersFor(id) }
            .orEmpty()

        /** The layers one stored id names: one, or - the old "places" - all five of them. */
        private fun layersFor(id: String): List<AssetLayer> = if (id == LEGACY_PLACES) {
            entries.filter { it.kind.shape == AssetShape.POINT }
        } else {
            entries.filter { it.id == id }
        }
    }
}
