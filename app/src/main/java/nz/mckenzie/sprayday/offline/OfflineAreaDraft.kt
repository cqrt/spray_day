package nz.mckenzie.sprayday.offline

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.domain.tiles.TileMath
import kotlin.math.max
import kotlin.math.min

/**
 * An offline area being chosen on the map, before anything is downloaded.
 *
 * Pure data plus tile maths, so the cost the operator sees while moving a zoom slider
 * is unit tested rather than discovered on a hillside with one bar of signal.
 */
data class OfflineAreaDraft(
    val name: String = "",
    /** Corners picked on the map: none, one, or the two that bound the area. */
    val corners: List<GeoPoint> = emptyList(),
    val minZoom: Int = DEFAULT_MIN_ZOOM,
    val maxZoom: Int = DEFAULT_MAX_ZOOM
) {

    val firstCorner: GeoPoint? get() = corners.firstOrNull()

    val secondCorner: GeoPoint? get() = corners.getOrNull(1)

    val isComplete: Boolean get() = corners.size >= 2

    /** The box the two corners define, or null until both are picked. */
    val bounds: LatLngBounds?
        get() {
            val a = firstCorner ?: return null
            val b = secondCorner ?: return null
            // Corners may be tapped in any order and in any direction.
            return LatLngBounds(
                minLat = min(a.lat, b.lat),
                minLng = min(a.lng, b.lng),
                maxLat = max(a.lat, b.lat),
                maxLng = max(a.lng, b.lng)
            )
        }

    val tileCount: Long
        get() = bounds?.let { TileMath.tileCount(it, minZoom, maxZoom) } ?: 0L

    val estimatedBytes: Long
        get() = bounds?.let { TileMath.estimateBytes(it, minZoom, maxZoom) } ?: 0L

    val estimatedSizeLabel: String get() = formatBytes(estimatedBytes)

    /** Past this the manager refuses the plan, so say so while the sliders move. */
    val isTooBig: Boolean get() = tileCount > OfflineAreaManager.MAX_TILES_PER_AREA

    /** The plan to store, named as typed or as [fallbackName] if nothing was typed. */
    fun plan(name: String, fallbackName: String): OfflineAreaPlan? {
        val area = bounds ?: return null
        return OfflineAreaPlan(
            name = name.trim().ifBlank { fallbackName },
            bounds = area,
            minZoom = minZoom,
            maxZoom = maxZoom
        )
    }

    /** Adds a corner, keeping at most two: a third tap starts the box again. */
    fun withCorner(point: GeoPoint): OfflineAreaDraft =
        if (isComplete) copy(corners = listOf(point)) else copy(corners = corners + point)

    fun withoutCorners(): OfflineAreaDraft = copy(corners = emptyList())

    /** Keeps the range sane: the two sliders are not allowed to cross. */
    fun withZoomRange(minimum: Int, maximum: Int): OfflineAreaDraft {
        val low = minimum.coerceIn(MIN_ZOOM, MAX_ZOOM)
        val high = maximum.coerceIn(MIN_ZOOM, MAX_ZOOM)
        // Push the far end rather than swapping: raising the shallowest level above
        // the deepest should not silently pull the shallow end down with it.
        return copy(minZoom = low, maxZoom = max(low, high))
    }

    companion object {
        /**
         * Below 8 a whole block is a dot and there is nothing to see; above 19 every
         * extra level multiplies the download roughly fourfold, so it is opt-in.
         */
        const val MIN_ZOOM = 8
        const val MAX_ZOOM = 19

        const val DEFAULT_MIN_ZOOM = OfflineAreaPlan.DEFAULT_MIN_ZOOM
        const val DEFAULT_MAX_ZOOM = OfflineAreaPlan.DEFAULT_MAX_ZOOM
    }
}
