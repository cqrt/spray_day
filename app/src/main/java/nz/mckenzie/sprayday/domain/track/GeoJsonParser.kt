package nz.mckenzie.sprayday.domain.track

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive
import kotlinx.serialization.json.contentOrNull
import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * Minimal GeoJSON reader: pulls every feature's line out of a FeatureCollection, a single Feature, or
 * a bare geometry, as the paths the file itself is in.
 *
 * GeoJSON is the one interchange format that says what its own coordinates are: RFC 7946 fixes them
 * to WGS84 and to **longitude first** (`[lon, lat, (alt)]`), the same order KML uses - so there is no
 * CRS to honour and no flip to get wrong. A `LineString` is one path, a `MultiLineString` and a
 * `Polygon`'s rings are several, and a `GeometryCollection` is whatever it nests. `Point` and
 * `MultiPoint` are deliberately not read: a place is not a line.
 *
 * A feature's name is taken from its own properties where it has one - see [NAME_KEYS] - so a file of
 * named tracks can be imported as one asset each rather than all under the file's own name. What the
 * paths then *mean* as a track is [TrackInterchange]'s rule, not this reader's.
 */
object GeoJsonParser {

    /**
     * One feature's geometry as the paths it holds, the name its properties give it, and its
     * **scalar properties** as text.
     *
     * The properties are carried for callers that need more than a track's name - the DOC Tracks
     * browser reads `SubObjectType` and `OBJECTID` from the same document - while the parser stays
     * generic: only the primitive values are kept, nested objects and arrays are ignored.
     */
    data class Feature(
        val name: String?,
        val paths: List<List<GeoPoint>>,
        val properties: Map<String, String> = emptyMap()
    )

    /**
     * The keys a feature may carry its own name under, in the order worth trying.
     *
     * A fixed list rather than a scan for anything containing "name": the file this was written for
     * carries `CharName1`…`CharName11` whose *values* are field names ("TECHNICAL_OBJECT_NAME"), and
     * a scan would hand one of those back as the track's name. `name`/`title` are the common keys;
     * `TechObjectName` is the one this app's own source data uses.
     */
    private val NAME_KEYS = listOf("name", "Name", "NAME", "title", "Title", "TechObjectName")

    /**
     * Every feature the document holds, in document order.
     *
     * The root may be a FeatureCollection, a single Feature, or a bare geometry - all three are legal
     * GeoJSON - and a bare geometry becomes one unnamed feature.
     */
    fun parse(text: String): List<Feature> {
        val root = Json.parseToJsonElement(text)
        return featuresOf(root).mapNotNull { feature -> featureFrom(feature) }
    }

    private fun featuresOf(root: JsonElement): List<JsonObject> = when (root) {
        is JsonArray -> root.mapNotNull { it as? JsonObject }
        is JsonObject -> if (typeOf(root) == "FeatureCollection") {
            (root["features"] as? JsonArray).orEmpty().mapNotNull { it as? JsonObject }
        } else {
            listOf(root)
        }
        else -> emptyList()
    }

    private fun featureFrom(feature: JsonObject): Feature? {
        val geometry = if (typeOf(feature) == "Feature") {
            feature["geometry"] as? JsonObject ?: return null
        } else {
            feature
        }
        val properties = feature["properties"] as? JsonObject
        return Feature(nameOf(properties), pathsOf(geometry), scalarsOf(properties))
    }

    /** A feature's properties as plain text, keeping only the values that are a single scalar. */
    private fun scalarsOf(properties: JsonObject?): Map<String, String> {
        properties ?: return emptyMap()
        return properties.mapNotNull { (key, value) ->
            val text = (value as? JsonPrimitive)?.contentOrNull
            if (text == null) null else key to text
        }.toMap()
    }

    /** The paths a geometry element holds, whichever shape it is written as. */
    private fun pathsOf(geometry: JsonObject): List<List<GeoPoint>> = when (typeOf(geometry)) {
        "LineString" -> listOfNotNull(path(geometry["coordinates"]))
        "MultiLineString" -> (geometry["coordinates"] as? JsonArray).orEmpty().mapNotNull { path(it) }
        // A polygon is read as its rings - the outer one and any holes - the same way a KML polygon is.
        "Polygon" -> (geometry["coordinates"] as? JsonArray).orEmpty().mapNotNull { path(it) }
        "MultiPolygon" -> (geometry["coordinates"] as? JsonArray).orEmpty().flatMap { polygon ->
            (polygon as? JsonArray).orEmpty().mapNotNull { ring -> path(ring) }
        }
        "GeometryCollection" -> (geometry["geometries"] as? JsonArray).orEmpty().flatMap { child ->
            (child as? JsonObject)?.let { pathsOf(it) }.orEmpty()
        }
        // Point and MultiPoint: a place, which this import does not make a line of.
        else -> emptyList()
    }

    /** One path from an array of positions, or null when there is nothing readable in it. */
    private fun path(coordinates: JsonElement?): List<GeoPoint>? =
        (coordinates as? JsonArray)
            ?.mapNotNull { position(it) }
            ?.takeIf { it.isNotEmpty() }

    /** One `[lon, lat, (alt)]` position. Longitude is first, as GeoJSON requires. */
    private fun position(element: JsonElement): GeoPoint? {
        val array = element as? JsonArray ?: return null
        val lon = number(array.getOrNull(0)) ?: return null
        val lat = number(array.getOrNull(1)) ?: return null
        return GeoPoint(lat = lat, lng = lon, altitudeM = number(array.getOrNull(2)))
    }

    private fun number(element: JsonElement?): Double? =
        (element as? JsonPrimitive)?.contentOrNull?.toDoubleOrNull()

    private fun nameOf(properties: JsonObject?): String? {
        properties ?: return null
        for (key in NAME_KEYS) {
            val value = (properties[key] as? JsonPrimitive)?.contentOrNull
            if (!value.isNullOrBlank()) return value.trim()
        }
        return null
    }

    private fun typeOf(element: JsonObject): String? =
        (element["type"] as? JsonPrimitive)?.contentOrNull
}
