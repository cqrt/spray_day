package nz.mckenzie.sprayday.ui

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import kotlin.math.abs
import kotlin.math.roundToInt

/** "850 m" / "2.35 km" - operator-facing distances. */
fun formatDistance(meters: Double): String = when {
    meters < 0.0 -> "-"
    meters < 1000.0 -> "${meters.roundToInt()} m"
    else -> String.format(Locale.US, "%.2f km", meters / 1000.0)
}

/** "420 m²" / "1.2 ha" - treated area for a swath width. */
fun formatArea(squareMetres: Double): String = when {
    squareMetres <= 0.0 -> "-"
    squareMetres < 10_000.0 -> "${squareMetres.roundToInt()} m\u00b2"
    else -> String.format(Locale.US, "%.1f ha", squareMetres / 10_000.0)
}

/** "45s" / "12m 30s" / "1h 05m" - elapsed recording time. */
fun formatDuration(millis: Long): String {
    if (millis <= 0L) return "0s"
    val totalSeconds = millis / 1000
    val hours = totalSeconds / 3600
    val minutes = (totalSeconds % 3600) / 60
    val seconds = totalSeconds % 60
    return when {
        hours > 0 -> String.format(Locale.US, "%dh %02dm", hours, minutes)
        minutes > 0 -> String.format(Locale.US, "%dm %02ds", minutes, seconds)
        else -> "${seconds}s"
    }
}

/** "13 Sep 2026" - local calendar date of an instant, for spray history. */
fun formatDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))

/** "13 Sep" - a short label, used to suggest a name for a freshly recorded track. */
fun formatShortDate(epochMs: Long, zone: ZoneId = ZoneId.systemDefault()): String =
    Instant.ofEpochMilli(epochMs).atZone(zone).format(DateTimeFormatter.ofPattern("d MMM", Locale.US))

/** "every 1 day" / "every 45 days" - a track's own spray cycle. */
fun formatIntervalDays(days: Int): String = "every $days day" + if (days == 1) "" else "s"

/**
 * "46.4130°S, 168.3480°E" - where something is, in a form that works offline.
 *
 * A place name would read better, but reverse geocoding needs a connection and can
 * fail exactly when the operator is deciding what to cache *because* they are about
 * to lose reception.
 */
fun formatCoordinates(lat: Double, lng: Double): String = String.format(
    Locale.US,
    "%.4f\u00b0%s, %.4f\u00b0%s",
    abs(lat),
    if (lat < 0.0) "S" else "N",
    abs(lng),
    if (lng < 0.0) "W" else "E"
)
