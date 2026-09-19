package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.domain.tiles.Basemap
import java.net.HttpURLConnection
import java.net.URL

/**
 * Fetches aerial tiles from LINZ.
 *
 * Status codes are mapped deliberately rather than collapsed into "failed":
 *
 *  - `200` -> the tile
 *  - `404` -> [TileFetcher.Result.NotFound]; LINZ has no imagery for some tiles
 *    (the edge of coverage, offshore), and those are not errors to retry
 *  - `400` -> a bad or expired API key, surfaced as a failure with that wording
 *  - `429` -> rate limited; the downloader's backoff and pacing are the answer,
 *    so it is worth saying so rather than reporting a generic failure
 *
 * The app identifies itself in the usual way ([sprayDayUserAgent]). LINZ does not require it;
 * being a well-behaved client of someone else's service does.
 */
class LinzTileFetcher(
    private val apiKey: String,
    /**
     * Where to fetch from. Overridden in tests so the status-code mapping can be
     * asserted against the real local tile server rather than the live service.
     */
    private val urlFor: (zoom: Int, x: Int, y: Int) -> String =
        { zoom, x, y -> Basemap.LINZ_AERIAL.tileUrl(apiKey, zoom, x, y) },
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 20_000
) : TileFetcher {

    override suspend fun fetch(zoom: Int, x: Int, y: Int): TileFetcher.Result =
        withContext(Dispatchers.IO) {
            val url = URL(urlFor(zoom, x, y))

            runCatching {
                val connection = (url.openConnection() as HttpURLConnection).apply {
                    requestMethod = "GET"
                    connectTimeout = connectTimeoutMs
                    readTimeout = readTimeoutMs
                    setRequestProperty("User-Agent", sprayDayUserAgent())
                    setRequestProperty("Accept", "image/webp,image/*")
                }
                try {
                    when (val code = connection.responseCode) {
                        HttpURLConnection.HTTP_OK -> {
                            val bytes = connection.inputStream.use { it.readBytes() }
                            if (bytes.isEmpty()) {
                                TileFetcher.Result.Failed("empty response")
                            } else {
                                TileFetcher.Result.Tile(bytes)
                            }
                        }

                        HttpURLConnection.HTTP_NOT_FOUND -> TileFetcher.Result.NotFound

                        HttpURLConnection.HTTP_BAD_REQUEST ->
                            TileFetcher.Result.Failed("LINZ rejected the key (HTTP 400): it may have expired")

                        TOO_MANY_REQUESTS ->
                            TileFetcher.Result.Failed("rate limited by LINZ (HTTP 429)")

                        else -> TileFetcher.Result.Failed("HTTP $code")
                    }
                } finally {
                    connection.disconnect()
                }
            }.getOrElse { error ->
                TileFetcher.Result.Failed(error.message ?: error::class.java.simpleName)
            }
        }

    private companion object {
        const val TOO_MANY_REQUESTS = 429
    }
}
