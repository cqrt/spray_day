package nz.mckenzie.sprayday.backup

import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.withContext
import kotlinx.serialization.SerialName
import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.domain.backup.BackupTarget
import nz.mckenzie.sprayday.domain.backup.StoredBackup
import java.net.HttpURLConnection
import java.net.URL
import java.util.Base64

/** A destination that cannot be used, said in words worth putting on the screen. */
class BackupTargetException(message: String) : Exception(message)

/** What GitHub says about the repository this app has been pointed at. */
data class RepoCheck(val fullName: String, val isPrivate: Boolean, val canPush: Boolean)

@Serializable
internal data class ContentsEntry(
    val name: String,
    val path: String,
    val sha: String? = null,
    val size: Long = 0,
    val type: String = "file"
)

@Serializable
internal data class ContentsFile(
    val name: String = "",
    val sha: String = "",
    val size: Long = 0,
    val content: String? = null,
    val encoding: String? = null
)

@Serializable
internal data class RepoBody(
    @SerialName("full_name") val fullName: String = "",
    @SerialName("private") val isPrivate: Boolean = false,
    val permissions: Permissions? = null
) {
    @Serializable
    data class Permissions(val push: Boolean = false, val admin: Boolean = false)
}

@Serializable
internal data class ErrorBody(val message: String = "")

/**
 * Keeps this device's backup in a GitHub repository, through the contents API.
 *
 * A repository rather than a synced folder because what an off-site backup mostly needs is
 * to be somewhere else, on a service that is not going to be unplugged, and to keep its
 * history: every backup is a commit, so yesterday's copy is still fetchable even though the
 * app only ever writes one file per device. A private repository is the point - see
 * [RepoCheck.isPrivate], which the screen reports on, because farm records in a public
 * repository are farm records in public.
 *
 * Why this API and not a Git client: it is a handful of calls, it needs no working copy, and
 * the token can be scoped to this one repository's contents. The file goes up as base64 in a
 * request body, which is the API's own arrangement rather than a choice - a backup bigger
 * than [MAX_TEXT_BYTES] is refused with a message that says to keep that one on a file.
 */
