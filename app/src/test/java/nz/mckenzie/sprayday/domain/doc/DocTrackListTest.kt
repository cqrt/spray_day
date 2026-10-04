package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.track.TrackInterchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Narrowing and ordering what a DOC search found.
 *
 * The two rules worth holding still are that "nearest" means nearest *vertex* - a long line passing
 * beside the phone is near the phone, where its middle would not be - and that a nearest sort with
 * nothing to be near to is no order at all, so it falls back to by name rather than inventing one.
 */
class DocTrackListTest {

    private fun track(id: Long, name: String, kind: String?, at: GeoPoint) = DocTrack(
        objectId = id,
        name = name,
        kind = kind,
        reading = TrackInterchange.Reading(
            paths = listOf(
                listOf(at, GeoPoint(at.lat + 0.01, at.lng + 0.01)),
                listOf(at, GeoPoint(at.lat - 0.01, at.lng))
            ),
            sideTracks = 1,
            segmentsDidNotMeet = false
        )
    )

    private val invercargill = GeoPoint(-46.4132, 168.3538)

    @Test
    fun `the kinds present are each once, sorted, and blanks are left out`() {
        val tracks = listOf(
            track(1, "A", "Tramping Track", invercargill),
            track(2, "B", "Short Walk", invercargill),
            track(3, "C", "Tramping Track", invercargill),
            track(4, "D", null, invercargill),
            track(5, "E", "  ", invercargill)
        )

        assertEquals(listOf("Short Walk", "Tramping Track"), tracks.kindsPresent())
    }

    @Test
    fun `nearest is measured to the closest vertex, not the middle of the line`() {
        // The second path starts exactly at the fix, so the track is nought metres away even though
        // its other end is a kilometre off.
        val track = track(1, "A", "Walking Track", invercargill)

        assertEquals(0.0, track.distanceM(invercargill)!!, 0.5)
    }

    @Test
    fun `nearest orders by the distance to the phone`() {
        val near = track(1, "Near", "Walking Track", GeoPoint(-46.4130, 168.3538))
        val far = track(2, "Far", "Walking Track", GeoPoint(-46.30, 168.20))

        val shown = listOf(far, near).showing(kinds = emptySet(), sort = DocTrackSort.NEAREST, from = invercargill)

        assertEquals(listOf(near, far), shown)
    }

    @Test
    fun `a nearest sort with no fix falls back to by name`() {
        val b = track(1, "B", "Walking Track", invercargill)
        val a = track(2, "A", "Walking Track", invercargill)

        val shown = listOf(b, a).showing(kinds = emptySet(), sort = DocTrackSort.NEAREST, from = null)

        assertEquals(listOf(a, b), shown)
    }

    @Test
    fun `picking kinds keeps only those`() {
        val walk = track(1, "Walk", "Walking Track", invercargill)
        val tramp = track(2, "Tramp", "Tramping Track", invercargill)

        val shown = listOf(walk, tramp).showing(setOf("Tramping Track"), DocTrackSort.NAME, invercargill)

        assertEquals(listOf(tramp), shown)
    }

    @Test
    fun `no kinds picked leaves an empty filter, not an empty list`() {
        val walk = track(1, "Walk", "Walking Track", invercargill)
        val tramp = track(2, "Tramp", "Tramping Track", invercargill)

        val shown = listOf(walk, tramp).showing(emptySet(), DocTrackSort.NAME, invercargill)

        assertTrue(shown.containsAll(listOf(walk, tramp)))
    }

    @Test
    fun `within keeps only what is truly in the radius, unlike the service's box`() {
        // The service's box would have handed both back; the circle keeps only the one inside it.
        val near = track(1, "Near", "Walking Track", GeoPoint(-46.4130, 168.3538))
        val far = track(2, "Far", "Walking Track", GeoPoint(-46.30, 168.20))

        val shown = listOf(near, far).within(radiusM = 5_000.0, from = invercargill)

        assertEquals(listOf(near), shown)
    }

    @Test
    fun `within with no fix keeps everything, because there is no centre to measure from`() {
        val tracks = listOf(track(1, "A", "Walking Track", GeoPoint(-46.30, 168.20)))

        assertEquals(tracks, tracks.within(5_000.0, from = null))
    }

    @Test
    fun `bounds is the box every vertex fits in, and nothing when there are no tracks`() {
        val a = track(1, "A", "Walking Track", GeoPoint(-46.50, 168.10))
        val b = track(2, "B", "Walking Track", GeoPoint(-46.40, 168.20))

        val bounds = listOf(a, b).bounds()!!
        // The track helper adds a diagonal 0.01 either way, so the extent is the corners of both.
        assertEquals(-46.51, bounds.minLat, 1e-9)
        assertEquals(168.10, bounds.minLng, 1e-9)
        assertEquals(-46.39, bounds.maxLat, 1e-9)
        assertEquals(168.21, bounds.maxLng, 1e-9)

        assertEquals(null, emptyList<DocTrack>().bounds())
    }
}
