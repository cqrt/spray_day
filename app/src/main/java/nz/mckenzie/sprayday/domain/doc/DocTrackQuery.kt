package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import java.net.URLEncoder
import java.util.Locale
import kotlin.math.cos

/**
 * What to ask the DOC Tracks service for: a name to look for, and optionally a place to look near.
 *
 * Neither is a filter the phone applies itself - both go to the service, which is the only thing
 * that can answer them without downloading all 3,255 tracks. A name is a substring match on
 * `TechObjectName`; [near] with [radiusKm] is an envelope around a fix; [bounds] is the map's own
 * view, for a desk that has panned to the paddock it means. [bounds] wins when it is given, because
 * it is the operator's own answer to "where".
 */
data class DocTrackQuery(
    val nameContains: String = "",
    val near: GeoPoint? = null,
    val radiusKm: Double = 25.0,
    val bounds: DocBounds? = null,
    val limit: Int = DEFAULT_LIMIT
) {
    companion object {
        /** How many tracks one search asks for. The service's own ceiling is 2,000. */
        const val DEFAULT_LIMIT = 300
    }
}

/** A box on the ground, south-west to north-east, in degrees. */
data class DocBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double
)

/**
 * The URL that asks the DOC Tracks service a question, and gets GeoJSON back.
 *
 * The DOC dataset is a hosted ArcGIS Feature Service, so "searching" is a REST query rather than a
 * download: `f=geojson` returns a FeatureCollection the app's own [nz.mckenzie.sprayday.domain.track.GeoJsonParser]
 * already reads, and `outSR=4326` has ArcGIS reproject from the service's native NZTM2000 (EPSG:2193)
 * to the WGS84 the app stores - so no projection arithmetic lives here.
 *
 * **The one place the service is named.** This is the *Deprecated* DOC Tracks endpoint; when DOC
 * publishes its replacement, [SERVICE_URL] is the single line to change.
 */
object DocTracksUrl {

    /** The DOC Tracks layer. Deprecated by DOC, with a replacement to come: see the object's own note. */
    const val SERVICE_URL =
        "https://services1.arcgis.com/3JjYDyG3oajxU6HO/arcgis/rest/services/DOC_Tracks_EAM/FeatureServer/0/query"

    /** The fields the browser needs: the key, the name, the kind, and the asset id for reference. */
    const val OUT_FIELDS = "OBJECTID,TechObjectName,SubObjectType,FlocID"

    fun of(query: DocTrackQuery, serviceUrl: String = SERVICE_URL): String {
        val parameters = linkedMapOf(
            "where" to where(query.nameContains)
        )
        // The map's own view when the desk gave one, else a box around a fix. One envelope either way,
        // because that is the one shape the service filters by.
        val envelope = when {
            query.bounds != null -> envelope(query.bounds)
            query.near != null -> envelope(query.near, query.radiusKm)
            else -> null
        }
        if (envelope != null) {
            parameters["geometry"] = envelope
            parameters["geometryType"] = "esriGeometryEnvelope"
            parameters["inSR"] = "4326"
            parameters["spatialRel"] = "esriSpatialRelIntersects"
        }
        parameters["outFields"] = OUT_FIELDS
        parameters["returnGeometry"] = "true"
        parameters["outSR"] = "4326"
        parameters["orderByFields"] = "OBJECTID"
        parameters["resultOffset"] = "0"
        parameters["resultRecordCount"] = query.limit.coerceIn(1, MAX_RECORD_COUNT).toString()
        parameters["f"] = "geojson"

        val queryString = parameters.entries.joinToString("&") { (key, value) ->
            "$key=${URLEncoder.encode(value, "UTF-8")}"
        }
        return "$serviceUrl?$queryString"
    }

    /**
     * The `where` clause: the name contains the words, case-insensitively.
     *
     * A quote in the words is doubled, which is how a SQL string escapes one - so a name with an
     * apostrophe searches for that name rather than ending the clause. Everything else is left to
     * the service's own LIKE, `%` and all.
     */
    fun where(nameContains: String): String {
        val name = nameContains.trim()
        if (name.isEmpty()) return "1=1"
        return "UPPER(TechObjectName) LIKE UPPER('%${name.replace("'", "''")}%')"
    }

    /**
     * An envelope around [near], as ArcGIS wants it: `xmin,ymin,xmax,ymax` in degrees.
     *
     * A degree of latitude is about 111.32 km everywhere; a degree of longitude shrinks with the
     * cosine of the latitude, which matters this far south. The envelope is only a bounding box, so
     * the corners reach a little further than the radius - a quick filter, not a distance test.
     */
    fun envelope(near: GeoPoint, radiusKm: Double): String {
        val latitudeDelta = radiusKm / KM_PER_DEGREE
        val longitudeDelta = radiusKm / (KM_PER_DEGREE * cos(Math.toRadians(near.lat)).coerceAtLeast(MIN_COS))
        return listOf(
            near.lng - longitudeDelta,
            (near.lat - latitudeDelta).coerceAtLeast(-90.0),
            near.lng + longitudeDelta,
            (near.lat + latitudeDelta).coerceAtMost(90.0)
        ).joinToString(",") { degrees -> String.format(Locale.US, "%.7f", degrees) }
    }

    /** A map's own view as the service's envelope: `xmin,ymin,xmax,ymax`, in degrees. */
    fun envelope(bounds: DocBounds): String =
        listOf(bounds.minLng, bounds.minLat, bounds.maxLng, bounds.maxLat)
            .joinToString(",") { degrees -> String.format(Locale.US, "%.7f", degrees) }

    private const val KM_PER_DEGREE = 111.32
    private const val MIN_COS = 0.01
    private const val MAX_RECORD_COUNT = 2000
}
