package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.haversineMeters
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/** How the DOC browser orders what a search found. */
enum class DocTrackSort {
    /** Nearest to the phone first: the track you are most likely to be standing on. */
    NEAREST,

    /** By name, A to Z. */
    NAME
}

/**
 * The metres from [point] to the nearest vertex of this track, or null when it has no vertices.
 *
 * Nearest vertex rather than the track's middle: a long line that passes beside the phone is near the
 * phone, and a centroid would call it far away. That is the distance an operator means by "close".
 */
fun DocTrack.distanceM(point: GeoPoint): Double? =
    reading.paths.flatten()
        .minOfOrNull { vertex -> haversineMeters(point.lat, point.lng, vertex.lat, vertex.lng) }

/**
 * The kinds present in these tracks, in a stable order, for a filter row to offer.
 *
 * Taken from what a search returned rather than from a list of DOC's taxonomy kept here: the filter
 * offers what is actually on the page, so it cannot offer a kind the service no longer uses or miss
 * one it has learned since.
 */
fun List<DocTrack>.kindsPresent(): List<String> =
    mapNotNull { track -> track.kind?.takeIf { it.isNotBlank() } }
        .distinct()
        .sorted()

/**
 * The tracks whose nearest vertex is within [radiusM] of [from], or all of them when [from] is null.
 *
 * The service's own filter is a **bounding box**, and a box reaches its corners about 1.4 times its
 * half-width, so a "50 km" box can hand back a track whose nearest point is 70 km away - and a long
 * track that only clips the corner can stretch far beyond it. This is the circle the operator
 * actually asked for, applied to what the service returned.
 */
fun List<DocTrack>.within(radiusM: Double, from: GeoPoint?): List<DocTrack> {
    from ?: return this
    return filter { track -> (track.distanceM(from) ?: Double.MAX_VALUE) <= radiusM }
}

/**
 * The tracks to show: those of the picked [kinds] (all of them when none is picked), ordered by [sort].
 *
 * A nearest sort with no [from] - the phone has no fix yet - falls back to by name, because with
 * nothing to be near to, the nearest order is no order at all.
 */
fun List<DocTrack>.showing(kinds: Set<String>, sort: DocTrackSort, from: GeoPoint?): List<DocTrack> {
    val ofKind = if (kinds.isEmpty()) this else filter { it.kind in kinds }
    return when {
        sort == DocTrackSort.NAME || from == null -> ofKind.sortedBy { it.name.lowercase() }
        else -> ofKind.sortedBy { it.distanceM(from) ?: Double.MAX_VALUE }
    }
}

/**
 * The box containing every vertex of every track, or null when there are no vertices at all.
 *
 * A downloaded cache has no box of its own the way an imagery area does - it is a set of tracks, not
 * a rectangle - so what the offline screen draws over the map is the ground those tracks actually
 * cover, worked out from the vertices rather than remembered from the download.
 */
fun List<DocTrack>.bounds(): LatLngBounds? {
    var minLat = Double.MAX_VALUE
    var minLng = Double.MAX_VALUE
    var maxLat = -Double.MAX_VALUE
    var maxLng = -Double.MAX_VALUE
    var any = false

    for (track in this) {
        for (path in track.reading.paths) {
            for (point in path) {
                any = true
                if (point.lat < minLat) minLat = point.lat
                if (point.lat > maxLat) maxLat = point.lat
                if (point.lng < minLng) minLng = point.lng
                if (point.lng > maxLng) maxLng = point.lng
            }
        }
    }
    return if (any) LatLngBounds(minLat, minLng, maxLat, maxLng) else null
}
