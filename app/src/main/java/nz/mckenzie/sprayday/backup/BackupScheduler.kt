package nz.mckenzie.sprayday.backup

import android.content.Context
import androidx.work.Constraints
import androidx.work.ExistingPeriodicWorkPolicy
import androidx.work.NetworkType
import androidx.work.PeriodicWorkRequestBuilder
import androidx.work.WorkManager
import java.util.concurrent.TimeUnit

/**
 * Keeps the automatic off-site backup in step with the setting.
 *
 * `KEEP` for the same reason the reminders and the update check use it: this is called once
 * per launch as well as when the setting changes, and without it every app start would reset
 * the period and the backup would never actually run.
 */
object BackupScheduler {

    const val WORK_NAME = "spray-day-offsite-backup"

    /**
     * Once a week.
     *
     * A season does not change by the hour, and what this costs the operator is their data
     * allowance on a farm connection - so this is the shortest period that is still honestly
     * called protection. Days are aligned so it does not run at an awkward hour.
     */
    private const val PERIOD_DAYS = 7L

    fun sync(context: Context, enabled: Boolean) {
        val workManager = WorkManager.getInstance(context)

        if (!enabled) {
            workManager.cancelUniqueWork(WORK_NAME)
            return
        }

        val request = PeriodicWorkRequestBuilder<BackupWorker>(PERIOD_DAYS, TimeUnit.DAYS)
            .setConstraints(
                Constraints.Builder()
                    .setRequiredNetworkType(NetworkType.CONNECTED)
                    .build()
            )
            .build()

        workManager.enqueueUniquePeriodicWork(WORK_NAME, ExistingPeriodicWorkPolicy.KEEP, request)
    }
}
