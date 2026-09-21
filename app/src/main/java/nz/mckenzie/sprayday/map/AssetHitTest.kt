package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.distanceToPolylineMeters

/**
 * Working out which track an operator tapped.
 *
 * A track is a thin line and a fingertip is wide, so this is not "was the tap on the
 * line" but "which line was the tap nearest, if any". The map already holds every
 * track's geometry for drawing, so the answer costs no database access - which keeps
 * the tap instant on a map full of tracks.
 */
object AssetHitTest {

    /**
     * How close a tap has to land. Roughly a fingertip width at a working zoom: big
     * enough that tapping does not feel fussy, small enough that a tap on empty
     * paddock does not open the wrong block.
     *
     * Callers that know the map's zoom should pass the tolerance in metres that a
     * fingertip covers at that zoom instead - see [AssetHitTest.toleranceForZoom].
     */
    const val DEFAULT_TOLERANCE_M = 40.0

    /** A fingertip, in pixels. Used to work out the radius at a given zoom. */
    private const val FINGERTIP_PX = 24.0

    /**
     * Metres on the ground that a fingertip covers at [zoom], so tapping a track works
     * zoomed right out as well as zoomed in. A fixed tolerance cannot: 40 m is generous
     * at spray height and narrower than a pixel at country scale.
     */
    fun toleranceForZoom(zoom: Double, latitude: Double): Double {
        val metresPerPixel = 156_543.03392 * kotlin.math.cos(Math.toRadians(latitude)) /
            Math.pow(2.0, zoom)
        return (metresPerPixel * FINGERTIP_PX).coerceAtLeast(DEFAULT_TOLERANCE_M)
    }

    /** The id of the nearest track to the tap, or null if the tap was not on one. */
    fun nearest(
        geometryByTrack: Map<Long, AssetGeometry>,
        lat: Double,
        lng: Double,
        toleranceM: Double = DEFAULT_TOLERANCE_M
    ): Long? {
        if (geometryByTrack.isEmpty()) return null
        val tap = GeoPoint(lat = lat, lng = lng)

        var bestId: Long? = null
        var bestDistance = Double.MAX_VALUE

        // Sorted so that two equally close tracks always resolve the same way, rather
        // than however the map happened to be built.
        for (assetId in geometryByTrack.keys.sorted()) {
            val geometry = geometryByTrack.getValue(assetId)
            // Every path of the asset, and the nearest of them: a tap on a side track is a tap on the
            // track it hangs off, which is the whole point of drawing it there.
            val distance = geometry.paths
                .map { path -> distanceToPolylineMeters(tap, path) }
                .filter { value -> !value.isNaN() }
                .minOrNull() ?: continue

            if (distance < bestDistance) {
                bestDistance = distance
                bestId = assetId
            }
        }

        return if (bestDistance <= toleranceM) bestId else null
    }
}
