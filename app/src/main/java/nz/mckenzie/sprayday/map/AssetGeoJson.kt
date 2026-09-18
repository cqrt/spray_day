package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * One stretch of an asset's line, drawn in its own colour.
 *
 * The map needs these because a track can be in two states at once: half of it sprayed this
 * morning and half of it still to do. One colour for the whole line cannot say that.
 */
data class AssetStretch(
    val colorHex: String,
    val points: List<GeoPoint>
)

/** One planned asset ready to be drawn on the map. */
data class AssetLine(
    val assetId: Long,
    val name: String,
    val colorHex: String,
    val points: List<GeoPoint>,
    /**
     * What it is, which decides how it is drawn: solid, dashed, dotted, or a circle.
     *
     * Defaulted for the map's previews - a line being drawn, or a planned line shown
     * over a recording - which are not assets and have no kind to be drawn by.
     */
    val kind: AssetKind = AssetKind.TRACK,
    /** A line to travel along, or a single place to stop at. */
    val shape: AssetShape = AssetShape.LINE,
    /**
     * Set when the line is not all in one state - a track half sprayed - in which case these
     * are drawn instead of the single line above, one feature each.
     *
     * Every one of them carries this asset's id, so a tap on any part of the line opens the
     * asset, and the map's layers filter on kind and shape exactly as they did before.
     */
    val stretches: List<AssetStretch> = emptyList()
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

    /**
     * One colour per kind, for the icon beside an asset in the list.
     *
     * Two things decide these. None of them may be the traffic-light green, amber or
     * red, because the icon says what something is while the dot beside it says when it
     * is due. And they all have to be readable as a thin outline on the card the theme
     * puts behind them - which the app follows from the system, so that card is
     * near-white in light mode and near-black in dark mode. Brown and deep purple were
     * fine on the first and all but invisible on the second, which is what
     * [nz.mckenzie.sprayday.map.AssetColorsTest] now tests for.
     */
    const val TRACK_KIND = "#1E88E5"

    /** Roads. */
    const val ROAD_KIND = "#AB47BC"

    /** Fencelines and structures: teal, the one hue left that is nobody else's. */
    const val INFRASTRUCTURE_KIND = "#00897B"

    /**
     * Where the phone is.
     *
     * Charcoal with a white ring, which is what a device marker looks like everywhere else -
     * and here that convention is also the requirement. It must not be readable as a due
     * colour or as a kind colour, and the obvious choice for "you are here" is the one thing
     * it cannot be: a blue dot would sit beside [TRACK_KIND] and be taken for one. Nothing
     * else on this map is this dark, so a dot this dark is the phone.
     */
    const val POSITION = "#212121"

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
    }}

/**
 * Builds the GeoJSON that MapLibre draws for the asset network.
 *
 * Each feature carries what the map needs and nothing more: its id for tapping, its
 * traffic-light colour, its kind and its shape. The kind and shape are properties
 * rather than baked into the geometry because the layers read them - the kind picks
 * which line layer draws a feature, and a point asset is drawn as a circle rather
 * than a line at all.
 *
 * Hand-rolled rather than pulling in a JSON library: the payload is tiny, and
 * keeping it pure Kotlin means it is covered by fast JVM unit tests.
 */
object AssetGeoJson {

    private const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    fun build(lines: List<AssetLine>): String {
        // A line needs two points to be a line; a place needs only the one it is at.
        val drawable = lines.filter { it.points.size >= if (it.shape == AssetShape.POINT) 1 else 2 }
        if (drawable.isEmpty()) return EMPTY

        val builder = StringBuilder(128 + drawable.size * 256)
        builder.append("{\"type\":\"FeatureCollection\",\"features\":[")
        var written = 0
        drawable.forEach { line ->
            stretchesOf(line).forEach { stretch ->
                if (written > 0) builder.append(',')
                appendFeature(builder, line, stretch)
                written++
            }
        }
        builder.append("]}")
        return builder.toString()
    }

    /**
     * What one asset is drawn as: the whole line in its own colour, or the stretches of it
     * that are in different states.
     *
     * A stretch too short to be a line is dropped rather than drawn as a dot where the line
     * should be, and an asset whose stretches all came out that way falls back to being
     * drawn whole - a map that has lost a track is worse than one that shows it in one
     * colour.
     */
    private fun stretchesOf(line: AssetLine): List<AssetStretch> {
        val whole = AssetStretch(colorHex = line.colorHex, points = line.points)
        if (line.shape == AssetShape.POINT) return listOf(whole)
        return line.stretches.filter { it.points.size >= 2 }.ifEmpty { listOf(whole) }
    }

    private fun appendFeature(builder: StringBuilder, line: AssetLine, stretch: AssetStretch) {
        builder.append("{\"type\":\"Feature\",\"properties\":{")
        builder.append("\"id\":").append(line.assetId).append(',')
        builder.append("\"name\":\"").append(escape(line.name)).append("\",")
        builder.append("\"stroke\":\"").append(escape(stretch.colorHex)).append("\",")
        builder.append("\"kind\":\"").append(line.kind.name).append("\",")
        builder.append("\"shape\":\"").append(line.shape.name).append("\"")
        builder.append("},\"geometry\":{")
        if (line.shape == AssetShape.POINT) {
            builder.append("\"type\":\"Point\",\"coordinates\":")
            appendPoint(builder, stretch.points.first())
        } else {
            builder.append("\"type\":\"LineString\",\"coordinates\":[")
            stretch.points.forEachIndexed { pointIndex, point ->
                if (pointIndex > 0) builder.append(',')
                appendPoint(builder, point)
            }
            builder.append("]")
        }
        builder.append("}}")
    }

    /** GeoJSON is [longitude, latitude] - the opposite of how humans say it. */
    private fun appendPoint(builder: StringBuilder, point: GeoPoint) {
        builder.append('[').append(format(point.lng)).append(',')
            .append(format(point.lat)).append(']')
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
