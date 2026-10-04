package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.track.TrackInterchange
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Searching the tracks downloaded for offline use.
 *
 * No place filter is applied here: a cache only holds the areas the operator downloaded, so the
 * download *is* the place filter - which is what lets an offline search be a name and nothing more.
 */
class DocTrackCacheTest {

    private fun track(id: Long, name: String) = DocTrack(
        objectId = id,
        name = name,
        kind = null,
        reading = TrackInterchange.Reading(
            paths = listOf(listOf(GeoPoint(-46.6, 168.3), GeoPoint(-46.61, 168.31))),
            sideTracks = 0,
            segmentsDidNotMeet = false
        )
    )

    @Test
    fun `a name filters the downloaded tracks, without case`() {
        val tracks = listOf(track(1, "Ocean Beach Tk"), track(2, "Omaui Track"))

        assertEquals(listOf(track(2, "Omaui Track")), tracks.matching("omaui"))
        assertTrue("nothing matching is nothing", tracks.matching("Kaituna").isEmpty())
    }

    @Test
    fun `no name is everything downloaded`() {
        val tracks = listOf(track(1, "A"), track(2, "B"))

        assertEquals(tracks, tracks.matching(""))
        assertEquals(tracks, tracks.matching("   "))
    }
}
