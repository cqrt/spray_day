package nz.mckenzie.sprayday.doc

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.DocTrackQuery
import nz.mckenzie.sprayday.domain.doc.DocTracksJson
import nz.mckenzie.sprayday.domain.doc.DocTracksUrl
import java.net.HttpURLConnection
import java.net.URL

/** What asking the DOC Tracks service came back with. */
sealed interface DocTracksResult {

    /** The tracks the search matched. Empty is a real answer: nothing matched. */
    data class Found(val tracks: List<DocTrack>) : DocTracksResult

    /** The question could not be asked, and [message] says why in words the operator can read. */
    data class Failed(val message: String) : DocTracksResult
}

/**
 * Where DOC's tracks are searched from.
 *
 * An interface for the same reason [nz.mckenzie.sprayday.update.ReleaseFeed] is one: the screen's
 * decisions - what to ask, how to show the answer, what a tick imports - are worth testing without a
 * network, so they are tested against a fake.
 */
interface DocTracksSource {
    suspend fun search(query: DocTrackQuery): DocTracksResult
}

/**
 * The live DOC Tracks service, asked over HTTP.
 *
 * The URL it is asked at is [DocTracksUrl]'s business; this only carries the request, reads the
 * answer, and hands it to [DocTracksJson]. A refused or unreachable service is a sentence for the
 * operator rather than an exception - the search was a deliberate act and its failure should read
 * like one.
 */
class ArcGisDocTracks(
    /** Overridden in tests: the live service is not part of a unit test. */
    private val urlFor: (DocTrackQuery) -> String = DocTracksUrl::of,
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 30_000
) : DocTracksSource {

    override suspend fun search(query: DocTrackQuery): DocTracksResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(urlFor(query)).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                setRequestProperty("Accept", "application/json")
                setRequestProperty("User-Agent", USER_AGENT)
            }
            try {
                val status = connection.responseCode
                if (status != HttpURLConnection.HTTP_OK) {
                    return@runCatching DocTracksResult.Failed(
                        "DOC's track service answered HTTP $status. Try again later."
                    )
                }
                val body = connection.inputStream.use { it.readBytes().decodeToString() }
                DocTracksResult.Found(DocTracksJson.parse(body))
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            // No network, DNS, TLS, or an answer that would not read: say so rather than crashing.
            DocTracksResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    companion object {
        const val USER_AGENT = "SprayDay-Android"
    }
}
