package nz.mckenzie.sprayday.domain.due

/**
 * How to say when a track is due, in the operator's words.
 *
 * Shared by the track screen and the due reminder, so the notification and the
 * traffic light cannot drift apart in what they call the same state.
 */
object DuePhrase {

    fun of(status: DueStatus, daysUntilDue: Long?): String = when (status) {
        DueStatus.NEVER_SPRAYED -> "Never sprayed"

        DueStatus.OVERDUE, DueStatus.DUE_SOON, DueStatus.NOT_DUE -> {
            val days = daysUntilDue ?: 0L
            when {
                days < 0L -> "${-days} days overdue"
                days == 0L -> "Due today"
                days == 1L -> "Due tomorrow"
                else -> "Due in $days days"
            }
        }
    }
}
