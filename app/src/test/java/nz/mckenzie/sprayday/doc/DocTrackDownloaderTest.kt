package nz.mckenzie.sprayday.doc

import nz.mckenzie.sprayday.domain.doc.DocBounds
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.DocTrackQuery
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.track.TrackInterchange
import kotlinx.coroutines.runBlocking
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Reading a whole area's tracks a page at a time.
 *
 * The service answers at most 2,000 a request, so an area with more is a loop - and the loop's two
 * worth-holding rules are that it keeps asking until a page comes back short, and that a failure
 * half way through keeps the pages that did arrive rather than throwing them away.
 */
class DocTrackDownloaderTest {

    private fun track(id: Long): DocTrack = DocTrack(
        objectId = id,
        name = "Track $id",
        kind = "Walking Track",
        reading = TrackInterchange.Reading(
            paths = listOf(listOf(GeoPoint(-46.6, 168.3), GeoPoint(-46.61, 168.31))),
            sideTracks = 0,
            segmentsDidNotMeet = false
        )
    )

    private val box = DocBounds(minLat = -46.7, minLng = 168.1, maxLat = -46.5, maxLng = 168.6)

    /** A source that hands back prepared pages, recording the offset each was asked at. */
    private class PagingSource(private val pages: List<List<DocTrack>>) : DocTracksSource {
        val offsets = mutableListOf<Int>()
        private var call = 0

        override suspend fun search(query: DocTrackQuery): DocTracksResult {
            offsets += query.offset
            val page = pages.getOrElse(call) { emptyList() }
            call++
            return DocTracksResult.Found(page)
        }
    }

    @Test
    fun `a full page is followed by the next, until a short one ends it`() {
        val source = PagingSource(
            listOf(
                List(DocTrackDownloader.PAGE) { track(it.toLong()) },
                listOf(track(9999))
            )
        )

        val result = runBlocking { DocTrackDownloader(source).download(box) }

        assertEquals(DocTrackDownloader.PAGE + 1, (result as DocDownloadResult.Done).tracks.size)
        assertEquals("the second page was asked for where the first ended", listOf(0, 2000), source.offsets)
    }

    @Test
    fun `one short page is the whole download`() {
        val source = PagingSource(listOf(listOf(track(1), track(2))))

        val done = runBlocking { DocTrackDownloader(source).download(box) } as DocDownloadResult.Done

        assertEquals(2, done.tracks.size)
        assertEquals(listOf(0), source.offsets)
    }

    @Test
    fun `a failure half way keeps the pages that arrived`() {
        val source = object : DocTracksSource {
            private var call = 0
            override suspend fun search(query: DocTrackQuery): DocTracksResult {
                call++
                return if (call == 1) {
                    DocTracksResult.Found(List(DocTrackDownloader.PAGE) { track(it.toLong()) })
                } else {
                    DocTracksResult.Failed("Unable to resolve host")
                }
            }
        }

        val failed = runBlocking { DocTrackDownloader(source).download(box) } as DocDownloadResult.Failed

        assertEquals("the first page is kept", DocTrackDownloader.PAGE, failed.tracks.size)
        assertTrue("and the reason is the service's", failed.message.contains("Unable to resolve host"))
    }
}
