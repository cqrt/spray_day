package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.map.LinzBasemap
import java.net.HttpURLConnection
import java.net.URL

/**
 * The answer to "does this LINZ key work?".
 *
 * LINZ standard-access keys expire every 90 days and the failure is confusing rather
 * than obvious, so a button that reports the truth is worth more than an explanation.
 */
sealed interface KeyCheck {

    /** LINZ accepted the key. */
    data object Works : KeyCheck

    /**
     * It did not work, and [message] says so in words that name the cause.
     */
    data class Failed(val message: String) : KeyCheck

    companion object {
        /** Turns LINZ's answer into an answer about the key. */
        fun fromStatus(status: Int): KeyCheck = when (status) {
            HttpURLConnection.HTTP_OK -> Works

            HttpURLConnection.HTTP_BAD_REQUEST,
            HttpURLConnection.HTTP_UNAUTHORIZED,
            HttpURLConnection.HTTP_FORBIDDEN ->
                Failed("LINZ rejected the key (HTTP $status): it may have expired")

            TOO_MANY_REQUESTS -> Failed("rate limited by LINZ (HTTP 429): try again in a minute")

            else -> Failed("LINZ answered HTTP $status")
        }

        private const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * Asks LINZ whether it accepts a key.
 *
 * The probe is the **hosted style document**, not a tile, and that distinction was
 * learned by testing against the live service rather than assumed:
 *
 *  - **A tile probe can pass with a dead key.** LINZ serves tiles through a CDN that
 *    keys its cache on the path alone, so a popular tile comes back `HTTP 200` for
 *    *any* key once anyone has fetched it. A probe on central Christchurch at zoom 12
 *    returned byte-identical imagery for a bogus key, for the real key, and for no key
 *    at all - and so did a zoom 16 tile over the same city.
 *  - **The style document is enforced per key.** On that same popular path, a bogus key
 *    gets `400` and the real key gets `200`. A cold zoom-19 tile over rural Southland
 *    also gets `400` for a bogus key and `200` for the real one, which confirms it.
 *
 * The consequence in the field is worth knowing: a dead key looks like imagery that
 * still works in places you have already been and is blank somewhere new, because the
 * cached parts carry on being served. This probe sees through that; a tile probe would
 * not.
 */
class LinzKeyProbe(
    /** Overridden in tests: the live service is not part of a unit test. */
    private val urlFor: (String) -> String = LinzBasemap::hostedAerialStyleUrl,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) {

    suspend fun check(apiKey: String): KeyCheck = withContext(Dispatchers.IO) {
        val url = URL(urlFor(apiKey))

        runCatching {
            val connection = (url.openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
            }
            try {
                KeyCheck.fromStatus(connection.responseCode)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            // No network, DNS, TLS: say so rather than blaming the key.
            KeyCheck.Failed(error.message ?: error::class.java.simpleName)
        }
    }
}

/**
 * A key as it is safe to show: enough to tell two keys apart, not enough to be a copy
 * of the secret on screen.
 */
fun maskKey(key: String): String {
    val trimmed = key.trim()
    return when {
        trimmed.isEmpty() -> ""
        trimmed.length <= 4 -> "\u2026" + trimmed
        else -> "\u2026" + trimmed.takeLast(4)
    }
}
