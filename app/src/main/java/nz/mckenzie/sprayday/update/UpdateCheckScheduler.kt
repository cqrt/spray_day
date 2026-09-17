package nz.mckenzie.sprayday.update

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the background update check in step with the setting.
 *
 * `KEEP` for the same reason the reminders use it: this is called once per launch as well
 * as when the setting changes, and without it every app start would reset the period and
 * the check would never actually run.
 */
object UpdateCheckScheduler {

    const val WORK_NAME = "spray-day-update-check"

    /**
     * Once a day.
     *
     * GitHub allows sixty unauthenticated calls an hour from one address, so a daily check
     * costs nothing against that; and a new version is worth hearing about within a day
     * without being worth waking the radio up more often than the weather.
     */
    private const val PERIOD_HOURS = 24L

    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)

        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<UpdateCheckWorker>(PERIOD_HOURS, TimeUnit.HOURS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
