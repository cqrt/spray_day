package nz.mckenzie.sprayday.web

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which address the operator is told to open, and what a token is.
 *
 * This is the decision that decides whether the switch works at all: a phone on the farm's Wi-Fi
 * usually holds a mobile-data address too, and handing the operator that one produces a page that
 * never loads with nothing on screen explaining why. So the picking is a pure function of what the
 * interfaces say, and these are the cases that matter.
 */
class WebEditorLinkTest {

    private fun link(address: String, interfaceName: String = "wlan0") =
        LinkCandidate(address = address, interfaceName = interfaceName)

    private fun addresses(vararg candidates: LinkCandidate) =
        WebEditorLink.candidates(candidates.toList()).map { it.address }

    @Test
    fun `a wi-fi address is offered before a mobile-data one`() {
        assertEquals(
            listOf("192.168.1.23", "10.20.30.40"),
            addresses(link("10.20.30.40", "rmnet_data0"), link("192.168.1.23", "wlan0"))
        )
    }

    @Test
    fun `a hotspot counts as wireless`() {
        assertEquals(
            listOf("192.168.43.1", "192.168.1.23"),
            addresses(link("192.168.1.23", "wlan0"), link("192.168.43.1", "ap0"))
        )
    }

    @Test
    fun `loopback and link-local addresses are never offered`() {
        assertTrue(
            addresses(
                link("127.0.0.1", "lo"),
                link("::1", "lo"),
                link("169.254.10.2", "wlan0"),
                link("fe80::1%wlan0", "wlan0")
            ).isEmpty()
        )
    }

    @Test
    fun `IPv4 is offered before IPv6`() {
        assertEquals(
            listOf("192.168.1.23", "fd00::5"),
            addresses(link("fd00::5", "wlan0"), link("192.168.1.23", "wlan0"))
        )
    }

    @Test
    fun `a public address is still offered, but behind a private one`() {
        assertEquals(
            listOf("192.168.1.23", "203.0.113.9"),
            addresses(link("203.0.113.9", "eth0"), link("192.168.1.23", "wlan0"))
        )
    }

    @Test
    fun `the same address twice is offered once`() {
        assertEquals(
            listOf("192.168.1.23"),
            addresses(link("192.168.1.23", "wlan0"), link("192.168.1.23", "wlan0"))
        )
    }

    @Test
    fun `the address to open carries the port and the token`() {
        assertEquals(
            "http://192.168.1.23:8799/?k=7f3a9c",
            WebEditorLink.urlFor("192.168.1.23", WebEditorLink.DEFAULT_PORT, "7f3a9c")
        )
    }

    @Test
    fun `an IPv6 address is bracketed for the URL`() {
        assertEquals(
            "http://[fd00::5]:8799/?k=7f3a9c",
            WebEditorLink.urlFor("fd00::5", 8799, "7f3a9c")
        )
    }

    @Test
    fun `a token is 32 lowercase hex characters`() {
        val token = WebEditorLink.newToken()

        assertEquals(32, token.length)
        assertTrue("only hex, in lower case: $token", token.all { it in "0123456789abcdef" })
    }

    @Test
    fun `two tokens are not the same token`() {
        val tokens = List(20) { WebEditorLink.newToken() }

        assertEquals("a token that repeats is not a token", 20, tokens.toSet().size)
    }

    @Test
    fun `hex writes every byte as two characters, high ones included`() {
        assertEquals("0080ff0a", WebEditorLink.hex(byteArrayOf(0, -128, -1, 10)))
        assertEquals("", WebEditorLink.hex(ByteArray(0)))
    }

    @Test
    fun `this machine's own loopback is never among the addresses offered`() {
        // The real interfaces, whatever they are on the machine running the tests.
        val offered = WebEditorLink.gather().map { it.address }

        assertFalse(offered.any { it.startsWith("127.") })
        assertFalse(offered.any { it == "::1" })
        assertFalse(offered.any { it.startsWith("169.254.") })
        assertFalse(offered.any { it.lowercase().startsWith("fe80:") })
    }
}
