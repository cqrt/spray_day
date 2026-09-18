package nz.mckenzie.sprayday.tracking

import android.content.Context
import android.util.Log
import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.flow.SharingStarted
import kotlinx.coroutines.flow.StateFlow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.stateIn
import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * Where the phone is, for every map in the app.
 *
 * The marker used to be something each screen handed to its map: the map tab collected the
 * stream, built the GeoJSON and passed it in, and every other map - the recorder, the drawing
 * screen, the offline picker, the thumbnails - drew the work with no phone on it at all. The
 * position of the phone is not a fact about a screen; it is a fact about the phone, and the
 * fix belongs with the map rather than with whoever remembered to ask.
 *
 * So the stream lives here and the map reads it. A process-wide holder is a deliberate
 * simplification, in the same spirit as [TileServerHolder] and [TrackingState]: there is one
 * phone, one fix at a time, and one place for a map to get it.
 *
 * Nothing is collected until a map is on screen, and collection stops when the last one goes
 * away: this is a GPS stream, and a tab nobody is looking at has no business keeping the
 * receiver awake. That is also why [updates] hands back a sleep after the last subscriber
 * rather than ending at once - a tab switch should not cost a fresh fix.
 */
object DevicePosition {

    /** How long the stream stays open after the last map leaves the screen. */
    private const val KEEP_ALIVE_MS = 5_000L

    @Volatile
    private var source: LocationSource? = null

    /**
     * The device's own fixes. Called once, from [nz.mckenzie.sprayday.SprayDayApplication].
     *
     * A phone with no fused provider - no Play services - has no marker to offer, and a map
     * that draws the work and nothing else is the right answer on one: the app is not stopped
     * from opening over a missing optional provider. It is said out loud in the log, though,
     * because "no marker on any map" is otherwise a silent mystery.
     */
    fun start(context: Context) {
        source = runCatching { FusedLocationSource(context.applicationContext) }
            .onFailure { failure ->
                Log.w(TAG, "No fused location provider, so no map will show the phone", failure)
            }
            .getOrNull()
    }

    /**
     * The newest fix, for as long as somebody is looking at a map.
     *
     * Null until the first fix arrives, and for ever when the app may not know where it is -
     * [FusedLocationSource.updates] ends without emitting rather than throwing, so a refused
     * permission is a map with no marker rather than a crash.
     */
    fun updates(scope: CoroutineScope, keepAliveMs: Long = KEEP_ALIVE_MS): StateFlow<GeoPoint?> =
        (source?.updates() ?: emptyFlow())
            .stateIn(scope, SharingStarted.WhileSubscribed(keepAliveMs), null)

    /** A test's own fixes, in place of the device's. */
    internal fun useForTest(testSource: LocationSource?) {
        source = testSource
    }

    private const val TAG = "SprayDayPosition"
}
