package nz.mckenzie.sprayday.tracking

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flow
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.map.PositionGeoJson
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The phone's position, as every map in the app reads it.
 *
 * This used to be the map tab's own business: it collected the stream in its view model and
 * handed the marker to its map, so the map was the one place in the app that showed where you
 * were. The fix belongs to the map instead, and the half of that worth testing - the half that
 * used to be a view-model test - is that the stream carries the newest fix, that nothing is
 * asked of the phone while no map is open, and that a phone which cannot say where it is
 * produces a marker with nothing on it rather than a failure.
 */
class DevicePositionTest {

    @After
    fun tearDown() {
        DevicePosition.useForTest(null)
    }

    /** Fixes, and a count of how many times the phone is actually being listened to. */
    private class CountingFixes(private val fixes: List<GeoPoint>) : LocationSource {
        var listening = 0
            private set

        /** The most listeners there have ever been at once - what the assertions can read
         * without racing the unwinding of a stream that has just been cancelled. */
        var mostAtOnce = 0
            private set

        override fun updates(): Flow<GeoPoint> = flow {
            listening++
            mostAtOnce = maxOf(mostAtOnce, listening)
            try {
                fixes.forEach { emit(it) }
            } finally {
                listening--
            }
        }

        override suspend fun currentLocation(): GeoPoint? = fixes.lastOrNull()
    }

    private fun fix(lng: Double) = GeoPoint(lat = -41.5, lng = lng, accuracyM = 5f)

    private fun scope() = CoroutineScope(SupervisorJob() + Dispatchers.Default)

    @Test
    fun `the marker is built from the newest fix`() = runBlocking {
        DevicePosition.useForTest(CountingFixes(listOf(fix(173.95), fix(173.96))))
        val scope = scope()
        try {
            val newest = withTimeout(5_000) { DevicePosition.updates(scope).first { it != null } }!!

            assertEquals("the latest fix is the one the map draws", 173.96, newest.lng, 1e-9)
            assertTrue(
                "and it is the dot, ringed by the accuracy the fix was good to",
                PositionGeoJson.build(newest).contains("\"part\":\"${PositionGeoJson.PART_DOT}\"")
            )
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `nothing is asked of the phone while no map is open`() = runBlocking {
        val phone = CountingFixes(listOf(fix(173.95)))
        DevicePosition.useForTest(phone)
        val scope = scope()
        try {
            val fixes = DevicePosition.updates(scope, keepAliveMs = 0)

            delay(300)
            assertEquals("a stream nobody is looking at is not a GPS request", 0, phone.mostAtOnce)
            assertNull("and it holds no fix", fixes.value)

            withTimeout(5_000) { fixes.first { it != null } }
            assertEquals("a map looking at it is one request", 1, phone.mostAtOnce)

            delay(400)
            assertEquals("and closing the map closes the request", 0, phone.listening)
        } finally {
            scope.cancel()
        }
    }

    @Test
    fun `a phone that cannot say where it is draws no marker`() = runBlocking {
        DevicePosition.useForTest(null)
        val scope = scope()
        try {
            assertNull(DevicePosition.updates(scope).value)
            assertEquals(PositionGeoJson.EMPTY, PositionGeoJson.build(null))
        } finally {
            scope.cancel()
        }
    }
}
