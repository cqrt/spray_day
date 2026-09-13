package nz.mckenzie.sprayday.map

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import java.net.HttpURLConnection
import java.net.URL

/** Outcome of probing the LINZ tile service with the configured key. */
sealed interface LinzKeyStatus {
    /** The key was accepted (HTTP 200). */
    data object Valid : LinzKeyStatus

    /** HTTP 400 - LINZ reports this for an expired standard-access key. */
    data object Expired : LinzKeyStatus

    /** HTTP 429 - rate limited; back off and retry rather than blaming the key. */
    data object RateLimited : LinzKeyStatus

    data class Failed(val message: String) : LinzKeyStatus
}

/**
 * Fetches a single known tile to find out whether the configured key still
 * works, so the app can tell the operator "your key expired" instead of just
 * showing a blank map.
 */
class LinzTileProbe(
    private val apiKey: String,
    private val connectTimeoutMs: Int = 15_000,
    private val readTimeoutMs: Int = 15_000
) {

    suspend fun check(): LinzKeyStatus = withContext(Dispatchers.IO) {
        val url = URL(
            LinzBasemap.aerialTileTemplate(apiKey)
                .replace("{z}", "6")
                .replace("{x}", "1")
                .replace("{y}", "40")
        )

        runCatching {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
            }
            try {
                when (connection.responseCode) {
                    HttpURLConnection.HTTP_OK -> LinzKeyStatus.Valid
                    HttpURLConnection.HTTP_BAD_REQUEST -> LinzKeyStatus.Expired
                    TOO_MANY_REQUESTS -> LinzKeyStatus.RateLimited
                    else -> LinzKeyStatus.Failed("HTTP ${connection.responseCode}")
                }
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            LinzKeyStatus.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
