package nz.mckenzie.sprayday.domain.reminders

import nz.mckenzie.sprayday.domain.due.DuePhrase
import nz.mckenzie.sprayday.domain.due.DueStatus

/**
 * What the reminder notification says.
 *
 * Kept away from the Android notification code so the wording can be tested: this is
 * the only part of the feature the operator actually reads.
 */
object ReminderMessage {

    /** Beyond this the body says how many more, rather than running off the screen. */
    const val MAX_LINES = 5

    fun title(assets: List<AssetDueState>): String {
        val late = assets.count { it.status.isLate() }
        val soon = assets.count { it.status == DueStatus.DUE_SOON }

        return when {
            assets.isEmpty() -> "No tracks due"
            soon == 0 ->
                if (late == 1) "1 track is due for spraying" else "$late tracks are due for spraying"
            late == 0 ->
                if (soon == 1) "1 track is due soon" else "$soon tracks are due soon"
            else -> "$late due for spraying, $soon due soon"
        }
    }

    /** One line per track: "Home block - 12 days overdue". */
    fun body(assets: List<AssetDueState>): String {
        val shown = assets.take(MAX_LINES).joinToString("\n") { track ->
            "${track.name} \u2014 ${DuePhrase.of(track.status, track.daysUntilDue)}"
        }
        val hidden = assets.size - MAX_LINES
        return if (hidden > 0) "$shown\nand $hidden more" else shown
    }

    private fun DueStatus.isLate(): Boolean =
        this == DueStatus.OVERDUE || this == DueStatus.NEVER_SPRAYED
}
