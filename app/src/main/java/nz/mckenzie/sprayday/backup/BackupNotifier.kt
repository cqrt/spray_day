package nz.mckenzie.sprayday.backup

import android.annotation.SuppressLint
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import androidx.core.app.NotificationCompat
import androidx.core.app.NotificationManagerCompat
import nz.mckenzie.sprayday.MainActivity
import nz.mckenzie.sprayday.R

/**
 * Posts "the backup did not happen".
 *
 * A backup nobody hears about is a backup that has been failing since March. This is the
 * one notification the off-site feature sends unprompted, and it exists because silence is
 * the failure mode that costs a season: the operator believes they are covered.
 *
 * Tapping it opens Settings, where the destination and the last backup are.
 */
class BackupNotifier(private val context: Context) {

    /** Returns false when the operator has notifications turned off for the app. */
    @SuppressLint("MissingPermission") // Posting without the permission is a silent no-op.
    fun notify(title: String, body: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        createChannel()

        // A request code of its own: pending intents are matched without their extras, so
        // sharing one with the update notification would have them replacing each other.
        val openSettings = PendingIntent.getActivity(
            context,
            REQUEST_CODE,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_SETTINGS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_backup)
            .setContentIntent(openSettings)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.backup_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.backup_channel_description)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sprayday-backups"
        const val NOTIFICATION_ID = 4303
        private const val REQUEST_CODE = 2
    }
}
