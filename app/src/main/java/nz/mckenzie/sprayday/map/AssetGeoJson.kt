package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint

/** One planned track ready to be drawn on the map. */
data class AssetLine(
    val assetId: Long,
    val name: String,
    val colorHex: String,
    val points: List<GeoPoint>
)

/**
 * Colours for the traffic-light system, matching the Compose theme
 * (ui/theme/Color.kt). Kept here as plain strings so the map layer can be unit
 * tested on the JVM without Compose on the classpath.
 */
object AssetColors {
    const val GREEN = "#2E7D32"
    const val YELLOW = "#F9A825"
    const val RED = "#C62828"
    const val UNKNOWN = "#757575"

    /** How a track is drawn on a map and marked in a list. */
    const val TRACK_KIND = "#1565C0"

    /** Roads. */
    const val ROAD_KIND = "#6A1B9A"

    /** Fencelines and structures. */
    const val INFRASTRUCTURE_KIND = "#4E342E"

    /**
     * Never-sprayed tracks are drawn red: they still need spraying, but the
     * distinct [DueStatus] lets the UI add its own "no history" wording.
     */
    fun forStatus(status: DueStatus): String = when (status) {
        DueStatus.NOT_DUE -> GREEN
        DueStatus.DUE_SOON -> YELLOW
        DueStatus.OVERDUE, DueStatus.NEVER_SPRAYED -> RED
    }

    /**
     * The colour of the icon that says what an asset *is*, as opposed to when it is
     * due. None of these is the traffic-light green, amber or red on purpose: an icon
     * that could be mistaken for a due date would be worse than no icon.
     */
    fun forKind(kind: AssetKind): String = when (kind) {
        AssetKind.TRACK -> TRACK_KIND
        AssetKind.ROAD -> ROAD_KIND
        AssetKind.INFRASTRUCTURE -> INFRASTRUCTURE_KIND
    }
}

/**
 * Builds the GeoJSON that MapLibre draws for the track network.
 *
 * Hand-rolled rather than pulling in a JSON library: the payload is tiny, and
 * keeping it pure Kotlin means it is covered by fast JVM unit tests.
 */
object AssetGeoJson {

    private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    fun build(lines: List<AssetLine>): String {
        val drawable = lines.filter { it.points.size >= 2 }
        if (drawable.isEmpty()) return EMPTY

        val builder = StringBuilder(128 + drawable.size * 256)
        builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
        drawable.forEachIndexed { index, line ->
            if (index > 0) builder.append(',')
            builder.append("{\"type\":\"Feature\",\"properties\":{")
            builder.append("\"id\":").append(line.assetId).append(',')
            builder.append("\"name\":\"").append(escape(line.name)).append("\",")
            builder.append("\"stroke\":\"").append(escape(line.colorHex)).append("\"")
            builder.append("},\"geometry\":{\"type\":\"LineString\",\"coordinates\":[")
            line.points.forEachIndexed { pointIndex, point ->
                if (pointIndex > 0) builder.append(',')
                // GeoJSON is [longitude, latitude] - the opposite of how humans say it.
                builder.append('[').append(format(point.lng)).append(',')
                    .append(format(point.lat)).append(']')
            }
            builder.append("]}}")
        }
        builder.append("]}")
        return builder.toString()
    }

    private fun format(value: Double): String =
        String.format(java.util.Locale.US, "%.7f", value)

    internal fun escape(value: String): String = buildString(value.length) {
        for (ch in value) {
            when (ch) {
                '\\' -> append("\\\\")
                '"' -> append("\\\"")
                '\n' -> append("\\n")
                '\r' -> append("\\r")
                '\t' -> append("\\t")
                else -> if (ch.code < 0x20) append(' ') else append(ch)
            }
        }
    }
}
