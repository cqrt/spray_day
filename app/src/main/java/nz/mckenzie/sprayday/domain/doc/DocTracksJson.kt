package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.track.GeoJsonParser
import nz.mckenzie.sprayday.domain.track.TrackInterchange

/**
 * The DOC Tracks service's answer read as the tracks it holds.
 *
 * The service is asked for `f=geojson`, so the geometry is read by [GeoJsonParser] - the same reader
 * a GeoJSON file gets - and the rule that turns one feature's paths into a line with side tracks is
 * [TrackInterchange]'s. What is here is only what the browser needs beyond that: the service's key,
 * its kind, and a name to fall back on when a feature has none.
 *
 * A feature that is not a line - a point, or a stray single vertex - is dropped rather than failing
 * the search: a page of tracks is still a page of tracks with one odd member.
 */
object DocTracksJson {

    fun parse(text: String): List<DocTrack> = GeoJsonParser.parse(text).mapNotNull { feature ->
        val reading = when (val outcome = TrackInterchange.reading(feature.paths)) {
            is TrackInterchange.Outcome.Read -> outcome.reading
            is TrackInterchange.Outcome.Invalid -> return@mapNotNull null
        }
        val objectId = feature.properties["OBJECTID"]?.toLongOrNull() ?: 0L
        val name = feature.name?.takeIf { it.isNotBlank() }
            ?: "DOC track ${if (objectId != 0L) objectId else "unknown"}"
        DocTrack(
            objectId = objectId,
            name = name,
            kind = feature.properties["SubObjectType"]?.takeIf { it.isNotBlank() },
            reading = reading
        )
    }
}
