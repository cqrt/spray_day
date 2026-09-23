package nz.mckenzie.sprayday.domain.asset

/**
 * What an asset is and what shape it is, in the operator's words.
 *
 * Kept beside [MethodPhrase] so the form, the list and the record all say the same
 * thing about the same asset - and so a new kind cannot ship as a picker with a blank
 * label.
 */
object AssetPhrase {

    /** "Track", "Road", "Fenceline", "Building", "Sign", "Bench seat", "Picnic table", "Other place". */
    fun kind(kind: AssetKind): String = when (kind) {
        AssetKind.TRACK -> "Track"
        AssetKind.ROAD -> "Road"
        AssetKind.FENCELINE -> "Fenceline"
        AssetKind.BUILDING -> "Building"
        AssetKind.SIGN -> "Sign"
        AssetKind.BENCH -> "Bench seat"
        AssetKind.TABLE -> "Picnic table"
        AssetKind.OTHER_PLACE -> "Other place"
    }

    /** The kinds in the order the picker offers them: the lines first, then the places. */
    val kinds: List<AssetKind> = AssetKind.entries.toList()
}
