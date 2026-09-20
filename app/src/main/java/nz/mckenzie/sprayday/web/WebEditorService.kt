package nz.mckenzie.sprayday.web

import android.app.Notification
import android.app.NotificationChannel
import android.app.NotificationManager
import android.app.PendingIntent
import android.app.Service
import android.content.Context
import android.content.Intent
import android.content.pm.ServiceInfo
import android.os.Build
import android.os.IBinder
import android.util.Log
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.flow.MutableStateFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.MainActivity
import nz.mckenzie.sprayday.R
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.offline.LocalTileServer
import nz.mckenzie.sprayday.offline.TileServerHolder
import nz.mckenzie.sprayday.tracking.DevicePosition

/**
 * The editor, served while the switch on the Settings screen is on.
 *
 * A foreground service for the same reason the recorder is one: the phone must keep serving while
 * the screen is off and the operator is reading the page on the laptop, and Android stops anything
 * that is not foreground. The type is `dataSync` rather than `location`, because what it does is
 * serve data - it *reads* the phone's last fix for the desk's locate button, which is not the same
 * as tracking the operator around a paddock.
 *
 * Everything it needs it takes from where the app already keeps it: the database through
 * `SprayDayDatabase`, the basemap from the settings the operator chose, the tiles from
 * [TileServerHolder] - so the tiles a laptop draws are the tiles already downloaded for the
 * tractor - and the fix from [DevicePosition]. The token is made here, once, and lives only here
 * and in the running server: turning the switch off ends it, as it should for something that is
 * opened by its own address.
 */
class WebEditorService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    private var server: WebEditorServer? = null
    private var positionJob: Job? = null

    /** The newest fix, for as long as the editor is on - see [DevicePosition.updates]. */
    private val position = MutableStateFlow<GeoPoint?>(null)

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> start()
            ACTION_STOP -> stopSelf()
        }
        // Not sticky: a service that resurrected itself would put the phone back on the farm's
        // network with nobody having asked - and with a token the operator no longer has.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        server?.stop()
        server = null
        positionJob?.cancel()
        scope.cancel()
        WebEditorState.off()
        super.onDestroy()
    }

    private fun start() {
        if (server != null) return

        // The address the laptop will reach, which is one specific address on the Wi-Fi rather than
        // every interface the phone has: binding to all of them would put the editor on mobile data
        // as well, and the plan's promise is a door on the farm's network.
        val address = WebEditorLink.gather().firstOrNull()?.address
        if (address == null) {
            // Plain English rather than a stack trace: the operator has just thrown a switch and
            // nothing happened, so the log says the one thing that explains it.
            Log.w(TAG, "No address on the Wi-Fi, so the editor cannot be served")
            stopSelf()
            return
        }

        val token = WebEditorLink.newToken()
        val database = SprayDayDatabase.get(this)
        val settings = SettingsRepository(this)

        val documents = WebEditorDocuments(
            assets = AssetRepository(database),
            sprays = SprayRepository(database),
            token = token,
            basemap = { settings.basemap.first() },
            position = { position.value },
            readPageFile = ::readPageFile
        )

        val started = WebEditorServer(
            // Every interface the phone has, rather than the Wi-Fi one on its own - which is what
            // makes the address on the card work whichever way the laptop reaches it, and what lets
            // `adb forward` reach it for the checks in the plan. The tile server's loopback promise
            // is a different server and is untouched; what guards this one is the token, and the
            // address the operator is given is still the Wi-Fi one, because that is the one a
            // laptop on the same network can use.
            host = WebEditorServer.ANY_ADDRESS,
            token = token,
            data = documents,
            // The app's own tile route, from the app's own stores: one handler, one set of tiles.
            tileRoute = LocalTileServer.tileRoute(TileServerHolder.sources(this)),
            requestedPort = WebEditorLink.DEFAULT_PORT
        )

        val bound = runCatching { started.start() }
        if (bound.isFailure) {
            Log.w(TAG, "The editor's server would not bind on $address", bound.exceptionOrNull())
            stopSelf()
            return
        }

        server = started
        // The fix is read for as long as the editor is on, so the desk's locate button asks the
        // phone rather than the browser - which a browser blocks on an insecure origin anyway.
        positionJob = scope.launch { DevicePosition.updates(scope).collect { position.value = it } }
        WebEditorState.servingAt(WebEditorLink.urlFor(address, bound.getOrThrow(), token))
        goForeground()
    }

    private fun readPageFile(name: String): ByteArray? =
        runCatching { assets.open("$PAGE_DIR/$name").use { it.readBytes() } }.getOrNull()

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_DATA_SYNC
            } else {
                0
            }
        )
    }

    /**
     * The notification carries the address rather than a way to stop: the operator is at a computer
     * and the switch is on the phone in their pocket. It is the shade where the address is found
     * when the laptop is not the one they read the card on.
     */
    private fun buildNotification(): Notification {
        val open = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.web_editor_notification_title))
            .setContentText(getString(R.string.web_editor_notification_text, WebEditorState.url.value.orEmpty()))
            .setSmallIcon(R.drawable.ic_stat_web)
            .setOngoing(true)
            .setContentIntent(open)
            .setOnlyAlertOnce(true)
            .build()
    }

    private fun createChannel() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.O) return
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.web_editor_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.web_editor_channel_description)
        }
        val manager = getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager
        manager.createNotificationChannel(channel)
    }

    companion object {
        const val CHANNEL_ID = "sprayday-web-editor"
        const val NOTIFICATION_ID = 4202

        /** Where the page lives inside the APK. */
        private const val PAGE_DIR = "web"

        private const val ACTION_START = "nz.mckenzie.sprayday.action.START_WEB_EDITOR"
        private const val ACTION_STOP = "nz.mckenzie.sprayday.action.STOP_WEB_EDITOR"

        private const val TAG = "SprayDayWebEditor"

        /** Turns the editor on, and leaves it on until [stop] - the switch on the Settings screen. */
        fun start(context: Context) {
            ContextCompat.startForegroundService(
                context,
                Intent(context, WebEditorService::class.java).setAction(ACTION_START)
            )
        }

        fun stop(context: Context) {
            context.startService(Intent(context, WebEditorService::class.java).setAction(ACTION_STOP))
        }
    }
}
