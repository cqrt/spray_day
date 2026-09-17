package nz.mckenzie.sprayday.update

import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.domain.update.AppVersion
import nz.mckenzie.sprayday.domain.update.ReleaseCatalog
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The decision inside one update check: is there something to install, and is it worth
 * saying so?
 *
 * Telling an operator about the same release every day would get the notification turned
 * off, which costs them every later one - so "said once, and only when it was really said"
 * is the behaviour most worth pinning down here.
 */
class UpdateCheckTest {

    private val running = AppVersion(0, 6, 2)

    private val releaseBody = """
        {
          "tag_name": "v0.6.3",
          "name": "Spray Day 0.6.3",
          "assets": [
            {
              "name": "spray-day-0.6.3-arm64-v8a.apk",
              "size": 16400000,
              "browser_download_url": "https://example.invalid/arm64.apk"
            }
          ]
        }
    """.trimIndent()

    private val newer = FeedResult.Answer(ReleaseCatalog.parseRelease(releaseBody))

    private class FakeFeed(private val result: FeedResult) : ReleaseFeed {
        override suspend fun latest(): FeedResult = result
    }

    private class FakeStore(private var told: String = "") {
        var saved: String? = null

        suspend fun read(): String = told

        suspend fun write(version: String) {
            told = version
            saved = version
        }
    }

    /** A check wired to a fake store and a fake notification, and what it announced. */
    private class Harness(
        feed: FeedResult,
        val store: FakeStore = FakeStore(),
        current: AppVersion? = null,
        private val posts: Boolean = true
    ) {
        val announced = mutableListOf<Pair<String, String>>()

        val check = UpdateCheck(
            current = current,
            supportedAbis = listOf("arm64-v8a"),
            feed = FakeFeed(feed),
            lastNotified = { store.read() },
            rememberNotified = { store.write(it) },
            post = { title, body ->
                announced += title to body
                posts
            }
        )
    }

    private fun harness(
        feed: FeedResult,
        told: String = "",
        posts: Boolean = true
    ) = Harness(feed, FakeStore(told), running, posts)

    @Test
    fun `a newer release is offered, announced, and remembered as announced`() = runBlocking {
        val h = harness(newer)

        val outcome = h.check.run()

        assertNotNull("there is something to install", outcome.available)
        assertEquals(AppVersion(0, 6, 3), outcome.available?.version)
        assertTrue("and the operator was told", outcome.notified)
        assertEquals("once", 1, h.announced.size)
        assertEquals("0.6.3", h.store.saved)
        assertTrue(
            "the notification names the version: ${h.announced.first()}",
            h.announced.first().first.contains("0.6.3")
        )
    }

    @Test
    fun `the same release is not announced twice`() = runBlocking {
        val h = harness(newer, told = "0.6.3")

        val outcome = h.check.run()

        assertNotNull("it is still offered", outcome.available)
        assertFalse("but not said again", outcome.notified)
        assertTrue(h.announced.isEmpty())
        assertNull("and nothing was re-saved", h.store.saved)
    }

    @Test
    fun `nothing is remembered when the notification did not appear`() = runBlocking {
        val h = harness(newer, posts = false)

        val outcome = h.check.run()

        assertFalse(outcome.notified)
        assertTrue("it still tried", h.announced.isNotEmpty())
        assertNull("so turning notifications on later will still tell them", h.store.saved)
        assertTrue(
            "and the screen says why: ${outcome.message}",
            outcome.message.contains("notifications")
        )
    }

    @Test
    fun `asking from the settings screen does not also post a notification`() = runBlocking {
        val h = harness(newer)

        val outcome = h.check.run(announce = false)

        assertNotNull(outcome.available)
        assertFalse(outcome.notified)
        assertTrue("nothing was posted about what is already on screen", h.announced.isEmpty())
    }

    @Test
    fun `the newest release is the one already installed`() = runBlocking {
        val same = releaseBody.replace("v0.6.3", "v0.6.2")
        val h = harness(FeedResult.Answer(ReleaseCatalog.parseRelease(same)))

        val outcome = h.check.run()

        assertNull(outcome.available)
        assertTrue(h.announced.isEmpty())
        assertTrue("it says so plainly: ${outcome.message}", outcome.message.contains("newest version"))
    }

    @Test
    fun `a repository with no releases yet is not a fault to retry`() = runBlocking {
        val h = harness(FeedResult.Answer(null))

        val outcome = h.check.run()

        assertNull(outcome.available)
        assertTrue(outcome.message.contains("No releases"))
    }

    @Test
    fun `a GitHub that cannot be reached gives its own reason, in words`() = runBlocking {
        val h = harness(FeedResult.Failed("no network"))

        val outcome = h.check.run()

        assertNull("nothing is offered", outcome.available)
        assertFalse("and nothing is said", outcome.notified)
        assertTrue(h.announced.isEmpty())
        assertEquals("the reason is passed on as it was given", "no network", outcome.message)
    }

    @Test
    fun `a build that does not know its own version says that rather than guessing`() = runBlocking {
        val h = Harness(newer, current = null)

        val outcome = h.check.run()

        assertNull(outcome.available)
        assertTrue(h.announced.isEmpty())
        assertTrue(
            "it explains: ${outcome.message}",
            outcome.message.contains("does not say what version")
        )
    }

    @Test
    fun `a release with no APK for this phone is nothing to install`() = runBlocking {
        val noApks = """{"tag_name":"v0.7.0","name":"Spray Day 0.7.0","assets":[]}"""
        val h = harness(FeedResult.Answer(ReleaseCatalog.parseRelease(noApks)))

        val outcome = h.check.run()

        assertNull(outcome.available)
        assertTrue(h.announced.isEmpty())
        assertTrue(outcome.message.contains("newest version"))
    }
}
