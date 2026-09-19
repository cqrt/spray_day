package nz.mckenzie.sprayday.domain.asset

import java.util.Locale

/**
 * How to say how many passes a line's job takes, in the operator's words.
 *
 * The words matter more than they look: "two passes" is what tells the operator that half an
 * hour's work on a track is not the job, and the summary line is the only place the app says so
 * outside the recorder. Kept here rather than in the two screens that read it, so the form and
 * the asset page cannot drift apart on what they call it.
 */
object PassPhrase {

    /** What to offer in the picker: one pass, or two. */
    val choices: List<Int> = listOf(ONE_PASS, TWO_PASSES)

    /** "One pass" / "Two passes" - the choice as it is picked. */
    fun choice(passesRequired: Int): String =
        if (passesRequired >= TWO_PASSES) "Two passes" else "One pass"

    /**
     * What the asset page says about it, or null when there is nothing worth saying.
     *
     * A line sprayed in one pass is every line in the app, so saying "one pass" on all of them
     * would be noise; only a line that takes two is worth the line of text, and then how far
     * apart the two run is what the app's side-reading rests on.
     */
    fun detail(passesRequired: Int, separationM: Double?): String? = when {
        passesRequired < TWO_PASSES -> null
        separationM == null -> "Two passes"
        else -> "Two passes, about ${metres(separationM)} m apart"
    }

    /** What the field under the choice explains, so the number means something. */
    const val SEPARATION_HINT: String =
        "How far apart the two passes run. Leave empty if you do not know: the app will go by " +
            "which way each pass was heading, and ask when it cannot tell."

    /**
     * The two counts, as words.
     *
     * [nz.mckenzie.sprayday.data.db.AssetEntity] holds the same two numbers for the database, and
     * a test keeps these and those in step: the form shows these words, and the stored field has
     * to mean the same thing, or the operator picks "two passes" and gets an asset that thinks
     * it takes one.
     */
    const val ONE_PASS = 1
    const val TWO_PASSES = 2

    /** "3" / "2.5" - a distance without a trailing zero. */
    private fun metres(value: Double): String =
        if (value == Math.floor(value) && !value.isInfinite()) {
            value.toInt().toString()
        } else {
            String.format(Locale.US, "%.1f", value)
        }
}