class GitHubBackupTarget(
    /** "owner/name", as shown in the repository's URL. */
    private val repo: String,
    private val token: String,
    /** This device's file, so two phones cannot overwrite each other's copy. */
    private val fileName: String,
    /** Null means whatever the repository calls its default branch. */
    private val branch: String? = null,
    private val baseUrl: String = "https://api.github.com",
    private val userAgent: String = "SprayDay-Android"
) : BackupTarget {

    override val label: String get() = "GitHub, $repo"

    /** The repository, the token and what the token may do - what the Test button asks. */
    suspend fun check(): RepoCheck {
        val response = send("GET", "/repos/$repo", ACCEPT_JSON)
        return GitHubResponses.repoCheck(response.status, response.body)
    }

    override suspend fun list(): List<StoredBackup> {
        val response = send("GET", "/repos/$repo/contents/" + refQuery(), ACCEPT_JSON)
        // An empty repository has nothing to list, which is not a failure: it is a
        // repository made a minute ago, waiting for its first backup.
        if (response.status == HttpURLConnection.HTTP_NOT_FOUND) return emptyList()
        return GitHubResponses.files(response.status, response.body, response.message())
            // By name, because the API reports no write time. The date that matters is
            // inside the file, and the screen shows it as soon as one is chosen.
            .sortedBy { it.name }
    }

    override suspend fun read(reference: String): String {
        val response = send("GET", contentsPath(reference) + refQuery(), ACCEPT_RAW)
        if (response.status != HttpURLConnection.HTTP_OK) {
            throw BackupTargetException(describeFailure(response))
        }
        return response.body
    }

    override suspend fun save(text: String): StoredBackup {
        val bytes = text.toByteArray()
        if (bytes.size > MAX_TEXT_BYTES) {
            throw BackupTargetException(
                "This backup is ${bytes.size / 1_000_000} MB, and GitHub will not take more " +
                    "than ${MAX_TEXT_BYTES / 1_000_000} MB in one write. Back up to a file as " +
                    "well, and keep the long recordings there."
            )
        }

        val content = Base64.getEncoder().encodeToString(bytes)
        // The hash of what is there now, if anything: the API refuses to overwrite a file
        // without it, which is what stops one phone from quietly trampling another's copy.
        var sha = existingSha()
        repeat(2) { attempt ->
            val response = send("PUT", contentsPath(fileName), ACCEPT_JSON, writeBody(content, sha))
            when (response.status) {
                HttpURLConnection.HTTP_OK, HttpURLConnection.HTTP_CREATED ->
                    return StoredBackup(
                        reference = fileName,
                        name = fileName,
                        sizeBytes = bytes.size.toLong()
                    )
                // The file changed under us, or appeared since we looked. Read the hash
                // again and write once more, then give up rather than loop.
                HttpURLConnection.HTTP_CONFLICT, UNPROCESSABLE_ENTITY -> {
                    if (attempt == 1) throw BackupTargetException(describeFailure(response))
                    sha = existingSha()
                }
                else -> throw BackupTargetException(describeFailure(response))
            }
        }
        throw BackupTargetException("GitHub would not take the backup.")
    }

    /** The hash of this device's file as it stands, or null when there is none yet. */
    private suspend fun existingSha(): String? {
        val response = send("GET", contentsPath(fileName) + refQuery(), ACCEPT_JSON)
        return when (response.status) {
            HttpURLConnection.HTTP_OK ->
                runCatching { json.decodeFromString<ContentsFile>(response.body).sha }.getOrNull()
            HttpURLConnection.HTTP_NOT_FOUND -> null
            else -> throw BackupTargetException(describeFailure(response))
        }
    }

    private fun contentsPath(name: String) = "/repos/$repo/contents/$name"

    /** Empty when no branch was named, so the repository's own default is used. */
    private fun refQuery(): String =
        branch?.takeIf { it.isNotBlank() }?.let { "?ref=$it" } ?: ""

    private fun writeBody(content: String, sha: String?): String = buildString {
        append("{\"message\":\"Backup from ").append(fileName).append('"')
        if (sha != null) append(",\"sha\":\"").append(sha).append('"')
        branch?.takeIf { it.isNotBlank() }?.let { append(",\"branch\":\"").append(it).append('"') }
        append(",\"content\":\"").append(content).append("\"}")
    }

    private fun describeFailure(response: Response): String {
        val detail = response.message()
        return GitHubResponses.explain(response.status, repo) + if (detail.isBlank()) "" else " ($detail)"
    }

    private suspend fun send(
        method: String,
        path: String,
        accept: String,
        body: String? = null
    ): Response = withContext(Dispatchers.IO) {
        val connection = (URL(baseUrl + path).openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = CONNECT_TIMEOUT_MS
            // Uploading a season over a farm connection is allowed to be slow; the point of
            // a read timeout here is to notice a dead connection, not to hurry a big file.
            readTimeout = READ_TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", accept)
            setRequestProperty("User-Agent", userAgent)
            setRequestProperty("X-GitHub-Api-Version", "2022-11-28")
        }
        try {
            if (body != null) {
                connection.doOutput = true
                connection.setRequestProperty("Content-Type", "application/json")
                connection.outputStream.use { it.write(body.toByteArray()) }
            }
            val status = connection.responseCode
            val stream = if (status >= 400) connection.errorStream else connection.inputStream
            Response(status, stream?.use { it.readBytes().decodeToString() }.orEmpty())
        } catch (failure: java.io.IOException) {
            throw BackupTargetException(
                "GitHub could not be reached: ${failure.message ?: "the connection failed"}"
            )
        } finally {
            connection.disconnect()
        }
    }

    /** What came back, before any of it is believed. */
    internal data class Response(val status: Int, val body: String) {
        fun message(): String =
            runCatching { json.decodeFromString<ErrorBody>(body).message }.getOrNull().orEmpty()
    }

    companion object {
        private const val ACCEPT_JSON = "application/vnd.github+json"
        private const val ACCEPT_RAW = "application/vnd.github.raw"
        private const val CONNECT_TIMEOUT_MS = 15_000
        private const val READ_TIMEOUT_MS = 120_000
        private const val UNPROCESSABLE_ENTITY = 422

        /** The API's file limit is 100 MB; base64 and JSON make this the honest ceiling. */
        private const val MAX_TEXT_BYTES = 32_000_000

        private val json = Json { ignoreUnknownKeys = true; encodeDefaults = true }
    }
}
