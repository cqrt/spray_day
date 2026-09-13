package nz.mckenzie.sprayday.offline

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.domain.tiles.TileMath
import kotlin.math.cos
import kotlin.math.max

/**
 * A rectangular area to cache for offline use, with an inclusive zoom range.
 *
 * Kept as pure data (plus tile maths) so the size estimate shown before a
 * download can be unit tested without touching MapLibre or the network.
 */
data class OfflineAreaPlan(
    val name: String,
    val bounds: LatLngBounds,
    val minZoom: Int = DEFAULT_MIN_ZOOM,
    val maxZoom: Int = DEFAULT_MAX_ZOOM
) {
    val tileCount: Long get() = TileMath.tileCount(bounds, minZoom, maxZoom)

    val estimatedBytes: Long get() = TileMath.estimateBytes(bounds, minZoom, maxZoom)

    val estimatedSizeLabel: String get() = formatBytes(estimatedBytes)

    /** Roughly how far the plan reaches from its centre, useful in the UI. */
    val approxWidthKm: Double
        get() = (bounds.maxLng - bounds.minLng) * 111.32 * cos(Math.toRadians((bounds.minLat + bounds.maxLat) / 2))

    companion object {
        /**
         * Zoom 10-16 keeps a spray block in the tens of megabytes while still
         * showing individual tracks clearly. The low zooms cost almost nothing
         * (one tile covers a whole block at zoom 10) but they matter: an offline
         * pack that starts at zoom 12 cannot render the zoom-11 overview the app
         * opens on. Zoom 17+ multiplies the tile count roughly fourfold per
         * level, so it is opt-in by extending the range.
         */
        const val DEFAULT_MIN_ZOOM = 10
        const val DEFAULT_MAX_ZOOM = 16

        /** Builds a square-ish area around a centre point. */
        fun aroundCentre(
            name: String,
            centre: GeoPoint,
            radiusKm: Double,
            minZoom: Int = DEFAULT_MIN_ZOOM,
            maxZoom: Int = DEFAULT_MAX_ZOOM
        ): OfflineAreaPlan {
            val latDelta = radiusKm / 111.32
            val lngDelta = radiusKm / (111.32 * max(0.05, cos(Math.toRadians(centre.lat))))
            return OfflineAreaPlan(
                name = name,
                bounds = LatLngBounds(
                    minLat = (centre.lat - latDelta).coerceAtLeast(-85.0),
                    minLng = (centre.lng - lngDelta).coerceAtLeast(-180.0),
                    maxLat = (centre.lat + latDelta).coerceAtMost(85.0),
                    maxLng = (centre.lng + lngDelta).coerceAtMost(180.0)
                ),
                minZoom = minZoom,
                maxZoom = maxZoom
            )
        }
    }
}

/**
 * Human-readable byte size, e.g. "68.4 MB".
 *
 * One decimal is shown for KB/MB/GB, and a trailing ".0" is dropped, so sizes
 * read naturally: "512 B", "1 KB", "1.5 KB", "1 MB", "2.5 GB".
 */
fun formatBytes(bytes: Long): String = when {
    bytes < 1024L -> "$bytes B"
    bytes < 1024L * 1024L -> label(bytes / 1024.0, "KB")
    bytes < 1024L * 1024L * 1024L -> label(bytes / (1024.0 * 1024.0), "MB")
    else -> label(bytes / (1024.0 * 1024.0 * 1024.0), "GB")
}

private fun label(value: Double, unit: String): String {
    val rounded = Math.round(value * 10.0) / 10.0
    return if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
        "${rounded.toLong()} $unit"
    } else {
        String.format(java.util.Locale.US, "%.1f $unit", rounded)
    }
}
