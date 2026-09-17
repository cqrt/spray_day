package nz.mckenzie.sprayday.update

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What GitHub's answer means, without a network.
 *
 * The interesting one is `404`: a repository whose releases have all been deleted answers
 * exactly like one that has never been tagged, and neither is a fault - there is simply
 * nothing to offer, which is a different sentence from "I could not ask".
 */
class FeedResultTest {

    @Test
    fun `a release body comes back as a release`() {
        val result = FeedResult.fromStatus(200, """{"tag_name":"v0.6.3"}""")

        assertTrue("it is an answer, not a failure", result is FeedResult.Answer)
        assertEquals("v0.6.3", (result as FeedResult.Answer).release?.tagName)
    }

    @Test
    fun `a body that is not a release is an answer with nothing in it`() {
        val result = FeedResult.fromStatus(200, "<html></html>")

        assertTrue(result is FeedResult.Answer)
        assertNull((result as FeedResult.Answer).release)
    }

    @Test
    fun `no releases is not a failure to retry`() {
        val result = FeedResult.fromStatus(404, null)

        assertTrue(result is FeedResult.Answer)
        assertNull((result as FeedResult.Answer).release)
    }

    @Test
    fun `being turned away names the cause rather than saying failed`() {
        val forbidden = FeedResult.fromStatus(403, null) as FeedResult.Failed
        val throttled = FeedResult.fromStatus(429, null) as FeedResult.Failed

        assertTrue("the address limit: ${forbidden.message}", forbidden.message.contains("not answering this phone"))
        assertTrue("and the same story for 429: ${throttled.message}", throttled.message.contains("429"))
    }

    @Test
    fun `any other status is reported with its number`() {
        val result = FeedResult.fromStatus(500, null) as FeedResult.Failed

        assertEquals("GitHub answered HTTP 500", result.message)
    }
}
