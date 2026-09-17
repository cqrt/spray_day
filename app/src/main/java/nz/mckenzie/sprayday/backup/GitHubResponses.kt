package nz.mckenzie.sprayday.backup

import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.domain.backup.OffsiteBackupRules
import nz.mckenzie.sprayday.domain.backup.StoredBackup

/**
 * What GitHub answered, read as this app's business rather than as HTTP.
 *
 * Separate from the target itself, and free of any connection, for the usual reason: a
 * refusal is the case that has to be right - an expired token and a misnamed repository look
 * identical from the operator's side unless something turns the status into a sentence - and
 * sentences are worth testing without a network.
 */
internal object GitHubResponses {

    private val json = Json { ignoreUnknownKeys = true }

    /** The repository, or a refusal with the reason in it. */
    fun repoCheck(status: Int, body: String): RepoCheck {
        if (status != 200) throw BackupTargetException(explain(status, repo = "that repository"))
        val parsed = runCatching { json.decodeFromString<RepoBody>(body) }.getOrNull()
            ?: throw BackupTargetException("GitHub answered, but not about a repository.")
        return RepoCheck(
            fullName = parsed.fullName,
            isPrivate = parsed.isPrivate,
            canPush = parsed.permissions?.let { it.push || it.admin } ?: false
        )
    }

    /** The backup files in a directory listing, ignoring anything else the repository holds. */
    fun files(status: Int, body: String, detail: String): List<StoredBackup> {
        if (status != 200) throw BackupTargetException(explain(status, repo = "that repository") + suffix(detail))
        val entries = runCatching { json.decodeFromString<List<ContentsEntry>>(body) }.getOrNull()
            ?: throw BackupTargetException("GitHub answered, but not with a list of files.")
        return entries
            .filter { it.type == "file" && OffsiteBackupRules.isBackupFileName(it.name) }
            .map { StoredBackup(reference = it.path, name = it.name, sizeBytes = it.size) }
    }

    /**
     * A refusal in the operator's terms.
     *
     * GitHub's own words are appended by the caller when there are any, but they are never
     * the whole message: "Bad credentials" does not tell anybody which of the three things
     * on the screen to go and look at.
     */
    fun explain(status: Int, repo: String): String = when (status) {
        401 -> "GitHub did not accept that token. Check it has not expired, and that the whole " +
            "token was pasted in."
        403 -> "GitHub allowed the token to be used, but not for this. A fine-grained token " +
            "needs Contents: read and write on $repo."
        404 -> "GitHub cannot see $repo with that token. Either the name is not quite right, " +
            "or the token was never given access to it."
        409 -> "The copy on GitHub changed while this phone was writing it. Nothing was " +
            "overwritten; try again."
        413 -> "GitHub would not take a file that size. Back up to a file as well."
        422 -> "GitHub would not write that file, usually because the copy on GitHub has moved " +
            "on since this phone last looked."
        429 -> "GitHub asked this phone to slow down. Try again shortly."
        else -> "GitHub answered $status."
    }

    private fun suffix(detail: String) = if (detail.isBlank()) "" else " ($detail)"
}
