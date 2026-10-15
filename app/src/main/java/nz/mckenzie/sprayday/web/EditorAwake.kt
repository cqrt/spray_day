package nz.mckenzie.sprayday.web

import android.annotation.SuppressLint
import android.content.Context
import android.net.wifi.WifiManager
import android.os.Build
import android.os.PowerManager

/**
 * What keeps the phone awake while the editor is being served, and gives it back its sleep after.
 *
 * The editor's foreground service keeps the **process** alive, which is enough for everything the
 * phone already holds: an incoming request wakes the phone, and a read from disk or from the database
 * answers it. What it is not enough for is a tile the phone does **not** hold. That one is fetched
 * from LINZ while the request is being answered, and a phone whose CPU has suspended cannot finish it:
 * the fetch overruns its timeout, the tile route answers 502, and the desk draws nothing over ground
 * nobody has looked at yet - while the ground it has already been over still draws, because that is
 * on disk. That is the fault an operator describes as *"the imagery stops working when the phone goes
 * to sleep"*.
 *
 * Two locks, and neither is enough on its own:
 *
 *  - a **partial wake lock**, so the CPU is not suspended in the middle of the fetch;
 *  - a **Wi-Fi lock**, so the radio is not in power save. [WifiManager.WIFI_MODE_FULL_HIGH_PERF] is
 *    the mode that says what this wants, in its own words - *"Wi-Fi will not go to power save ... the
 *    lock is active even when the device screen is off"* - and it is what a phone on API 26 to 33
 *    gets. From API 34 that constant is deprecated and is silently replaced by
 *    [WifiManager.WIFI_MODE_FULL_LOW_LATENCY], whose own lock *"is only active when the screen is
 *    on"*: on a phone that new there is nothing worth asking for - see [wifiLockModeFor] - and the
 *    wake lock is what does the work. A Wi-Fi lock needs the app to hold `WAKE_LOCK`, which it does
 *    for the wake lock anyway.
 *
 * Neither lock, and nothing else an app may hold, survives **Doze**: a phone left still and unplugged
 * for half an hour has its app network suspended whatever it holds. That half of the fault is
 * answered by downloading the ground first, on the Offline areas screen - which is what makes the
 * desk draw an area with no network at all. It is also why the locks are held only for the life of
 * the run: the editor being on is a switch the operator threw to work from a computer, and turning it
 * off gives the phone back its sleep.
 *
 * **And on some phones neither lock is enough anyway.** An operator reported exactly that: the imagery
 * went on failing a few minutes after the screen went off with both locks held, which is a phone whose
 * radio sleeps when its screen does - a few minutes is far too soon for Doze, so it is the phone's own
 * idea of sleep rather than Android's. What answers that is [hold]'s `keepTheScreenOn`, the operator's
 * own switch: a phone whose screen is on never enters either state, and the cost is said on the card.
 */
internal class EditorAwake(private val context: Context) {

    private var wakeLock: PowerManager.WakeLock? = null
    private var wifiLock: WifiManager.WifiLock? = null

    /**
     * Holds the phone awake. Safe to ask for twice - a run being built again asks again - and the
     * second ask finds the locks already in hand rather than taking a second pair.
     *
     * [keepTheScreenOn] is the operator's switch: the screen stays on, dimmed, so the phone is awake in
     * the way that no phone disagrees about. It costs battery and is off unless they ask for it.
     *
     * No timeout on the wake lock, which is what lint would rather see: the run's own stop is the
     * release, and a run is served for as long as the operator is at the computer - which is hours,
     * not minutes. A timed lock would simply expire and put the fault back. Nothing outlives the
     * process in any case: the kernel drops a wake lock with the process that took it, so the worst a
     * kill can leave behind is a run that is no longer serving. The tag is what makes one visible in
     * `dumpsys power` if it ever is.
     */
    @SuppressLint("WakelockTimeout")
    fun hold(keepTheScreenOn: Boolean) {
        if (wakeLock == null) {
            val power = context.getSystemService(Context.POWER_SERVICE) as PowerManager
            wakeLock = power
                .newWakeLock(wakeLockLevelFor(keepTheScreenOn), WAKE_LOCK_TAG)
                .apply { acquire() }
        }
        // A phone whose Android has replaced the Wi-Fi mode is asked for no Wi-Fi lock at all, which
        // is why this can be nothing - see [wifiLockModeFor]. The wake lock above is taken either way.
        val mode = wifiLockModeFor(Build.VERSION.SDK_INT)
        if (mode != null && wifiLock == null) {
            val wifi = context.applicationContext.getSystemService(Context.WIFI_SERVICE) as WifiManager
            wifiLock = wifi.createWifiLock(mode, WIFI_LOCK_TAG).apply { acquire() }
        }
    }

