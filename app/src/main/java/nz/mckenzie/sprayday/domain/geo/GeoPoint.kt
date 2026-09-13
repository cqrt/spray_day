package nz.mckenzie.sprayday.domain.geo

/**
 * A single geographic fix.
 *
 * [timeMs] is epoch milliseconds (UTC). Fields that a device may not report
 * (altitude, accuracy, speed, bearing) are nullable rather than defaulted so
 * that "unknown" is never confused with "zero".
 */
data class GeoPoint(
    val lat: Double,
    val lng: Double,
    val altitudeM: Double? = null,
    val accuracyM: Float? = null,
    val speedMps: Float? = null,
    val bearingDeg: Float? = null,
    val timeMs: Long = 0L
)
