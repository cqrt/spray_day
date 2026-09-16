package nz.mckenzie.sprayday.tracking

import kotlinx.coroutines.withTimeoutOrNull
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds

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
    halfWidthDegrees: Double = 0.01
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
