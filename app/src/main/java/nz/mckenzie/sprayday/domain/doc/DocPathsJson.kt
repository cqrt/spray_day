package nz.mckenzie.sprayday.domain.doc

import kotlinx.serialization.builtins.ListSerializer
import kotlinx.serialization.builtins.serializer
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * An offline track's paths as text, for the cache row.
 *
 * A cache is not the record of the farm, so its geometry travels as **one JSON column** rather than
 * as a points table: it is written once when a track is downloaded and read whole when the browser
 * wants it, and nothing ever joins to it. Each point is a `[lat, lng]` pair, and each path is a list
 * of them, so a track with side tracks is the same shape it is everywhere else in the app.
 */
object DocPathsJson {

    private val json = Json

    private val serializer = ListSerializer(ListSerializer(ListSerializer(Double.serializer())))

    fun write(paths: List<List<GeoPoint>>): String =
        json.encodeToString(serializer, paths.map { path -> path.map { listOf(it.lat, it.lng) } })

    /** The paths back, or none when the text will not read - a broken cache row is not a crash. */
    fun read(text: String): List<List<GeoPoint>> = runCatching {
        json.decodeFromString(serializer, text)
            .map { path -> path.mapNotNull { pair -> pointOf(pair) } }
            .filter { it.isNotEmpty() }
    }.getOrDefault(emptyList())

    private fun pointOf(pair: List<Double>): GeoPoint? =
        if (pair.size >= 2) GeoPoint(lat = pair[0], lng = pair[1]) else null
}