    /** Gives the phone back its sleep. Safe to ask for when nothing is held, which every stop is. */
    fun letSleep() {
        wakeLock?.let { held -> if (held.isHeld) held.release() }
        wakeLock = null
        wifiLock?.let { held -> if (held.isHeld) held.release() }
        wifiLock = null
    }

    companion object {
        /**
         * Named so that a lock left behind is identifiable rather than anonymous: `dumpsys power`
         * lists wake locks by tag, which is the only way to see this from outside the app.
         */
        const val WAKE_LOCK_TAG = "SprayDay:webEditor"
        const val WIFI_LOCK_TAG = "SprayDay:webEditor:wifi"
    }
}

/**
 * The Wi-Fi lock to ask for on a phone of API [sdk], or null when there is nothing worth asking for.
 *
 * Pure, so the one decision in [EditorAwake] is held by a test rather than by a comment:
 * `app/src/test/java/nz/mckenzie/sprayday/web/EditorAwakeTest.kt`.
 *
 * [WifiManager.WIFI_MODE_FULL_HIGH_PERF] is the mode whose own words are what this wants - *"Wi-Fi
 * will not go to power save ... the lock is active even when the device screen is off"* - and it is
 * what a phone from API 26 to 33 is given. From API 34 that constant is deprecated, and the platform
 * silently hands back [WifiManager.WIFI_MODE_FULL_LOW_LATENCY] instead, whose own lock *"is only
 * active when the screen is on"*: a lock that cannot do this job is worse than no lock, because it
 * reads like an answer. So a phone that new is asked for nothing, and the wake lock is what is left.
 */
@Suppress("DEPRECATION")
internal fun wifiLockModeFor(sdk: Int): Int? =
    if (sdk < Build.VERSION_CODES.UPSIDE_DOWN_CAKE) WifiManager.WIFI_MODE_FULL_HIGH_PERF else null

/**
 * The wake lock's own level for a run that does or does not keep the phone's screen on.
 *
 * Pure, so the other decision in [EditorAwake] is held by a test as well:
 * `app/src/test/java/nz/mckenzie/sprayday/web/EditorAwakeTest.kt`.
 *
 * [PowerManager.PARTIAL_WAKE_LOCK] is the CPU without the screen, which is the whole of what the app
 * needs when everything being asked of the phone is already on it. Keeping the screen on needs
 * [PowerManager.SCREEN_DIM_WAKE_LOCK]: the screen stays lit at its dimmest, which is enough to keep
 * the phone out of both the radio-sleeping state and Doze, and is the one thing an operator can *see*
 * is happening. Both of the screen levels are deprecated in Android's own SDK - the advice is a window
 * flag on an activity - but the phone is in a pocket with no activity on show, so a wake lock is the
 * only way to say it from a service.
 */
@Suppress("DEPRECATION")
internal fun wakeLockLevelFor(keepTheScreenOn: Boolean): Int =
    if (keepTheScreenOn) PowerManager.SCREEN_DIM_WAKE_LOCK else PowerManager.PARTIAL_WAKE_LOCK
