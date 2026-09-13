package nz.mckenzie.sprayday.domain.due

import java.time.Instant
import java.time.ZoneId
import java.time.temporal.ChronoUnit

/**
 * Traffic-light state for a track.
 *
 * [NEVER_SPRAYED] is deliberately distinct from [OVERDUE] so the map can show a
 * neutral "no history" treatment, but the UI colours it red because the track
 * still needs spraying.
 */
enum class DueStatus {
    NEVER_SPRAYED,
    OVERDUE,
    DUE_SOON,
    NOT_DUE
}

/**
 * @param dueDateEpochMs start of the due day in the calculation zone, or null if never sprayed
 * @param daysUntilDue negative when overdue, null if never sprayed
 */
data class DueInfo(
    val status: DueStatus,
    val dueDateEpochMs: Long?,
    val daysUntilDue: Long?
)

/**
 * Works out when a track is next due for spraying.
 *
 * Arithmetic is done on *calendar days* in the operator's time zone, which is
 * what a person means by "due every 120 days" - using raw milliseconds would
 * drift across daylight-saving changes.
 */
object DueCalculator {

    fun calculate(
        lastSprayedAtEpochMs: Long?,
        intervalDays: Int,
        leadDays: Int,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): DueInfo {
        if (lastSprayedAtEpochMs == null) {
            return DueInfo(status = DueStatus.NEVER_SPRAYED, dueDateEpochMs = null, daysUntilDue = null)
        }

        val lastSprayedDate = Instant.ofEpochMilli(lastSprayedAtEpochMs).atZone(zoneId).toLocalDate()
        val dueDate = lastSprayedDate.plusDays(intervalDays.toLong())
        val today = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId).toLocalDate()
        val daysUntilDue = ChronoUnit.DAYS.between(today, dueDate)

        val status = when {
            daysUntilDue < 0L -> DueStatus.OVERDUE
            daysUntilDue <= leadDays.toLong() -> DueStatus.DUE_SOON
            else -> DueStatus.NOT_DUE
        }

        return DueInfo(
            status = status,
            dueDateEpochMs = dueDate.atStartOfDay(zoneId).toInstant().toEpochMilli(),
            daysUntilDue = daysUntilDue
        )
    }
}
