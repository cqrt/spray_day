package nz.mckenzie.sprayday.tracking

import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The first frame a map opens on.
 *
 * Three screens ask the same question - where is the phone? - and the answer has to be
 * quick and safe: quick, because it is the first thing the operator sees, and safe,
 * because a map that hangs waiting for a fix is worse than one showing the country.
 *
 * This exists because the drawing and recording screens did not ask at all, and opened
 * on a neutral view centred on Nelson - which is exactly right for one person and
 * twenty minutes of confused panning for everybody else.
 */
class LocationFrameTest {

    private val invercargill = GeoPoint(lat = -46.4132, lng = 168.3538)

    @Test
    fun `a fix becomes a small frame around it`() = runBlocking {
        val frame = FixedLocation(invercargill).frameOnDevice()!!

        assertEquals(-46.4132, frame.minLat, 0.02)
        assertEquals(168.3538, frame.minLng, 0.02)
        assertTrue("the fix sits inside its own frame", frame.maxLat > invercargill.lat)
        assertTrue("and so does the fix's longitude", frame.maxLng > invercargill.lng)
    }

    @Test
    fun `a phone that cannot say where it is has no frame to offer`() = runBlocking {
        assertNull(FixedLocation(null).frameOnDevice())
    }

    @Test
    fun `a location source that falls over does not take the map with it`() = runBlocking {
        val broken = object : LocationSource {
            override fun updates(): Flow<GeoPoint> = emptyFlow()

            override suspend fun currentLocation(): GeoPoint = error("no permission")
        }

        assertNull(broken.frameOnDevice())
    }

    private class FixedLocation(private val fix: GeoPoint?) : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()

        override suspend fun currentLocation(): GeoPoint? = fix
    }
}
