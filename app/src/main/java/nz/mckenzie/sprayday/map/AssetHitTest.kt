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
     * How close a tap has to be to a line **being drawn** to mean "put the point on the line".
     *
     * Smaller than [FINGERTIP_PX] on purpose, and a different question from [toleranceForZoom]: a
     * fingertip answers *which* line was tapped, out of everything on the map, while a line is being
     * drawn the question is *where on it* - every tap is near the line, so a fingertip's worth of
     * slack swallows the taps that were meant to carry the line on. Twelve pixels is about a drawn
     * line's own width plus a little, and it is the desk's own rule for the same gesture
     * (`geometry.mjs`'s `SNAP_PX`).
     */
    const val ON_THE_LINE_PX = 12.0

    /**
     * What [ON_THE_LINE_PX] is worth in metres, for a caller with no zoom to hand - a test, or a
     * drawing that has not settled yet. The map always passes the zoom's own answer instead.
     */
    const val DEFAULT_ON_THE_LINE_TOLERANCE_M = 12.0

    /**
     * Metres on the ground that a fingertip covers at [zoom], so tapping a track works
     * zoomed right out as well as zoomed in. A fixed tolerance cannot: 40 m is generous
     * at spray height and narrower than a pixel at country scale.
     */
    fun toleranceForZoom(zoom: Double, latitude: Double): Double =
        (metresPerPixel(zoom, latitude) * FINGERTIP_PX).coerceAtLeast(DEFAULT_TOLERANCE_M)

    /**
     * Metres on the ground that a line's own width covers at [zoom]: on the line, rather than near it.
     *
     * No floor, unlike [toleranceForZoom]. A floor is right for a fingertip, which is roughly the same
     * size at every zoom, and wrong for a line: the line is drawn the same few pixels wide whether the
     * map is showing a paddock or an island, so what "on it" means shrinks and grows with the ground
     * under it. A floor here is what made a tap at the end of a line look ignored - see
     * [nz.mckenzie.sprayday.viewmodel.DrawAssetViewModel.addPoint].
     */
    fun onTheLineToleranceForZoom(zoom: Double, latitude: Double): Double =
        metresPerPixel(zoom, latitude) * ON_THE_LINE_PX

    /** Web Mercator: metres across one pixel at [zoom] and [latitude]. */
    private fun metresPerPixel(zoom: Double, latitude: Double): Double =
        156_543.03392 * kotlin.math.cos(Math.toRadians(latitude)) / Math.pow(2.0, zoom)

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
