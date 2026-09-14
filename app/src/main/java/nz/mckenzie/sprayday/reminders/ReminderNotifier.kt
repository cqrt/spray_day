package nz.mckenzie.sprayday.reminders

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
 * Posts the due reminder.
 *
 * Thin on purpose: the wording comes from
 * [nz.mckenzie.sprayday.domain.reminders.ReminderMessage], which is pure and tested,
 * so this only has to get it onto the screen. Tapping it opens the track list, since
 * that is where the work it describes happens.
 */
class ReminderNotifier(private val context: Context) {

    /** Returns false when the operator has notifications turned off for the app. */
    @SuppressLint("MissingPermission") // Posting without the permission is a silent no-op.
    fun notify(title: String, body: String): Boolean {
        if (!NotificationManagerCompat.from(context).areNotificationsEnabled()) return false

        createChannel()

        val openTracks = PendingIntent.getActivity(
            context,
            0,
            Intent(context, MainActivity::class.java)
                .addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP)
                .putExtra(MainActivity.EXTRA_DESTINATION, MainActivity.DESTINATION_TRACKS),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val notification = NotificationCompat.Builder(context, CHANNEL_ID)
            .setContentTitle(title)
            .setContentText(body.lineSequence().firstOrNull().orEmpty())
            .setStyle(NotificationCompat.BigTextStyle().bigText(body))
            .setSmallIcon(R.drawable.ic_stat_due)
            .setContentIntent(openTracks)
            .setAutoCancel(true)
            .setPriority(NotificationCompat.PRIORITY_DEFAULT)
            .build()

        NotificationManagerCompat.from(context).notify(NOTIFICATION_ID, notification)
        return true
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            context.getString(R.string.due_channel_name),
            NotificationManager.IMPORTANCE_DEFAULT
        ).apply {
            description = context.getString(R.string.due_channel_description)
        }
        val manager = context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sprayday-due"
        const val NOTIFICATION_ID = 4301
    }
}
