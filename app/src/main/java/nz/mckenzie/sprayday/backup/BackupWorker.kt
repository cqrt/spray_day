package nz.mckenzie.sprayday.backup

import android.content.Context
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import kotlinx.coroutines.flow.first
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.BackupController
import nz.mckenzie.sprayday.data.BackupRepository
import nz.mckenzie.sprayday.data.OffsiteBackup
import nz.mckenzie.sprayday.data.OffsiteResult
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase

/**
 * The weekly off-site backup, run by WorkManager.
 *
 * WorkManager for the same reasons the reminders and the update check use it: this is
 * deferrable work that should wait for a network, survive a reboot and not fight Doze.
 *
 * The failure path is the point of the worker existing at all. A backup that has been
 * failing silently for a season is worse than no backup, because the operator believes they
 * are covered - so a failure is retried, and then said out loud.
 */
class BackupWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val appContext = applicationContext
        val settings = SettingsRepository(appContext)
        val target = BackupTargets.from(appContext, settings)

        if (target == null) {
            // Nothing is configured - off, or half set up. Not a failure: the schedule is
            // cancelled when the switch is off, and this can only be a race with that.
            Result.success()
        } else {
            val repository = BackupRepository(
                db = SprayDayDatabase.get(appContext),
                appVersion = BuildConfig.VERSION_NAME
            )
            val offsite = OffsiteBackup(
                controller = BackupController(
                    repository = repository,
                    context = appContext,
                    switches = settings
                ),
                repository = repository,
                target = target
            )

            when (val outcome = offsite.backUpNow()) {
                is OffsiteResult.Written -> {
                    settings.setLastOffsiteBackupAt(System.currentTimeMillis())
                    Result.success()
                }

                OffsiteResult.RefusedEmpty -> {
                    // Refusing is right, and is what a fresh install does until the operator
                    // restores. Worth saying only when a copy exists off-site: then it means
                    // this phone has lost what the copy still holds.
                    if (settings.lastOffsiteBackupAt.first() > 0L) {
                        tellTheOperator(
                            settings = settings,
                            title = "Nothing on this phone to back up",
                            body = "This phone holds no assets, sprays or recordings, so the " +
                                "copy in ${target.label} was left untouched. If that is not " +
                                "what you expected, restore from it in Settings."
                        )
                    }
                    Result.success()
                }

                is OffsiteResult.Failed -> {
                    tellTheOperator(
                        settings = settings,
                        title = "The backup did not happen",
                        body = "${outcome.message}\n\nThe copy in ${target.label} is from the " +
                            "last time it worked. Settings shows what it holds."
                    )
                    Result.retry()
                }
            }
        }
    } catch (failure: Throwable) {
        // A database that would not open, or a destination that cannot be built: worth
        // another try later. WorkManager caps the attempts rather than looping forever.
        Result.retry()
    }

    /**
     * Says it once, not every week.
     *
     * A backup failing for a month is worth four notifications at most, and this is the
     * difference between an operator who fixes it and one who turns the app's notifications
     * off. The deadline is stored rather than counted, so it survives a restart.
     */
    private suspend fun tellTheOperator(settings: SettingsRepository, title: String, body: String) {
        val now = System.currentTimeMillis()
        val lastTold = settings.lastOffsiteFailureNotifiedAt.first()
        if (now - lastTold < REPEAT_AFTER_MS) return

        BackupNotifier(applicationContext).notify(title, body)
        settings.setLastOffsiteFailureNotifiedAt(now)
    }

    private companion object {
        const val REPEAT_AFTER_MS = 3L * 24 * 60 * 60 * 1000
    }
}
