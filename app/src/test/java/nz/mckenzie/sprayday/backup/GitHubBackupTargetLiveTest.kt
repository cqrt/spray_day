package nz.mckenzie.sprayday.backup

import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.domain.backup.AssetRecord
import nz.mckenzie.sprayday.domain.backup.BackupDocument
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.LinePointRecord
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Test
import java.net.HttpURLConnection
import java.net.URL

/**
 * The one test that talks to a real GitHub repository.
 *
 * It is skipped unless `SPRAY_DAY_BACKUP_TOKEN` is set, so the everyday suite and CI never
 * reach the network - the same rule the rest of these tests keep. Run it by hand after
 * changing a token, a repository, or the calls this app makes:
 *
 *     $env:SPRAY_DAY_BACKUP_TOKEN = (gh auth token)
 *     .\gradlew.bat :app:testDebugUnitTest --tests '*GitHubBackupTargetLiveTest*'
 *
 * It writes one small file under the name of a device that does not exist, checks that it can
 * be listed, read and overwritten, and deletes it again in a `finally`, so the repository is
 * left as it was found. A live service is the only way to find out whether the API is being
 * called the way it actually wants to be called; a mock would only confirm what was assumed.
 */
class GitHubBackupTargetLiveTest {

    private val token = System.getenv("SPRAY_DAY_BACKUP_TOKEN")
    private val repo = System.getenv("SPRAY_DAY_BACKUP_REPO") ?: "cqrt/spray-day-backups"

    private fun document(pointCount: Int) = BackupDocument(
        exportedAtEpochMs = 1_760_000_000_000L,
        appVersion = "target-check",
        assets = listOf(
            AssetRecord(
                id = 1,
                name = "Fenceline, upper",
                intervalDays = 21,
                createdAtEpochMs = 1_760_000_000_000L,
                lengthM = 120.0,
                points = (0 until pointCount).map { index -> LinePointRecord(-45.0 + index, 170.0) }
            )
        )
    )

    @Test
    fun `a backup goes up, is found, is read back and is updated in place`() = runBlocking {
        assumeTrue("set SPRAY_DAY_BACKUP_TOKEN to run this", !token.isNullOrBlank())
        val target = GitHubBackupTarget(repo = repo, token = token!!, fileName = FILE_NAME)
        delete(FILE_NAME)

        try {
            // The token, the repository, and whether a farm's records would be readable by
            // anyone who came across the repository.
            val check = target.check()
            assertEquals(repo, check.fullName)
            assertTrue("this check writes farm data, so it refuses a public repository", check.isPrivate)
            assertTrue("the token cannot write to $repo", check.canPush)

            // A repository with no backup in it yet is not an error.
            assertFalse(target.list().map { it.name }.toString(), target.list().any { it.name == FILE_NAME })

            // Creating the file: no hash exists yet.
            val first = BackupFormat.encode(document(pointCount = 3))
            target.save(first)
            assertTrue(target.list().any { it.name == FILE_NAME })
            assertEquals(first, target.read(FILE_NAME))

            // Updating it: the path with a hash in it, which has to be right or every
            // backup after the first silently fails.
            val second = BackupFormat.encode(document(pointCount = 5))
            target.save(second)
            assertEquals(second, target.read(FILE_NAME))
            assertEquals(5, BackupFormat.decode(target.read(FILE_NAME)).assets.first().points.size)

            // The copy's size is reported, so the screen has something to show before a
            // restore.
            val listed = target.list().first { it.name == FILE_NAME }
            assertEquals(second.toByteArray().size.toLong(), listed.sizeBytes)
            assertFalse(listed.sizeLabel.isBlank())
        } finally {
            delete(FILE_NAME)
        }
    }

    /**
     * Deletes the file with a direct call.
     *
     * The app itself has no reason to delete a copy - the operator's history is the point of
     * a repository - so this lives here rather than in [GitHubBackupTarget], and the file is
     * removed by the test that made it.
     */
    private fun delete(name: String) {
        val sha = shaOf(name) ?: return
        val connection = (URL("https://api.github.com/repos/$repo/contents/$name")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "DELETE"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        try {
            connection.doOutput = true
            connection.setRequestProperty("Content-Type", "application/json")
            connection.outputStream.use { stream ->
                stream.write("""{"message":"Remove the target check","sha":"$sha"}""".toByteArray())
            }
            connection.responseCode
        } finally {
            connection.disconnect()
        }
    }

    /** The file's hash, or null when there is no such file. */
    private fun shaOf(name: String): String? {
        val connection = (URL("https://api.github.com/repos/$repo/contents/$name")
            .openConnection() as HttpURLConnection).apply {
            requestMethod = "GET"
            connectTimeout = TIMEOUT_MS
            readTimeout = TIMEOUT_MS
            setRequestProperty("Authorization", "Bearer $token")
            setRequestProperty("Accept", "application/vnd.github+json")
            setRequestProperty("User-Agent", USER_AGENT)
        }
        return try {
            if (connection.responseCode != HttpURLConnection.HTTP_OK) {
                null
            } else {
                val body = connection.inputStream.use { it.readBytes().decodeToString() }
                Regex("\"sha\"\\s*:\\s*\"([0-9a-f]+)\"").find(body)?.groupValues?.get(1)
            }
        } catch (failure: java.io.IOException) {
            null
        } finally {
            connection.disconnect()
        }
    }

    private companion object {
        /** Deliberately not any real device's name, so it can never collide with a copy. */
        const val FILE_NAME = "spray-day-backup-target-check.json"
        const val USER_AGENT = "SprayDay-Android"
        const val TIMEOUT_MS = 60_000
    }
}
