package nz.mckenzie.sprayday.tracking

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
import androidx.core.app.NotificationCompat
import androidx.core.app.ServiceCompat
import androidx.core.content.ContextCompat
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.Job
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import nz.mckenzie.sprayday.MainActivity
import nz.mckenzie.sprayday.R
import nz.mckenzie.sprayday.data.RecordingRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.TrackPointFilter
import nz.mckenzie.sprayday.domain.geo.haversineMeters
import nz.mckenzie.sprayday.domain.geo.polylineLengthMeters
import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.ui.formatDistance

/**
 * Records a GPS track in the foreground so the screen can be off for the whole
 * spray run.
 *
 * Every accepted fix is written to the database as it arrives, so a crash, a
 * process kill or a flat battery costs at most the last fix. Accepted fixes are
 * filtered by [TrackPointFilter]; rejections are counted and surfaced in the UI
 * so a sparse-looking track can be explained rather than just noticed.
 *
 * Deliberately uses the `location` foreground service type: it is not subject to
 * the six-hour cap that applies to `dataSync`/`mediaProcessing`, and it does not
 * need `ACCESS_BACKGROUND_LOCATION` because recording only starts from the
 * visible UI.
 */
class TrackingService : Service() {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.Default)
    private var filter = TrackPointFilter()
    private var collectionJob: Job? = null
    private var distanceM = 0.0

    private lateinit var recordings: RecordingRepository
    private lateinit var locationSource: LocationSource

    private var sessionId: Long = -1L
    private var status: RecordingStatus = RecordingStatus.RECORDING

    override fun onBind(intent: Intent?): IBinder? = null

    override fun onCreate() {
        super.onCreate()
        recordings = RecordingRepository(SprayDayDatabase.get(this))
        locationSource = FusedLocationSource(this)
        createChannel()
    }

    override fun onStartCommand(intent: Intent?, flags: Int, startId: Int): Int {
        when (intent?.action) {
            ACTION_START -> {
                val requested = intent.getLongExtra(EXTRA_SESSION_ID, -1L)
                if (requested <= 0L) {
                    stopSelf()
                    return START_NOT_STICKY
                }
                // A second START for the session already being recorded is what a
                // re-created Record screen sends. Rewinding the counters for it would put
                // "0 m" beside a coverage measured from the whole pass - and the coverage
                // would be the honest of the two - so the recording simply carries on.
                if (stillRecording(requested, sessionId, collectionJob?.isActive == true)) {
                    goForeground()
                    return START_NOT_STICKY
                }
                sessionId = requested
                // A fresh session means a fresh filter and distance; one that already has
                // fixes in it is picked up from them instead.
                filter = TrackPointFilter()
                distanceM = 0.0
                status = RecordingStatus.RECORDING
                goForeground()
                startCollecting()
            }

            ACTION_PAUSE -> updateStatus(RecordingStatus.PAUSED)
            ACTION_RESUME -> updateStatus(RecordingStatus.RECORDING)
            ACTION_STOP -> stopSelf()
        }
        // Not sticky: a recorder should never resurrect itself with no context.
        return START_NOT_STICKY
    }

    override fun onDestroy() {
        collectionJob?.cancel()
        scope.cancel()
        super.onDestroy()
    }

    private fun updateStatus(newStatus: RecordingStatus) {
        status = newStatus
        TrackingState.setStatus(newStatus)
        scope.launch { recordings.setStatus(sessionId, newStatus) }

        if (newStatus == RecordingStatus.RECORDING) {
            goForeground()
            startCollecting()
        } else {
            collectionJob?.cancel()
            collectionJob = null
            goForeground()
        }
    }

    private fun goForeground() {
        ServiceCompat.startForeground(
            this,
            NOTIFICATION_ID,
            buildNotification(),
            if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.Q) {
                ServiceInfo.FOREGROUND_SERVICE_TYPE_LOCATION
            } else {
                0
            }
        )
    }

    private fun startCollecting() {
        collectionJob?.cancel()
        collectionJob = scope.launch {
            resumeFromWhatIsRecorded()
            locationSource.updates().collect { fix ->
                if (status != RecordingStatus.RECORDING) return@collect

                val previous = filter.lastAcceptedPoint
                if (!filter.accept(fix)) {
                    TrackingState.onRejectedFixes(
                        filter.rejectedAccuracyCount +
                            filter.rejectedDistanceCount +
                            filter.rejectedSpeedCount
                    )
                    return@collect
                }

                previous?.let { last ->
                    distanceM += haversineMeters(last.lat, last.lng, fix.lat, fix.lng)
                }

                // Persist first, then report: losing a fix matters more than a
                // lagging notification.
                recordings.appendPoint(sessionId, fix)
                TrackingState.onAcceptedFix(filter.acceptedCount, distanceM, fix.accuracyM)
                notifyProgress()
            }
        }
    }

    /**
     * Picks the recording up where it left off.
     *
     * The distance driven and the fix the filter compares against both come back from
     * the session's own fixes. Without this, collecting again on a session that is
     * already half recorded restarts the distance at zero while the fixes - and with
     * them the coverage, which is measured from the fixes - carry on, leaving an
     * operator two numbers about one pass that disagree, and no way to tell which is
     * the honest one. A session with nothing recorded yet is unaffected.
     */
    private suspend fun resumeFromWhatIsRecorded() {
        val recorded = runCatching { recordings.getPoints(sessionId) }.getOrDefault(emptyList())
        distanceM = polylineLengthMeters(recorded)
        filter = TrackPointFilter().apply { seed(recorded) }
    }

    /** The notification is refreshed periodically rather than on every fix. */
    private fun notifyProgress() {
        if (filter.acceptedCount % PROGRESS_EVERY != 0) return
        notificationManager().notify(NOTIFICATION_ID, buildNotification())
    }

    private fun buildNotification(): Notification {
        val openApp = PendingIntent.getActivity(
            this,
            0,
            Intent(this, MainActivity::class.java).addFlags(Intent.FLAG_ACTIVITY_SINGLE_TOP),
            PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_IMMUTABLE
        )

        val state = TrackingState.current
        val text = when (status) {
            RecordingStatus.RECORDING -> getString(
                R.string.recording_notification_text,
                formatDistance(state.distanceM),
                state.pointCount
            )

            else -> getString(R.string.recording_paused_text)
        }

        return NotificationCompat.Builder(this, CHANNEL_ID)
            .setContentTitle(getString(R.string.recording_notification_title))
            .setContentText(text)
            .setSmallIcon(R.drawable.ic_stat_tracking)
            .setOngoing(true)
            .setContentIntent(openApp)
            .setPriority(NotificationCompat.PRIORITY_LOW)
            .build()
    }

    private fun createChannel() {
        val channel = NotificationChannel(
            CHANNEL_ID,
            getString(R.string.recording_channel_name),
            NotificationManager.IMPORTANCE_LOW
        ).apply {
            description = getString(R.string.recording_channel_description)
        }
        notificationManager().createNotificationChannel(channel)
    }

    private fun notificationManager(): NotificationManager =
        getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager

    companion object {
        const val CHANNEL_ID = "sprayday-recording"
        const val NOTIFICATION_ID = 4201

        private const val ACTION_START = "nz.mckenzie.sprayday.action.START_RECORDING"
        private const val ACTION_PAUSE = "nz.mckenzie.sprayday.action.PAUSE_RECORDING"
        private const val ACTION_RESUME = "nz.mckenzie.sprayday.action.RESUME_RECORDING"
        private const val ACTION_STOP = "nz.mckenzie.sprayday.action.STOP_RECORDING"
        private const val EXTRA_SESSION_ID = "sessionId"
        private const val PROGRESS_EVERY = 5

        /** Starts recording. The caller must already hold the location permission. */
        fun start(context: Context, sessionId: Long) {
            val intent = Intent(context, TrackingService::class.java)
                .setAction(ACTION_START)
                .putExtra(EXTRA_SESSION_ID, sessionId)
            ContextCompat.startForegroundService(context, intent)
        }

        fun pause(context: Context) = send(context, ACTION_PAUSE)

        fun resume(context: Context) = send(context, ACTION_RESUME)

        fun stop(context: Context) = send(context, ACTION_STOP)

        private fun send(context: Context, action: String) {
            context.startService(Intent(context, TrackingService::class.java).setAction(action))
        }
    }
}

/**
 * Whether an ACTION_START is for the session this service is already collecting.
 *
 * Kept beside the service rather than inside it so the rule can be read and tested
 * without a running service: a re-created Record screen sends START again for the
 * session that is already recording, and that must not rewind a recording that is
 * going perfectly well - the operator would be told they had just started while the
 * coverage beside it counted the whole pass.
 */
internal fun stillRecording(
    requestedSessionId: Long,
    currentSessionId: Long,
    collecting: Boolean
): Boolean = collecting && requestedSessionId == currentSessionId

