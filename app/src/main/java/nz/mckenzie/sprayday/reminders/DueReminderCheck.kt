package nz.mckenzie.sprayday.reminders

import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import nz.mckenzie.sprayday.data.ReminderStateStore
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.reminders.DueReminderPlanner
import nz.mckenzie.sprayday.domain.reminders.ReminderMessage
import nz.mckenzie.sprayday.domain.reminders.AssetDueState
import java.time.ZoneId

/** What one reminder check did, in words worth putting on the settings screen. */
data class ReminderOutcome(
    /** How many tracks are due or overdue, whether or not anything was said. */
    val dueCount: Int,
    /** How many tracks the notification was about; 0 when none was posted. */
    val notifiedCount: Int,
    val posted: Boolean,
    val message: String
)

/**
 * One pass of "is anything due, and should I say so?".
 *
 * Shared by the background worker and the settings screen's Check now button, so the
 * button reports exactly what the background job would have done.
 */
class DueReminderCheck(
    private val assetRepository: AssetRepository,
    private val store: ReminderStateStore,
    /** Posts the notification and says whether it actually appeared. */
    private val post: (title: String, body: String) -> Boolean,
    private val zoneId: ZoneId = ZoneId.systemDefault()
) {

    constructor(
        assetRepository: AssetRepository,
        store: ReminderStateStore,
        notifier: ReminderNotifier,
        zoneId: ZoneId = ZoneId.systemDefault()
    ) : this(assetRepository, store, notifier::notify, zoneId)

    suspend fun run(nowEpochMs: Long = System.currentTimeMillis()): ReminderOutcome {
        val due = assetRepository.observeAssetsWithDue(nowProvider = flowOf(nowEpochMs)).first()
            .map { it.toDueState() }
        val dueCount = due.count { it.status != DueStatus.NOT_DUE }

        val plan = DueReminderPlanner.plan(
            assets = due,
            previous = store.state.first(),
            nowEpochMs = nowEpochMs,
            leadDays = AssetEntity.DEFAULT_LEAD_DAYS,
            zoneId = zoneId
        )

        if (plan.notify.isEmpty()) {
            return ReminderOutcome(
                dueCount = dueCount,
                notifiedCount = 0,
                posted = false,
                message = when {
                    due.isEmpty() -> "No assets to check yet."
                    dueCount == 0 -> "Nothing is due."
                    else -> "$dueCount due, and the last reminder already covered them."
                }
            )
        }

        val posted = post(ReminderMessage.title(plan.notify), ReminderMessage.body(plan.notify))
        // Only remember it if it was actually shown: otherwise turning notifications on
        // later would find a week of reminders already "said".
        if (posted) store.save(plan.state)

        return ReminderOutcome(
            dueCount = dueCount,
            notifiedCount = plan.notify.size,
            posted = posted,
            message = if (posted) {
                "Reminded you about ${plan.notify.size} asset${if (plan.notify.size == 1) "" else "s"}."
            } else {
                "There is something to say, but notifications are turned off for Spray Day."
            }
        )
    }
}

private fun AssetWithDue.toDueState() = AssetDueState(
    assetId = asset.id,
    name = asset.name,
    status = due.status,
    daysUntilDue = due.daysUntilDue,
    createdAtEpochMs = asset.createdAtEpochMs
)
