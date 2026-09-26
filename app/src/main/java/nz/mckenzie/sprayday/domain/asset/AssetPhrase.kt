package nz.mckenzie.sprayday.domain.asset

/**
 * What an asset is and what shape it is, in the operator's words.
 *
 * Kept beside [MethodPhrase] so the form, the list and the record all say the same
 * thing about the same asset - and so a new kind cannot ship as a picker with a blank
 * label.
 */
object AssetPhrase {

    /**
     * "Track", "Road", "Fenceline", "Carpark", "Building", "Sign", "Bench seat", "Picnic table",
     * "Other place".
     */
    fun kind(kind: AssetKind): String = when (kind) {
        AssetKind.TRACK -> "Track"
        AssetKind.ROAD -> "Road"
        AssetKind.FENCELINE -> "Fenceline"
        AssetKind.CARPARK -> "Carpark"
        AssetKind.BUILDING -> "Building"
        AssetKind.SIGN -> "Sign"
        AssetKind.BENCH -> "Bench seat"
        AssetKind.TABLE -> "Picnic table"
        AssetKind.OTHER_PLACE -> "Other place"
    }

    /**
     * The kinds in the order the picker offers them: the lines to travel along, then the ground with
     * an edge, then the places to stop at.
     */
    val kinds: List<AssetKind> = AssetKind.entries.toList()

    /**
     * What the metres of a shape are called: a line has a length, and a ring has the metres round it.
     *
     * A phrase of its own rather than a word buried in a screen, because the card, the drawing screen
     * and the desk's card are all making the same statement about the same number.
     */
    fun lengthLabel(shape: AssetShape): String = when (shape) {
        AssetShape.AREA -> "Round it"
        else -> "Length"
    }

    /**
     * What changing an asset's shape is called.
     *
     * A line is redrawn; ground is re-fenced. The card's own action and the drawing screen's title are
     * the same errand and so the same words - and before this was one place, the drawing screen told
     * the operator they were about to "change the line" of a carpark.
     */
    fun changeLabel(shape: AssetShape): String = when (shape) {
        AssetShape.AREA -> "Change the boundary"
        else -> "Change the line"
    }

    /**
     * How a figure for ground is said, given the number already written out.
     *
     * Two claims, and the words have to tell them apart: a ring's own area is **measured** from its
     * corners, while a line's is an estimate from a swath width somebody typed - and an estimate that
     * reads like a measurement is the kind of figure that ends up in a spray diary as though it had
     * been surveyed.
     */
    fun areaPhrase(shape: AssetShape, areaText: String): String = when (shape) {
        AssetShape.AREA -> "$areaText of ground"
        else -> "about $areaText"
    }
}
