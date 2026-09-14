package nz.mckenzie.sprayday.domain.gpx

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import java.time.Instant
import java.time.temporal.ChronoUnit

/**
 * Writes GPX 1.1 track files.
 *
 * GPX is the interchange format that LINZ imagery workflows, QGIS, Google Earth
 * and most rural/forestry tools can all read, so a track recorded in Spray Day
 * is never trapped in the app.
 */
object GpxWriter {

    const val CREATOR = "Spray Day"

    fun write(
        assetName: String,
        points: List<GeoPoint>,
        creator: String = CREATOR
    ): String {
        val builder = StringBuilder(256 + points.size * 96)
        builder.append("<?xml version=\"1.0\" encoding=\"UTF-8\"?>\n")
        builder.append("<gpx version=\"1.1\" creator=\"").append(escape(creator)).append('"')
        builder.append(" xmlns=\"http://www.topografix.com/GPX/1/1\">\n")
        builder.append("  <metadata><name>").append(escape(assetName)).append("</name></metadata>\n")
        builder.append("  <trk>\n")
        builder.append("    <name>").append(escape(assetName)).append("</name>\n")
        builder.append("    <trkseg>\n")

        for (point in points) {
            builder.append("      <trkpt lat=\"").append(formatCoordinate(point.lat))
                .append("\" lon=\"").append(formatCoordinate(point.lng)).append("\">\n")
            point.altitudeM?.let {
                builder.append("        <ele>").append(formatElevation(it)).append("</ele>\n")
            }
            if (point.timeMs > 0L) {
                builder.append("        <time>").append(formatTime(point.timeMs)).append("</time>\n")
            }
            point.accuracyM?.let {
                // Stored as HDOP-ish precision metadata so the raw quality survives a round trip.
                builder.append("        <hdop>").append(formatElevation(it.toDouble() / 5.0)).append("</hdop>\n")
            }
            builder.append("      </trkpt>\n")
        }

        builder.append("    </trkseg>\n")
        builder.append("  </trk>\n")
        builder.append("</gpx>\n")
        return builder.toString()
    }

    private fun formatCoordinate(value: Double): String = String.format(java.util.Locale.US, "%.7f", value)

    private fun formatElevation(value: Double): String = String.format(java.util.Locale.US, "%.2f", value)

    /** ISO-8601 UTC, whole seconds - the format GPX 1.1 specifies. */
    fun formatTime(epochMs: Long): String =
        Instant.ofEpochMilli(epochMs).truncatedTo(ChronoUnit.SECONDS).toString()

    internal fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '&' -> append("&amp;")
                '<' -> append("&lt;")
                '>' -> append("&gt;")
                '"' -> append("&quot;")
                '\'' -> append("&apos;")
                else -> append(ch)
            }
        }
    }
}
