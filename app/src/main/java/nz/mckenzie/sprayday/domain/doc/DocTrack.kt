package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.track.TrackInterchange

/**
 * One track the DOC Tracks service holds, ready to import: its own name and kind, and the paths it
 * is drawn as.
 *
 * [reading] is the same reading a file gets - the line and its side tracks - so a track pulled from
 * the web service is the same kind of thing as a track pulled from a file, and the metres and points
 * here are the phone's own arithmetic over the vertices rather than a number the service supplied.
 */
data class DocTrack(
    /** The service's `OBJECTID`, which is its key and how a ticked row is remembered. */
    val objectId: Long,
    val name: String,
    /** `SubObjectType`: Walking Track, Tramping Track, Route, Short Walk. Null when the service omits it. */
    val kind: String?,
    val reading: TrackInterchange.Reading
) {
    val lengthM: Double get() = AssetGeometry(reading.paths).lengthM

    val pointCount: Int get() = reading.paths.sumOf { it.size }

    /**
     * The source reference the asset it becomes carries: `doc:<OBJECTID>`.
     *
     * This is what lets the browser tell a track it has already imported from one it has not - see
     * [nz.mckenzie.sprayday.data.db.AssetEntity.sourceRef] - and it is derived rather than stored so
     * the phone and the desk cannot disagree about what a track's reference is.
     */
    val sourceRef: String get() = docSourceRef(objectId)
}

/** The source reference an asset imported from DOC carries, from the track's own service key. */
fun docSourceRef(objectId: Long): String = "doc:$objectId"
