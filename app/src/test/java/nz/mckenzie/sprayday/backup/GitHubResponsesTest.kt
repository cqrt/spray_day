package nz.mckenzie.sprayday.backup

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How GitHub's answers are read.
 *
 * The refusals are the cases that have to be right: from the operator's side an expired
 * token, a repository that does not exist and a token that was never given access to it all
 * look the same - a button that does nothing - unless something turns the status code into a
 * sentence that says which of the three to go and fix.
 */
class GitHubResponsesTest {

    private val repo = "cqrt/spray-day-backups"

    @Test
    fun `a rejected token is named as the thing to look at`() {
        val message = GitHubResponses.explain(401, repo)

        assertTrue(message, message.contains("token"))
        assertTrue(message, message.contains("expired"))
    }

    @Test
    fun `a token without the right scope is told what it needs`() {
        val message = GitHubResponses.explain(403, repo)

        assertTrue(message, message.contains("Contents: read and write"))
        assertTrue(message, message.contains(repo))
    }

    @Test
    fun `a repository that cannot be seen names both possible causes`() {
        val message = GitHubResponses.explain(404, repo)

        assertTrue(message, message.contains(repo))
        assertTrue(message, message.contains("name is not quite right"))
        assertTrue(message, message.contains("access"))
    }

    @Test
    fun `a conflict says nothing was overwritten`() {
        assertTrue(GitHubResponses.explain(409, repo).contains("Nothing was overwritten"))
    }

    @Test
    fun `a private repository that can be written to is reported as both`() {
        val check = GitHubResponses.repoCheck(
            200,
            """{"full_name":"cqrt/spray-day-backups","private":true,"permissions":{"push":true}}"""
        )

        assertEquals(repo, check.fullName)
        assertTrue(check.isPrivate)
        assertTrue(check.canPush)
    }

    @Test
    fun `a public repository is reported as public`() {
        val check = GitHubResponses.repoCheck(
            200,
            """{"full_name":"cqrt/spray-day-backups","private":false,"permissions":{"admin":true}}"""
        )

        assertFalse(check.isPrivate)
        // Admin includes push, and a token that can administer a repository can write to it.
        assertTrue(check.canPush)
    }

    @Test
    fun `a token that can only read is reported as unable to push`() {
        val check = GitHubResponses.repoCheck(
            200,
            """{"full_name":"cqrt/spray-day-backups","private":true,"permissions":{"pull":true,"push":false}}"""
        )

        assertTrue(check.isPrivate)
        assertFalse(check.canPush)
    }

    @Test
    fun `a refusal is thrown with the sentence, not with the status code`() {
        val failure = runCatching { GitHubResponses.repoCheck(401, """{"message":"Bad credentials"}""") }

        assertEquals("GitHub did not accept that token. Check it has not expired, and that the whole token was pasted in.",
            failure.exceptionOrNull()?.message)
    }

    @Test
    fun `a listing keeps this app's backups and ignores everything else`() {
        val files = GitHubResponses.files(
            200,
            """
            [
              {"name":"README.md","path":"README.md","sha":"1","size":20,"type":"file"},
              {"name":"spray-day-pixel-8.json","path":"spray-day-pixel-8.json","sha":"2","size":1234,"type":"file"},
              {"name":"spray-day-sdk-gphone64-x86-64.json","path":"spray-day-sdk-gphone64-x86-64.json","sha":"3","size":99,"type":"file"},
              {"name":"notes","path":"notes","sha":"4","size":0,"type":"dir"}
            ]
            """.trimIndent(),
            detail = ""
        )

        assertEquals(
            listOf("spray-day-pixel-8.json", "spray-day-sdk-gphone64-x86-64.json"),
            files.map { it.name }
        )
        assertEquals(1234L, files.first().sizeBytes)
        assertEquals("spray-day-pixel-8.json", files.first().reference)
    }

    @Test
    fun `a listing that is not a listing is a refusal, not an empty repository`() {
        // The third argument is GitHub's own words, which the caller has already taken out
        // of the body; they are appended so a rate limit or a proxy reads as itself.
        val failure = runCatching { GitHubResponses.files(500, """{"message":"Server Error"}""", "Server Error") }

        assertTrue(failure.exceptionOrNull() is BackupTargetException)
        val message = failure.exceptionOrNull()?.message.orEmpty()
        assertTrue(message, message.contains("answered 500"))
        assertTrue(message, message.contains("Server Error"))
    }
}
