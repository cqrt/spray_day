package nz.mckenzie.sprayday.update

import android.app.PendingIntent
import android.content.Context
import android.content.Intent
import android.content.pm.PackageInstaller
import android.os.Build
import kotlinx.coroutines.CompletableDeferred
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.domain.update.AvailableUpdate
import java.net.HttpURLConnection
import java.net.URL

/** How an in-app update got on. */
sealed interface InstallResult {

    /**
     * Android has the file: either it is asking the operator to confirm, or it has already
     * put the new version in place. That is the normal outcome rather than a failure - no
     * app can install anything on Android without the operator's tap.
     */
    data object HandedToInstaller : InstallResult

    /** It did not get that far, and [message] says why in words. */
    data class Failed(val message: String) : InstallResult
}

/**
 * Downloads a release and installs it over this copy of the app.
 *
 * A [PackageInstaller] session rather than a file plus an `ACTION_VIEW` intent: the bytes go
 * straight into the session the installer owns, so nothing is left on the phone afterwards,
 * there is no file provider to declare, and there is never a moment where an APK sits in a
 * directory something else could read.
 *
 * Downloading and installing are one operation for the same reason. On a farm connection a
 * dropped download abandons the session, rather than leaving half an APK behind for
 * something that cannot tell whether it is whole to install later.
 *
 * What protects the operator from a tampered download is Android itself: the installer
 * refuses an APK whose signing certificate is not the one already on the device, so a
 * substituted file fails at the last step rather than being trusted by this code.
 */
class ApkInstaller(private val context: Context) {

    suspend fun install(
        update: AvailableUpdate,
        onProgress: (Float) -> Unit = {}
    ): InstallResult = withContext(Dispatchers.IO) {
        val installer = context.packageManager.packageInstaller
        val params = PackageInstaller.SessionParams(PackageInstaller.SessionParams.MODE_FULL_INSTALL).apply {
            // An update of the app that is running, not a second copy of it.
            setAppPackageName(context.packageName)
            if (update.asset.size > 0L) setSize(update.asset.size)
        }

        val sessionId = runCatching { installer.createSession(params) }.getOrElse {
            return@withContext InstallResult.Failed(it.message ?: "could not start an install")
        }

        val answer = CompletableDeferred<Intent>()
        InstallStatusRelay.listen { answer.complete(it) }
        try {
            installer.openSession(sessionId).use { session ->
                val bytes = session.openWrite(SESSION_FILE_NAME, 0L, lengthFor(update)).use { out ->
                    val written = download(update.asset.downloadUrl, update.asset.size, onProgress) { buffer, read ->
                        out.write(buffer, 0, read)
                    }
                    session.fsync(out)
                    written
                }
                if (bytes <= 0L) {
                    installer.abandonSession(sessionId)
                    return@withContext InstallResult.Failed("the download was empty")
                }
                session.commit(pendingIntentFor(sessionId).intentSender)
            }

            // The installer answers within a second or two: either that it needs the
            // operator, or the reason it will not install this at all.
            when (val status = withTimeoutOrNull(ANSWER_TIMEOUT_MS) { answer.await() }) {
                null -> InstallResult.Failed("Android's installer did not answer")
                else -> interpret(status)
            }
        } catch (failure: Throwable) {
            runCatching { installer.abandonSession(sessionId) }
            InstallResult.Failed(failure.message ?: "the download did not finish")
        } finally {
            InstallStatusRelay.listen(null)
        }
    }

    /** The length the session is told to expect: the release's own size, or "unknown". */
    private fun lengthFor(update: AvailableUpdate): Long =
        if (update.asset.size > 0L) update.asset.size else UNKNOWN_SIZE

    private fun pendingIntentFor(sessionId: Int): PendingIntent = PendingIntent.getBroadcast(
        context,
        sessionId,
        Intent(context, UpdateInstallReceiver::class.java),
        // Mutable, because the installer fills in the intent that asks the operator to
        // confirm. That is also the reason the receiver is not exported.
        PendingIntent.FLAG_UPDATE_CURRENT or PendingIntent.FLAG_MUTABLE
    )

