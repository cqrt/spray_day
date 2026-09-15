package nz.mckenzie.sprayday.domain.asset

/**
 * What an asset is and what shape it is, in the operator's words.
 *
 * Kept beside [MethodPhrase] so the form, the list and the record all say the same
 * thing about the same asset - and so a new kind cannot ship as a picker with a blank
 * label.
 */
object AssetPhrase {

    /** "Track", "Road", "Infrastructure". */
    fun kind(kind: AssetKind): String = when (kind) {
        AssetKind.TRACK -> "Track"
        AssetKind.ROAD -> "Road"
        AssetKind.INFRASTRUCTURE -> "Infrastructure"
    }

    /**
     * What the operator is choosing between for shape, in full sentences, because
     * "line" and "point" mean nothing on their own in a paddock.
     */
    fun shapeChoice(shape: AssetShape): String = when (shape) {
        AssetShape.LINE -> "Follows a path"
        AssetShape.POINT -> "Just one spot"
    }

    /** The kinds in the order the picker offers them. */
    val kinds: List<AssetKind> = AssetKind.entries.toList()

    /** The shapes in the order the picker offers them. */
    val shapes: List<AssetShape> = AssetShape.entries.toList()
}
