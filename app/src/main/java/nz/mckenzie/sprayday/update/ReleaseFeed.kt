package nz.mckenzie.sprayday.update

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import nz.mckenzie.sprayday.domain.update.GitHubRelease
import nz.mckenzie.sprayday.domain.update.ReleaseCatalog
import java.net.HttpURLConnection
import java.net.URL

/**
 * What asking GitHub about the newest release came back with.
 *
 * [Answer] carries null when there is no release to read at all - a repository whose
 * releases have been deleted, or one that has never been tagged. That is a different thing
 * from [Failed], and the screen says so: "no releases yet" is not a fault to retry.
 */
sealed interface FeedResult {

    data class Answer(val release: GitHubRelease?) : FeedResult

    /** The question could not be asked, and [message] says why in words. */
    data class Failed(val message: String) : FeedResult

    companion object {
        /** Turns GitHub's answer into an answer about the release. */
        fun fromStatus(status: Int, body: String?): FeedResult = when (status) {
            HttpURLConnection.HTTP_OK -> Answer(body?.let(ReleaseCatalog::parseRelease))

            HttpURLConnection.HTTP_NOT_FOUND -> Answer(null)

            // GitHub says 403 when the hourly limit for this address is used up, and 429
            // when it has seen too many requests, which is the same story to the operator.
            HttpURLConnection.HTTP_FORBIDDEN, TOO_MANY_REQUESTS ->
                Failed("GitHub is not answering this phone at the moment (HTTP $status): try again later")

            else -> Failed("GitHub answered HTTP $status")
        }

        private const val TOO_MANY_REQUESTS = 429
    }
}

/**
 * Where the newest release is read from.
 *
 * An interface for the same reason [nz.mckenzie.sprayday.offline.TileFetcher] is one: the
 * decisions built on top of it are worth testing without a network, so they are tested
 * against a fake rather than against GitHub.
 */
interface ReleaseFeed {
    suspend fun latest(): FeedResult
}

/**
 * Asks GitHub for the newest release of this app.
 *
 * `releases/latest` excludes drafts and pre-releases itself, so what comes back is what
 * would be published to anybody. Unauthenticated calls are limited per address per hour,
 * which is why the background check is daily rather than hourly - one request a day for a
 * limit of sixty.
 */
class GitHubReleaseFeed(
    /** Overridden in tests: the live service is not part of a unit test. */
    private val urlFor: () -> String = { RELEASES_URL },
    private val connectTimeoutMs: Int = 10_000,
    private val readTimeoutMs: Int = 15_000
) : ReleaseFeed {

    override suspend fun latest(): FeedResult = withContext(Dispatchers.IO) {
        runCatching {
            val connection = (URL(urlFor()).openConnection() as HttpURLConnection).apply {
                requestMethod = "GET"
                connectTimeout = connectTimeoutMs
                readTimeout = readTimeoutMs
                // GitHub answers 403 to a call with no User-Agent, which would look like
                // a rate limit rather than what it is.
                setRequestProperty("User-Agent", USER_AGENT)
                setRequestProperty("Accept", "application/vnd.github+json")
            }
            try {
                val status = connection.responseCode
                val stream = if (status == HttpURLConnection.HTTP_OK) {
                    connection.inputStream
                } else {
                    connection.errorStream
                }
                val body = stream?.use { it.readBytes().decodeToString() }
                FeedResult.fromStatus(status, body)
            } finally {
                connection.disconnect()
            }
        }.getOrElse { error ->
            // No network, DNS, TLS: say so rather than blaming the release.
            FeedResult.Failed(error.message ?: error::class.java.simpleName)
        }
    }

    companion object {
        /** The newest published release of this app. */
        const val RELEASES_URL = "https://api.github.com/repos/cqrt/spray_day/releases/latest"

        const val USER_AGENT = "SprayDay-Android"
    }
}