    /** Turns the installer's answer into something the operator can act on. */
    private fun interpret(answer: Intent): InstallResult {
        val status = answer.getIntExtra(PackageInstaller.EXTRA_STATUS, PackageInstaller.STATUS_FAILURE)
        val detail = answer.getStringExtra(PackageInstaller.EXTRA_STATUS_MESSAGE).orEmpty()

        return when (status) {
            PackageInstaller.STATUS_PENDING_USER_ACTION -> {
                val confirm = confirmationFrom(answer)
                if (confirm == null) {
                    InstallResult.Failed("Android asked for confirmation but did not say how")
                } else {
                    context.startActivity(confirm)
                    InstallResult.HandedToInstaller
                }
            }

            PackageInstaller.STATUS_SUCCESS -> InstallResult.HandedToInstaller

            PackageInstaller.STATUS_FAILURE_ABORTED -> InstallResult.Failed("The update was cancelled.")

            PackageInstaller.STATUS_FAILURE_BLOCKED -> InstallResult.Failed(
                "Android blocked the install. Allow Spray Day to install apps, then try again."
            )

            PackageInstaller.STATUS_FAILURE_CONFLICT -> InstallResult.Failed(
                "Its signature is not the one this app was installed with."
            )

            PackageInstaller.STATUS_FAILURE_INCOMPATIBLE ->
                InstallResult.Failed("The download is not built for this phone.")

            PackageInstaller.STATUS_FAILURE_INVALID ->
                InstallResult.Failed("The download is not a valid app.")

            PackageInstaller.STATUS_FAILURE_STORAGE ->
                InstallResult.Failed("There is not enough room to install it.")

            else -> InstallResult.Failed(
                if (detail.isBlank()) "Android refused the install." else "Android refused it: $detail"
            )
        }
    }

    /**
     * The intent that asks the operator to confirm, checked before it is launched.
     *
     * The status carries an intent for this app to start, and starting whatever an intent
     * happens to name is how an app can be made to talk someone into installing something
     * else. Only the platform's own installer, or an intent that is itself a package
     * archive, is followed.
     */
    @Suppress("DEPRECATION")
    private fun confirmationFrom(answer: Intent): Intent? {
        val confirm = if (Build.VERSION.SDK_INT >= Build.VERSION_CODES.TIRAMISU) {
            answer.getParcelableExtra(Intent.EXTRA_INTENT, Intent::class.java)
        } else {
            answer.getParcelableExtra(Intent.EXTRA_INTENT)
        } ?: return null

        val installer = context.packageManager.resolveActivity(confirm, 0)?.activityInfo?.packageName
        val isInstaller = installer != null && installer in INSTALLER_PACKAGES
        val isArchive = confirm.type == PACKAGE_ARCHIVE_MIME
        if (!isInstaller && !isArchive) return null

        return confirm.addFlags(Intent.FLAG_ACTIVITY_NEW_TASK)
    }

    /** Streams the release into the session, saying how far along it is. */
    private fun download(
        url: String,
        expected: Long,
        onProgress: (Float) -> Unit,
        sink: (ByteArray, Int) -> Unit
    ): Long {
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = CONNECT_TIMEOUT_MS
            readTimeout = READ_TIMEOUT_MS
            // GitHub hands release downloads to another host, so following it is the
            // difference between a download and a redirect body.
            instanceFollowRedirects = true
            setRequestProperty("User-Agent", GitHubReleaseFeed.USER_AGENT)
        }
        try {
            val code = connection.responseCode
            if (code != HttpURLConnection.HTTP_OK) error("the download answered HTTP $code")

            val total = if (expected > 0L) expected else connection.contentLengthLong
            val buffer = ByteArray(BUFFER_BYTES)
            var written = 0L
            connection.inputStream.use { input ->
                while (true) {
                    val read = input.read(buffer)
                    if (read <= 0) break
                    sink(buffer, read)
                    written += read
                    if (total > 0L) onProgress((written.toFloat() / total).coerceIn(0f, 1f))
                }
            }
            return written
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        const val SESSION_FILE_NAME = "spray-day.apk"

        /** -1 is what the platform takes for a session whose length is not known. */
        const val UNKNOWN_SIZE = -1L

        const val BUFFER_BYTES = 64 * 1024
        const val CONNECT_TIMEOUT_MS = 10_000
        const val READ_TIMEOUT_MS = 30_000

        /**
         * How long to wait for the installer's first answer. Generous, because it includes
         * the platform reading the session; short enough that a phone which will never
         * answer does not look like a hang.
         */
        const val ANSWER_TIMEOUT_MS = 30_000L

        const val PACKAGE_ARCHIVE_MIME = "application/vnd.android.package-archive"

        /**
         * The apps that may be handed the confirmation. Android 11 moved the installer's
         * UI into the permission controller, so both homes are listed.
         */
        val INSTALLER_PACKAGES = setOf(
            "com.android.packageinstaller",
            "com.google.android.packageinstaller",
            "com.android.permissioncontroller",
            "com.google.android.permissioncontroller"
        )
    }
}
