package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LAT
import nz.mckenzie.sprayday.domain.geo.METRES_PER_DEG_LNG_AT_EQUATOR
import java.util.Locale
import kotlin.math.cos

/**
 * The marker that says where the phone is, and how well it knows.
 *
 * Two features rather than one. A **dot** at the fix, and a **ring** around it whose radius is
 * the fix's own accuracy - drawn as real geometry, a circle of that many metres, rather than a
 * circle of screen pixels pretending to be one. The difference matters where this app is used:
 * a fix under trees can be out by thirty metres, and a dot the same size as a good one would
 * claim a certainty the phone does not have.
 *
 * A fix with no accuracy gets no ring at all. An unknown accuracy is not a small one, and the
 * honest thing to draw is the dot on its own.
 *
 * Hand-rolled rather than pulling in a JSON library, for the same reason [AssetGeoJson] is:
 * the payload is two features, and keeping it pure Kotlin means fast JVM tests.
 */
object PositionGeoJson {

    /** An empty collection, for a phone that cannot say where it is. */
    const val EMPTY = "{\"type\":\"FeatureCollection\",\"features\":[]}"

    /**
     * Vertices in the accuracy ring.
     *
     * Forty-eight is a 7.5-degree step: indistinguishable from a circle at any zoom this app
     * is used at, and still cheap enough to rebuild on every fix - which is what a stream of
     * fixes means.
     */
    private const val RING_POINTS = 48

    /** The property the layers filter on, in the same idiom as the assets' kind and shape. */
    internal const val PART_PROPERTY = "part"
    internal const val PART_DOT = "dot"
    internal const val PART_ACCURACY = "accuracy"

    fun build(fix: GeoPoint?): String {
        if (fix == null) return EMPTY

        val builder = StringBuilder(64 + RING_POINTS * 48)
        builder.append("{\"type\":\"FeatureCollection\",\"features\":[")

        // The ring first: it is drawn underneath, and a reader of the JSON should meet the
        // uncertainty before the claim.
        if (fix.accuracyM != null && fix.accuracyM > 0f) {
            appendFeature(builder, PART_ACCURACY)
            builder.append(",\"geometry\":{\"type\":\"Polygon\",\"coordinates\":[[")
            appendRing(builder, fix, fix.accuracyM.toDouble())
            builder.append("]]}},")
        }

        appendFeature(builder, PART_DOT)
        builder.append(",\"geometry\":{\"type\":\"Point\",\"coordinates\":")
        appendPoint(builder, fix)
        builder.append("}}")

        builder.append("]}")
        return builder.toString()
    }

    private fun appendFeature(builder: StringBuilder, part: String) {
        builder.append("{\"type\":\"Feature\",\"properties\":{\"")
            .append(PART_PROPERTY).append("\":\"").append(part).append("\"}")
    }

    /**
     * A closed ring of [radiusM] around the fix.
     *
     * Longitude shrinks with latitude and latitude does not, so the two steps are worked out
     * separately - the same treatment [nz.mckenzie.sprayday.domain.geo.distanceToSegmentMeters]
     * gives a segment, and for the same reason: at Blenheim's latitude a degree of longitude is
     * a quarter shorter than a degree of latitude, and a ring drawn as though they matched
     * would be an ellipse nobody asked for.
     */
    private fun appendRing(builder: StringBuilder, fix: GeoPoint, radiusM: Double) {
        val latStep = radiusM / METRES_PER_DEG_LAT
        val lngStep = radiusM / (METRES_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(fix.lat)))

        // Closed: the last vertex repeats the first, because a GeoJSON polygon that does not
        // close is not a polygon.
        for (index in 0..RING_POINTS) {
            val angle = 2.0 * Math.PI * index / RING_POINTS
            if (index > 0) builder.append(',')
            builder.append('[')
                .append(format(fix.lng + lngStep * cos(angle))).append(',')
                .append(format(fix.lat + latStep * Math.sin(angle)))
                .append(']')
        }
    }

    /** GeoJSON is [longitude, latitude] - the opposite of how humans say it. */
    private fun appendPoint(builder: StringBuilder, point: GeoPoint) {
        builder.append('[').append(format(point.lng)).append(',')
            .append(format(point.lat)).append(']')
    }

    private fun format(value: Double): String = String.format(Locale.US, "%.7f", value)
}
