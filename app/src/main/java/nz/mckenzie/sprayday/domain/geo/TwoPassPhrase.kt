package nz.mckenzie.sprayday.domain.geo

/**
 * What the recorder says about a line that takes two passes.
 *
 * The recorder is the only place the operator can be told that the pass they have just walked is
 * half the job - the traffic light stays where it was, and a screen that said nothing about why
 * would read as the app having ignored the work. Kept out of the screen so the sentences can be
 * tested without a device, like the due phrases are.
 */
object TwoPassPhrase {

    /**
     * What the card says about the line itself: before a pass, during one, and after one.
     *
     * Nothing at all for a line nothing has been along: its state is what the traffic light and
     * the planned line already say, and a sentence would only be repeating them.
     */
    fun state(result: TwoPasses.Result?): String? = when {
        result == null -> null
        result.isComplete -> "Both passes done"
        // Nothing has been along it at all: there is no half-done state to explain.
        result.doneM <= 0.0 && result.onePassM <= 0.0 && result.ambiguousM <= 0.0 -> null
        else -> "One pass still to go"
    }

    /**
     * What finishing a pass left owing, for the message on the card.
     *
     * Called only when the line is not done, so there is always something to say. The two shapes
     * are worth keeping apart: a line the pass went along once is owed the other pass, and a pass
     * that never came near the line has left the job exactly where it found it.
     */
    fun notRecorded(result: TwoPasses.Result): String =
        if (result.onePassM > 0.0 || result.ambiguousM > 0.0) {
            "one pass still to go, so no spray was recorded yet"
        } else {
            "the pass did not come near the line, so no spray was recorded"
        }
}
