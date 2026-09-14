package nz.mckenzie.sprayday.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * How LINZ's answers are read back to the operator, and how much of a key is safe to
 * show on screen.
 */
class KeyCheckTest {

    @Test
    fun `a 200 means the key works`() {
        assertEquals(KeyCheck.Works, KeyCheck.fromStatus(200))
    }

    @Test
    fun `a rejected key says rejected and mentions expiry`() {
        for (status in listOf(400, 401, 403)) {
            val check = KeyCheck.fromStatus(status)

            assertTrue("HTTP $status should be a failure", check is KeyCheck.Failed)
            val message = (check as KeyCheck.Failed).message
            assertTrue("should name the status: $message", message.contains("HTTP $status"))
            assertTrue("should point at expiry: $message", message.contains("expired"))
        }
    }

    @Test
    fun `being rate limited is reported as itself, not as a bad key`() {
        val check = KeyCheck.fromStatus(429) as KeyCheck.Failed

        // 429 is about volume, so telling the operator their key expired would send them
        // off to request a new one for nothing.
        assertTrue(check.message.contains("429"))
        assertTrue("should not blame the key: ${check.message}", !check.message.contains("expired"))
    }

    @Test
    fun `any other status is passed through unembellished`() {
        assertEquals(KeyCheck.Failed("LINZ answered HTTP 503"), KeyCheck.fromStatus(503))
    }

    @Test
    fun `only the tail of a key is ever shown`() {
        assertEquals("\u20261234", maskKey("abcdefghijklmnop1234"))
        assertEquals("", maskKey("   "))
        assertEquals("\u2026ab", maskKey("ab"))
        assertEquals("\u2026wxyz", maskKey("  ...wxyz  "))
    }
}
