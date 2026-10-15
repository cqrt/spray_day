package nz.mckenzie.sprayday.web

import android.net.wifi.WifiManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The one decision inside `EditorAwake`: which Wi-Fi lock a phone of this API level is given.
 *
 * The locks themselves are the phone's business and are checked on a phone - `dumpsys power` shows
 * them by tag, and that they are given back when the run stops. What is decided here is the thing a
 * reader would otherwise have to take on trust, because Android changed its mind about it: the mode
 * that is active with the screen off, and the API level from which there is no such mode any more.
 */
class EditorAwakeTest {

    @Suppress("DEPRECATION")
    @Test
    fun aPhoneOldEnoughForTheModeThatWorksWithTheScreenOffIsGivenIt() {
        val expected = WifiManager.WIFI_MODE_FULL_HIGH_PERF

        assertEquals("the oldest phone this app runs on", expected, wifiLockModeFor(26))
        assertEquals("and the last one before Android replaced the mode", expected, wifiLockModeFor(33))
    }

    @Test
    fun aPhoneNewEnoughToHaveReplacedTheModeIsAskedForNothing() {
        // From API 34 the platform answers WIFI_MODE_FULL_HIGH_PERF with WIFI_MODE_FULL_LOW_LATENCY,
        // whose lock only works with the screen on - which is the one condition this is not about.
        // Asking for nothing is the honest answer; the wake lock is what does the work there.
        assertNull("API 34, where the replacement arrived", wifiLockModeFor(34))
        assertNull("and anything newer", wifiLockModeFor(36))
    }
}
