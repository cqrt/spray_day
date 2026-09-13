package nz.mckenzie.sprayday.domain.tiles

import kotlin.math.PI
import kotlin.math.cos
import kotlin.math.floor
import kotlin.math.ln
import kotlin.math.tan

/**
 * XYZ (Web Mercator) tile maths, used to estimate the cost of downloading an
 * offline region *before* the user commits to it.
 */
object TileMath {

    /** Usable latitude limit of the Web Mercator projection. */
    const val MAX_MERCATOR_LAT: Double = 85.05112878

    /** Typical raster tile payload. LINZ serves WebP, which is smaller than this. */
    const val DEFAULT_TILE_BYTES: Long = 45_000L

    fun lonToTileX(lng: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val x = floor((normaliseLng(lng) + 180.0) / 360.0 * n).toInt()
        return x.coerceIn(0, n - 1)
    }

    fun latToTileY(lat: Double, zoom: Int): Int {
        val n = 1 shl zoom
        val clampedLat = lat.coerceIn(-MAX_MERCATOR_LAT, MAX_MERCATOR_LAT)
        val latRad = Math.toRadians(clampedLat)
        val y = floor((1.0 - ln(tan(latRad) + 1.0 / cos(latRad)) / PI) / 2.0 * n).toInt()
        return y.coerceIn(0, n - 1)
    }

    /** Inclusive tile range covering [bounds] at [zoom]. */
    fun tileRange(bounds: LatLngBounds, zoom: Int): TileRange {
        val minX = lonToTileX(bounds.minLng, zoom)
        val maxX = lonToTileX(bounds.maxLng, zoom)
        // Note the Y axis is inverted: north (max lat) maps to the smaller tile Y.
        val minY = latToTileY(bounds.maxLat, zoom)
        val maxY = latToTileY(bounds.minLat, zoom)
        return TileRange(minX, maxX, minY, maxY)
    }

    /** Total tiles needed for [bounds] across an inclusive zoom range. */
    fun tileCount(bounds: LatLngBounds, minZoom: Int, maxZoom: Int): Long {
        if (maxZoom < minZoom) return 0L
        var total = 0L
        for (zoom in minZoom..maxZoom) {
            total += tileRange(bounds, zoom).count
        }
        return total
    }

    /** Rough download size estimate in bytes for an offline region. */
    fun estimateBytes(
        bounds: LatLngBounds,
        minZoom: Int,
        maxZoom: Int,
        averageTileBytes: Long = DEFAULT_TILE_BYTES
    ): Long = tileCount(bounds, minZoom, maxZoom) * averageTileBytes

    private fun normaliseLng(lng: Double): Double {
        var value = lng
        while (value < -180.0) value += 360.0
        while (value > 180.0) value -= 360.0
        return value
    }
}

/** Inclusive tile index range at a single zoom level. */
data class TileRange(val minX: Int, val maxX: Int, val minY: Int, val maxY: Int) {
    val count: Long get() = (maxX - minX + 1).toLong() * (maxY - minY + 1).toLong()
}

/** Geographic bounding box. */
data class LatLngBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double
) {
    init {
        require(minLat <= maxLat) { "minLat must be <= maxLat" }
        require(minLng <= maxLng) { "minLng must be <= maxLng" }
    }
}
