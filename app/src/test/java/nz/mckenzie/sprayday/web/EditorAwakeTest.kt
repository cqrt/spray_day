package nz.mckenzie.sprayday.web

import android.net.wifi.WifiManager
import android.os.PowerManager
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The two decisions inside `EditorAwake`: which Wi-Fi lock a phone of this API level is given, and
 * which wake lock a run takes for the operator's answer about the screen.
 *
 * The locks themselves are the phone's business and are checked on a phone - `dumpsys power` shows
 * them by tag, and that they are given back when the run stops. What is decided here is the part a
 * reader would otherwise have to take on trust: the Wi-Fi mode that is active with the screen off and
 * the API level from which Android has no such mode any more, and the two wake-lock levels the switch
 * on the card chooses between.
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

    @Test
    fun aRunThatIsNotKeepingTheScreenOnTakesTheCpuAndNoMore() {
        // Everything the desk reads that the phone already holds is a disk read, and the CPU held up
        // is the whole of what that needs. The screen is left to its own timeout.
        assertEquals(PowerManager.PARTIAL_WAKE_LOCK, wakeLockLevelFor(keepTheScreenOn = false))
    }

    @Suppress("DEPRECATION")
    @Test
    fun aRunThatIsKeepingTheScreenOnTakesTheScreenWithIt() {
        // The operator's switch, for the phone whose radio sleeps when its screen does: a screen that
        // stays lit keeps the phone out of that state and out of Doze, and is the one thing they can
        // see is happening. Dimmed, because a lit screen all afternoon is a flat battery.
        assertEquals(PowerManager.SCREEN_DIM_WAKE_LOCK, wakeLockLevelFor(keepTheScreenOn = true))
    }
}
