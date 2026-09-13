package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackPointFilterTest {

    private fun point(
        lat: Double = 0.0,
        lng: Double = 0.0,
        accuracy: Float? = 5f,
        timeMs: Long = 0L
    ) = GeoPoint(lat = lat, lng = lng, accuracyM = accuracy, timeMs = timeMs)

    @Test
    fun `first accurate fix is accepted`() {
        val filter = TrackPointFilter()
        assertTrue(filter.accept(point()))
        assertEquals(1, filter.acceptedCount)
    }

    @Test
    fun `fixes worse than the accuracy limit are rejected`() {
        val filter = TrackPointFilter()
        assertFalse(filter.accept(point(accuracy = 50f)))
        assertEquals(0, filter.acceptedCount)
        assertEquals(1, filter.rejectedAccuracyCount)
    }

    @Test
    fun `missing accuracy is treated as acceptable`() {
        val filter = TrackPointFilter()
        assertTrue(filter.accept(point(accuracy = null)))
    }

    @Test
    fun `fixes closer than the minimum distance are treated as jitter`() {
        val filter = TrackPointFilter()
        assertTrue(filter.accept(point(lat = 0.0)))
        // ~1.1 m north - inside the 3 m threshold.
        assertFalse(filter.accept(point(lat = 0.00001)))
        assertEquals(1, filter.rejectedDistanceCount)
        assertEquals(1, filter.acceptedCount)
    }

    @Test
    fun `fixes that have genuinely moved are accepted`() {
        val filter = TrackPointFilter()
        assertTrue(filter.accept(point(timeMs = 0L)))
        // ~11.1 m north after 2 seconds (~5.6 m/s) - plausible for a vehicle.
        assertTrue(filter.accept(point(lat = 0.0001, timeMs = 2_000L)))
        assertEquals(2, filter.acceptedCount)
    }

    @Test
    fun `implausible jumps are rejected as speed outliers`() {
        val filter = TrackPointFilter()
        assertTrue(filter.accept(point(timeMs = 0L)))
        // ~111 m north in 1 second - not a spray vehicle.
        assertFalse(filter.accept(point(lat = 0.001, timeMs = 1_000L)))
        assertEquals(1, filter.rejectedSpeedCount)
        assertEquals(1, filter.acceptedCount)
    }

    @Test
    fun `speed is not evaluated when timestamps are absent`() {
        val filter = TrackPointFilter()
        assertTrue(filter.accept(point(timeMs = 0L)))
        assertTrue(filter.accept(point(lat = 0.001, timeMs = 0L)))
        assertEquals(0, filter.rejectedSpeedCount)
    }

    @Test
    fun `reset clears state and counters`() {
        val filter = TrackPointFilter()
        filter.accept(point())
        filter.accept(point(lat = 0.00001))
        filter.reset()

        assertEquals(0, filter.acceptedCount)
        assertEquals(0, filter.rejectedDistanceCount)
        assertEquals(null, filter.lastAcceptedPoint)
        assertTrue(filter.accept(point()))
    }

    @Test
    fun `thresholds are configurable`() {
        val strict = TrackPointFilter(PointFilterConfig(minDistanceM = 20.0, maxAccuracyM = 3f))
        assertFalse(strict.accept(point(accuracy = 10f)))
        assertTrue(strict.accept(point(accuracy = 2f)))
        assertFalse(strict.accept(point(lat = 0.0001, accuracy = 2f)))
    }

    @Test
    fun `last accepted point tracks the most recent fix`() {
        val filter = TrackPointFilter()
        filter.accept(point(lat = 0.0))
        val moved = point(lat = 0.0001)
        filter.accept(moved)
        assertEquals(moved.lat, filter.lastAcceptedPoint?.lat ?: Double.NaN, 0.0)
    }
}
