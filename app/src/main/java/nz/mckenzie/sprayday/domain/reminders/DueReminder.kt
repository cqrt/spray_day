package nz.mckenzie.sprayday.domain.reminders

import nz.mckenzie.sprayday.domain.due.DueStatus
import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/** A track as the reminder logic sees it. */
data class TrackDueState(
    val trackId: Long,
    val name: String,
    val status: DueStatus,
    val daysUntilDue: Long?,
    val createdAtEpochMs: Long
)

/**
 * What has already been said, so the same news is not repeated every day.
 *
 * The urgency each track was last mentioned at is kept, rather than just its id,
 * because a track going from "due soon" to "overdue" *is* news: that escalation is
 * the reason to have reminders at all.
 */
data class ReminderState(
    val notifiedAtEpochMs: Long? = null,
    val notified: Map<Long, DueStatus> = emptyMap()
)

/** The tracks worth mentioning now, and what to remember afterwards. */
data class ReminderPlan(
    val notify: List<TrackDueState>,
    val state: ReminderState
)

/**
 * Decides when to remind the operator about tracks that are due.
 *
 * The rules exist to avoid the two ways a reminder becomes useless: telling the
 * operator the same thing every day until they mute it, and telling them about a
 * line they drew this morning.
 *
 *  - a track is mentioned when it first becomes due, again if it gets worse (due soon
 *    turning overdue), and after that at most once a week while it stays due;
 *  - a never-sprayed track is left alone until it is as old as the lead time, because
 *    a track drawn today has not been missed - it has just been drawn.
 */
object DueReminderPlanner {

    /** How long to leave a still-due track alone before mentioning it again. */
    const val REPEAT_AFTER_DAYS = 7L

    fun plan(
        tracks: List<TrackDueState>,
        previous: ReminderState,
        nowEpochMs: Long,
        leadDays: Int,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): ReminderPlan {
        val candidates = tracks
            .filter { worthMentioning(it, nowEpochMs, leadDays, zoneId) }
            // Most overdue first, so the notification opens with the worst of it.
            .sortedBy { it.daysUntilDue ?: Long.MAX_VALUE }

        if (candidates.isEmpty()) return ReminderPlan(notify = emptyList(), state = previous)

        val neverTold = previous.notifiedAtEpochMs == null
        val somethingWorse = candidates.any { candidate ->
            val toldAt = previous.notified[candidate.trackId]
            toldAt == null || candidate.status.urgency() > toldAt.urgency()
        }
        val weekHasPassed = previous.notifiedAtEpochMs?.let { last ->
            daysBetween(last, nowEpochMs, zoneId) >= REPEAT_AFTER_DAYS
        } ?: false

        return if (neverTold || somethingWorse || weekHasPassed) {
            ReminderPlan(
                notify = candidates,
                state = ReminderState(
                    notifiedAtEpochMs = nowEpochMs,
                    notified = candidates.associate { it.trackId to it.status }
                )
            )
        } else {
            // Nothing worth saying, so nothing is recorded either: keeping the old state
            // leaves a track that joins the due list after this able to speak up.
            ReminderPlan(notify = emptyList(), state = previous)
        }
    }

    private fun worthMentioning(
        track: TrackDueState,
        nowEpochMs: Long,
        leadDays: Int,
        zoneId: ZoneId
    ): Boolean = when (track.status) {
        DueStatus.OVERDUE, DueStatus.DUE_SOON -> true
        DueStatus.NEVER_SPRAYED -> daysBetween(track.createdAtEpochMs, nowEpochMs, zoneId) >= leadDays
        DueStatus.NOT_DUE -> false
    }

    /** Never sprayed counts as overdue: nobody has ever dealt with it. */
    private fun DueStatus.urgency(): Int = when (this) {
        DueStatus.OVERDUE, DueStatus.NEVER_SPRAYED -> 2
        DueStatus.DUE_SOON -> 1
        DueStatus.NOT_DUE -> 0
    }

    private fun daysBetween(fromEpochMs: Long, toEpochMs: Long, zoneId: ZoneId): Long {
        val from = Instant.ofEpochMilli(fromEpochMs).atZone(zoneId).toLocalDate()
        val to = Instant.ofEpochMilli(toEpochMs).atZone(zoneId).toLocalDate()
        return ChronoUnit.DAYS.between(from, to)
    }
}
