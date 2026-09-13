package nz.mckenzie.sprayday.ui

import java.util.Locale
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
