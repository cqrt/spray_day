package nz.mckenzie.sprayday.update

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
 * Posts "there is a newer version".
 *
 * Tapping it opens Settings, which is where the update is. The operator asked to be told,
 * not to have a download start behind their back on a farm connection - so the notification
 * is news, and the tap that follows is the decision.
 */
class UpdateNotifier(private val context: Context) {

    /** Returns false when the operator has notifications turned off for the app. */
    @SuppressLint("MissingPermission") // Posting without the permission is a silent no-op.
    fun notify(title: String, body: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        createChannel()

        // A request code of its own, because pending intents are matched without their
        // extras: sharing the reminder's code would mean this one replacing the reminder's
        // "open the asset list" tap with "open Settings", or the other way round.
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
            .setSmallIcon(R.drawable.ic_stat_update)
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
            context.getString(R.string.update_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.update_channel_description)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sprayday-updates"
        const val NOTIFICATION_ID = 4302
        private const val REQUEST_CODE = 1
    }
}
