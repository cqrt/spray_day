package nz.mckenzie.sprayday.tracking

import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.domain.tiles.FRAME_HALF_WIDTH_DEGREES
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

/**
 * What to say when the phone cannot say where it is.
 *
 * Shared rather than worded per screen, because the two screens that put a map in front of
 * somebody say the same thing when there is no fix: the offline picker falls back to the
 * middle of the assets, and the map falls back to a country-wide view, and in both cases the
 * operator is owed the same sentence about why.
 */
object LocationUnavailable {
    const val MESSAGE =
        "Location is not available to the app, so there is nothing to centre on. " +
            "Allow location for Spray Day, or check that location is switched on."
}

/**
 * A small frame around where the phone is, for centring a map on the operator.
 *
 * Every screen that puts a map in front of someone about to drive or walk something -
 * the map itself, drawing, recording - wants the same first frame, and none of them
 * should be looking at a neutral country-wide view when the phone knows perfectly well
 * where it is. That view sat on Nelson, which is exactly right for one person and
 * twenty minutes of confused panning for everybody else.
 *
 * Null when the phone cannot say where it is - no permission, no provider, no answer in
 * time - so callers fall back to that neutral view rather than holding a screen up. The
 * timeout is short for the same reason: this is a first frame, not a measurement.
 */
suspend fun LocationSource.frameOnDevice(
    timeoutMs: Long = 5_000L,
    halfWidthDegrees: Double = FRAME_HALF_WIDTH_DEGREES
): LatLngBounds? {
    val fix = withTimeoutOrNull(timeoutMs) {
        runCatching { currentLocation() }.getOrNull()
    } ?: return null

    return LatLngBounds(
        minLat = fix.lat - halfWidthDegrees,
        minLng = fix.lng - halfWidthDegrees,
        maxLat = fix.lat + halfWidthDegrees,
        maxLng = fix.lng + halfWidthDegrees
    )
}
