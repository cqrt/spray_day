package nz.mckenzie.sprayday.web

import java.net.NetworkInterface
import java.security.SecureRandom

/**
 * Where a computer on the same Wi-Fi finds the editor, and the token it has to bring.
 *
 * The editor is served by the phone, so the address is whatever address the phone holds *on the
 * Wi-Fi* - and a phone usually holds more than one. Mobile data is the case that matters: it is a
 * real address, it looks just as good to a naive picker, and no laptop on the farm's Wi-Fi can
 * reach it. Picking between them is the only interesting thing in this file, and it is kept away
 * from the socket on purpose: it is a decision about the operator's own network, so it is a pure
 * function of what the interfaces say, and a test can hold it.
 *
 * None of this needs a permission. `NetworkInterface` lists the device's own addresses, so the app
 * is asking about itself rather than reading the Wi-Fi's name or the scan results.
 */
object WebEditorLink {

    /** The port the editor asks for. If it is taken, the next free one is used instead. */
    const val DEFAULT_PORT = 8799

    /**
     * 128 bits, written as 32 hex characters: long enough that a guess is not a strategy on a
     * LAN, short enough to be read off a screen and typed if it ever has to be.
     */
    private const val TOKEN_BYTES = 16

    /**
     * Interface names Android gives to the interfaces a laptop can plausibly reach. `wlan` is
     * Wi-Fi; `ap`/`softap` are a hotspot, which is the same thing from the sofa. A table rather
     * than a comparison buried in the ranking, so the test can hold the whole list.
     */
    private val WIRELESS_PREFIXES = listOf("wlan", "wifi", "ap")

    private val random = SecureRandom()

    /** Every address this phone holds, best first, with the ones nothing can reach left out. */
    fun gather(): List<LinkCandidate> = candidates(interfaces())

    /**
     * The addresses worth offering, ordered so the one to read out is first.
     *
     * Dropped: loopback, which is only the phone itself (and `LocalTileServer`'s own promise - the
     * editor is deliberately not that), and link-local, which needs a scope name the laptop does
     * not have. Ordered: a private IPv4 address (a farm's Wi-Fi, a hotspot, most ethernets) over a
     * public one, an address on a wireless interface over any other, and IPv4 before IPv6 because
     * a ULA is the least likely to be what the laptop can use and the least pleasant to type.
     */
    fun candidates(all: List<LinkCandidate>): List<LinkCandidate> = all
        .filterNot { isLoopback(it.address) || isLinkLocal(it.address) }
        .distinct()
        .sortedWith(compareBy({ rank(it) }, { it.interfaceName }, { it.address }))

    /**
     * The address to open on the computer - token and all, or bare when this run asks for none.
     *
     * A null [token] is a run with no secret, which is a thing the operator can choose: see
     * [WebEditorServer]. What comes back then is the plain address, so the card shows a door that
     * anybody on the Wi-Fi can walk through as exactly that - and nothing is left on the end that
     * looks like a key and is not being checked.
     */
    fun urlFor(address: String, port: Int = DEFAULT_PORT, token: String?): String {
        // A URL needs brackets around an IPv6 literal; nothing else about it changes.
        val host = if (address.contains(':')) "[$address]" else address
        return "http://$host:$port/" + if (token == null) "" else "?k=$token"
    }

    /**
     * A fresh token for one run of the switch.
     *
     * Per session rather than per install, and never stored: the URL is the password, and a token
     * that outlived the switch would be a password left lying in a notification shade. Restarting
     * the editor therefore gives a new address to open, which is the honest behaviour for something
     * served to a LAN.
     */
    fun newToken(): String = hex(ByteArray(TOKEN_BYTES).also { random.nextBytes(it) })

    /** Bytes as lowercase hex, two characters each, so a byte's shape is not worth remembering. */
    fun hex(bytes: ByteArray): String =
        bytes.joinToString("") { (it.toInt() and 0xFF).toString(16).padStart(2, '0') }

    private fun interfaces(): List<LinkCandidate> = runCatching {
        NetworkInterface.getNetworkInterfaces().toList()
            .filter { nic -> runCatching { nic.isUp }.getOrDefault(false) }
            .flatMap { nic ->
                nic.inetAddresses.toList().map { address ->
                    LinkCandidate(address = address.hostAddress.orEmpty(), interfaceName = nic.name.orEmpty())
                }
            }
    }.getOrDefault(emptyList())

    /** Lower is better; see [candidates] for what the order is for. */
    private fun rank(candidate: LinkCandidate): Int {
        val v4 = !candidate.address.contains(':')
        val privateV4 = v4 && isPrivateV4(candidate.address)
        val wireless = WIRELESS_PREFIXES.any { candidate.interfaceName.startsWith(it, ignoreCase = true) }
        return when {
            privateV4 && wireless -> 0
            privateV4 -> 1
            v4 && wireless -> 2
            v4 -> 3
            else -> 4
        }
    }

    /** The three RFC 1918 ranges: what a farm's router hands out, and what a hotspot hands out. */
    private fun isPrivateV4(address: String): Boolean {
        val parts = address.split('.').mapNotNull { it.toIntOrNull() }
        if (parts.size != 4 || parts.any { it !in 0..255 }) return false
        return when {
            parts[0] == 10 -> true
            parts[0] == 172 && parts[1] in 16..31 -> true
            parts[0] == 192 && parts[1] == 168 -> true
            else -> false
        }
    }

    private fun isLoopback(address: String): Boolean =
        address.startsWith("127.") || address.equals("::1", ignoreCase = true)

    private fun isLinkLocal(address: String): Boolean {
        val lower = address.lowercase()
        return lower.startsWith("169.254.") || lower.startsWith("fe80:")
    }
}

/** One address the phone holds, and the interface it is on. */
data class LinkCandidate(val address: String, val interfaceName: String)
