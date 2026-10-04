package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.haversineMeters

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
