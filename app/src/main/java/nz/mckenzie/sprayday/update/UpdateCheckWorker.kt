package nz.mckenzie.sprayday.update

import android.content.Context
import android.os.Build
import androidx.work.CoroutineWorker
import androidx.work.WorkerParameters
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.domain.update.AppVersion

/**
 * The background check for a newer version.
 *
 * WorkManager for the same reasons the reminders use it: this is deferrable work, and it
 * already knows how to survive a reboot and wait until the phone is out of Doze.
 */
class UpdateCheckWorker(
    context: Context,
    params: WorkerParameters
) : CoroutineWorker(context, params) {

    override suspend fun doWork(): Result = try {
        val appContext = applicationContext
        UpdateCheck(
            current = AppVersion.parse(BuildConfig.VERSION_NAME),
            supportedAbis = Build.SUPPORTED_ABIS.toList(),
            feed = GitHubReleaseFeed(),
            settings = SettingsRepository(appContext),
            notifier = UpdateNotifier(appContext)
        ).run()
        Result.success()
    } catch (failure: Throwable) {
        // GitHub being unreachable is worth another try later; WorkManager caps the attempts.
        Result.retry()
    }
}
