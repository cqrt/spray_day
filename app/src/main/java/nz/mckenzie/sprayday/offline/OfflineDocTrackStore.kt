package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.flow.Flow
import nz.mckenzie.sprayday.data.db.OfflineDocTrackDao
import nz.mckenzie.sprayday.data.db.OfflineDocTrackEntity
import nz.mckenzie.sprayday.doc.DocDownloadResult
import nz.mckenzie.sprayday.doc.DocTrackDownloader
import nz.mckenzie.sprayday.domain.doc.DocBounds
import nz.mckenzie.sprayday.domain.doc.DocPathsJson
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.DocTrackCache
import nz.mckenzie.sprayday.domain.doc.bounds
import nz.mckenzie.sprayday.domain.track.TrackInterchange
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/**
 * DOC's tracks kept on the phone, beside the offline imagery.
 *
 * The same shape as [OfflineAreaManager]: a download pulls what the service has for a box, and this
 * records it so the browser can offer it with no reception. It is a cache rather than the record of
 * the farm - clearing it costs nothing but the download - so it is not backed up and not joined to
 * anything.
 *
 * Stored globally rather than per area: a track is the same track wherever it was downloaded from, and
 * two overlapping areas asking for it should end with one copy, not two. Keying by the service's own
 * `OBJECTID` is what makes that so.
 */
class OfflineDocTrackStore(
    private val dao: OfflineDocTrackDao,
    private val downloader: DocTrackDownloader,
    private val now: () -> Long = System::currentTimeMillis
) : DocTrackCache {

    override suspend fun cached(): List<DocTrack> = dao.getAll().mapNotNull { it.toDocTrack() }

    override suspend fun count(): Int = dao.count()

    override suspend fun store(tracks: List<DocTrack>) {
        if (tracks.isEmpty()) return
        dao.insertAll(tracks.map { it.toEntity(now()) })
    }

    override suspend fun clear() {
        dao.deleteAll()
    }

    /** A live count, for the offline screen to say what is stored. */
    fun observeCount(): Flow<Int> = dao.observeCount()

    /**
     * The ground the downloaded tracks cover, for the offline screen's map, or null when there is
     * nothing downloaded. Worked out from the vertices, because a cache of tracks has no box of its
     * own the way an imagery area does.
     */
    suspend fun bounds(): LatLngBounds? = cached().bounds()

    /**
     * Downloads every track in [bounds] and stores them, however the read went.
     *
     * A download that stopped short stores the pages that arrived: the next attempt asks the service
     * again rather than starting from a blank cache, and what did arrive is already usable offline.
     */
    suspend fun download(bounds: DocBounds): DocDownloadResult {
        val result = downloader.download(bounds)
        store(
            when (result) {
                is DocDownloadResult.Done -> result.tracks
                is DocDownloadResult.Failed -> result.tracks
            }
        )
        return result
    }
}

/**
 * A cache row back into a track.
 *
 * The reading's own side-track count is taken from the paths it was stored with - a cached track has
 * one line and its side tracks - and `segmentsDidNotMeet` is not stored because nothing a cache is
 * used for depends on it. The metres and the points are worked out from the vertices, as everywhere.
 */
private fun OfflineDocTrackEntity.toDocTrack(): DocTrack? {
    val paths = DocPathsJson.read(pathsJson)
    if (paths.isEmpty()) return null
    return DocTrack(
        objectId = objectId,
        name = name,
        kind = kind,
        reading = TrackInterchange.Reading(
            paths = paths,
            sideTracks = (paths.size - 1).coerceAtLeast(0),
            segmentsDidNotMeet = false
        )
    )
}

private fun DocTrack.toEntity(at: Long) = OfflineDocTrackEntity(
    objectId = objectId,
    name = name,
    kind = kind,
    lengthM = lengthM,
    pathsJson = DocPathsJson.write(reading.paths),
    downloadedAtEpochMs = at
)

/** The sentence a download's track count earns, in the operator's own terms. */
fun docDownloadedMessage(count: Int): String = when {
    count == 0 -> "No DOC tracks were in that area."
    count == 1 -> "Downloaded 1 DOC track."
    else -> "Downloaded $count DOC tracks."
}
