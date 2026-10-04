package nz.mckenzie.sprayday.doc

import nz.mckenzie.sprayday.domain.doc.DocBounds
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.DocTrackQuery

/** How a download of DOC's tracks for an area went. */
sealed interface DocDownloadResult {

    /** Every track the service had in the area. */
    data class Done(val tracks: List<DocTrack>) : DocDownloadResult

    /**
     * The download stopped short, and [message] says why.
     *
     * [tracks] are the pages that did arrive before it stopped, because a half-downloaded area is
     * still worth keeping: the next attempt resumes rather than starting over.
     */
    data class Failed(val message: String, val tracks: List<DocTrack>) : DocDownloadResult
}

/**
 * Reads every DOC track in a box, a page at a time.
 *
 * The service answers at most 2,000 tracks a request, so an area with more is read by asking again
 * from where the last page ended - which is why [DocTrackQuery.offset] exists. The total is capped:
 * a box over a whole island is not an offline area for spraying a block, and a runaway download is
 * worse than a short one.
 *
 * Kept apart from storage so the paging can be tested against a fake service with no network and no
 * database - the pages are the whole of what it decides.
 */
class DocTrackDownloader(
    private val source: DocTracksSource,
    private val pageSize: Int = PAGE
) {

    suspend fun download(bounds: DocBounds): DocDownloadResult {
        val all = mutableListOf<DocTrack>()
        var offset = 0
        while (true) {
            val page = source.search(
                DocTrackQuery(bounds = bounds, limit = pageSize, offset = offset)
            )
            when (page) {
                is DocTracksResult.Failed -> return DocDownloadResult.Failed(page.message, all)
                is DocTracksResult.Found -> {
                    all += page.tracks
                    // A short page is the last one; a full one means there is more behind it.
                    if (page.tracks.size < pageSize || all.size >= MAX_TRACKS) break
                    offset += page.tracks.size
                }
            }
        }
        return DocDownloadResult.Done(all)
    }

    companion object {
        /** The service's own ceiling per request, so one page is one request. */
        const val PAGE = 2000

        /** Where a download stops, whatever the box. A block is a few hundred tracks. */
        const val MAX_TRACKS = 4000
    }
}
