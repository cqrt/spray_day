package nz.mckenzie.sprayday.data

import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.flow

/**
 * Clock sources for the due-status flow.
 *
 * Keeping the clock explicit (rather than calling System.currentTimeMillis
 * inside a query) makes the repository deterministic under test.
 */
object TrackTicker {

    /** Emits the current time immediately, then once a minute while subscribed. */
    fun minutes(periodMs: Long = 60_000L): Flow<Long> = flow {
        while (true) {
            emit(System.currentTimeMillis())
            delay(periodMs)
        }
    }

    /** Emits a single fixed instant - used by tests and previews. */
    fun fixed(epochMs: Long): Flow<Long> = flow { emit(epochMs) }
}
