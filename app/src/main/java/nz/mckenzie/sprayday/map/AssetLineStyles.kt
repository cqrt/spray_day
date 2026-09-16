package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind

/**
 * How each kind of asset is drawn on the map.
 *
 * A track is a line you travel along, a road is a line with two edges, and
 * infrastructure is a series of short things - a fenceline, a shelter, a table - so
 * they are drawn solid, dashed and dotted in that order.
 *
 * Dash lengths are multiples of the line width rather than pixels, which is why the
 * dotted style is a very short dash with round caps: that is what a dot is, and it
 * survives the line being drawn thick or thin.
 *
 * Kept out of the map view so that which kind is dashed - a decision the operator
 * notices - is covered by a plain unit test instead of by looking at a screen.
 */
object AssetLineStyles {

    /** Dashed: the dash is longer than the gap, so it reads as a marked road. */
    val ROAD: Array<Float> = arrayOf(2.0f, 1.6f)

    /** Dotted: a speck followed by a gap the round cap turns into a dot. */
    val INFRASTRUCTURE: Array<Float> = arrayOf(0.05f, 1.7f)

    /** Null means solid: no dash array at all. */
    fun forKind(kind: AssetKind): Array<Float>? = when (kind) {
        AssetKind.TRACK -> null
        AssetKind.ROAD -> ROAD
        AssetKind.INFRASTRUCTURE -> INFRASTRUCTURE
    }
}
