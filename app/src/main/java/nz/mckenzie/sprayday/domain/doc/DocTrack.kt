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
    /**
     * `WEB_TRACK_TYPE`: how the track is walked - "One way", "Loop", "Return", and the like. Null when
     * DOC holds no value for it.
     *
     * DOC keeps this as one of a track's up-to-eleven pivoted characteristics (`CharName1..11` paired
     * with `CharValue1..11`) rather than as a column of its own, so it is found by name in the answer
     * rather than read from a fixed field - see [DocTracksJson].
     */
    val trackType: String? = null,
    /**
     * `FlocID`: the asset's functional-location key, e.g. `CATLNSCO-TK11`.
     *
     * Its prefix names the site the track is at - see [DocPlaces] - which is how an imported asset is
     * given a place: the record itself carries no place name.
     */
    val flocId: String? = null,
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

/**
 * DOC's own name for the characteristic that says how a track is walked: `WEB_TRACK_TYPE`, whose value
 * is "One way", "Loop", "Return" and the like.
 *
 * The name is DOC's, not the app's: it is the key to look for among the answer's pivoted
 * characteristics - see [DocTrack.trackType].
 */
const val TRACK_TYPE_CHARACTERISTIC = "WEB_TRACK_TYPE"

/**
 * How many characteristic slots DOC offers a track (`CharName1..N` with `CharValue1..N`).
 *
 * Eleven, and the slot a given characteristic lands in varies by row - which is why the type is found
 * by its name rather than by a column number.
 */
const val CHARACTERISTIC_SLOTS = 11
