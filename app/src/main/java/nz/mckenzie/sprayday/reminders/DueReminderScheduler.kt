package nz.mckenzie.sprayday.reminders

import android.content.Context
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the background reminder check in step with the setting.
 *
 * Called whenever the setting changes and once per launch, so `KEEP` matters: without
 * it, every app start would reset the period and the check would never actually run.
 */
object DueReminderScheduler {

    const val WORK_NAME = "spray-day-due-reminders"

    /**
     * Twice a day. A spray window is measured in days, not hours, and the planner
     * decides whether anything is worth saying.
     */
    private const val PERIOD_HOURS = 12L

    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)

        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<DueReminderWorker>(PERIOD_HOURS, TimeUnit.HOURS).build()
        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
