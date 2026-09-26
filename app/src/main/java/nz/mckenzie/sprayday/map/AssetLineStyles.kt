package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind

/**
 * How each kind of line is drawn on the map.
 *
 * A track is a line you travel along, a road is a line with two edges, and a fenceline is a
 * series of short things - posts - so they are drawn solid, dashed and dotted in that order. The
 * five kinds that are places are not here: they are drawn as a house, and a dash pattern has
 * nothing to say about a house.
 *
 * Dash lengths are multiples of the line width rather than pixels, which is why the dotted style
 * is a very short dash with round caps: that is what a dot is, and it survives the line being
 * drawn thick or thin.
 *
 * Kept out of the map view so that which kind is dashed - a decision the operator notices - is
 * covered by a plain unit test instead of by looking at a screen.
 */
object AssetLineStyles {

    /** Dashed: the dash is longer than the gap, so it reads as a marked road. */
    val ROAD: Array<Float> = arrayOf(2.0f, 1.6f)

    /** Dotted: a speck followed by a gap the round cap turns into a dot. */
    val FENCELINE: Array<Float> = arrayOf(0.05f, 1.7f)

    /**
     * Null means solid - which is also what every place gets, because a place is one point and
     * a dash pattern is a thing a line has. Nothing draws a place with a line layer, so this is
     * a statement about the kind rather than about a layer that exists.
     */
    fun forKind(kind: AssetKind): Array<Float>? = when (kind) {
        AssetKind.TRACK -> null
        AssetKind.ROAD -> ROAD
        AssetKind.FENCELINE -> FENCELINE
        // Solid, like a track, and for the opposite reason: a boundary is not a dash pattern. The edge
        // of a piece of ground goes right round it, and what tells a carpark from a track on the map is
        // the shape - a closed one, filled in its due colour - not the dash it is drawn with.
        AssetKind.CARPARK -> null
        AssetKind.BUILDING,
        AssetKind.SIGN,
        AssetKind.BENCH,
        AssetKind.TABLE,
        AssetKind.OTHER_PLACE -> null
    }
}
