package nz.mckenzie.sprayday.domain.track

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.w3c.dom.Document
import org.w3c.dom.Element

/**
 * Minimal GPX reader: pulls every track point out of a GPX 1.1 (or 1.0) file, either as the segments
 * the file itself is in or flattened into one line.
 *
 * Namespace-agnostic lookups are used because GPX files in the wild appear both
 * with and without a default `xmlns`, and with various prefixes.
 *
 * **A track on the ground is a line and its side tracks** (see
 * [nz.mckenzie.sprayday.domain.geo.AssetGeometry]), and GPX's own model for that is several `<trkseg>`
 * elements in one `<trk>`. So this reads the segments as paths and lets the caller decide what they
 * are: whether the later ones join the first is a question about the ground, and it is answered where
 * the file is turned into a track rather than here, where the file is only read.
 */
object GpxParser {

    /**
     * The file's own track segments, in document order, each a path.
     *
     * A file that never says where one segment ends - every `trkpt` loose under its `<trk>`, which is
     * what some tools write - is one path, which is what the app has always read those as. Empty
     * segments are dropped rather than returned: a `<trkseg>` with nothing in it is not a path.
     */
    fun parseSegments(xml: String): List<List<GeoPoint>> = segmentsOf(xmlDocument(xml))

    /**
     * The same segments, from a document already parsed.
     *
     * [nz.mckenzie.sprayday.domain.track.TrackInterchange] parses once and then asks the format's
     * reader for the paths, so a file is never read off disk twice to work out which format it is.
     */
    internal fun segmentsOf(document: Document): List<List<GeoPoint>> {
        val segments = elementsOf(document, "trkseg")
        val paths = if (segments.isEmpty()) {
            listOf(document.documentElement)
        } else {
            segments
        }
        return paths.map { element -> pointsOf(element) }.filter { it.isNotEmpty() }
    }

    /**
     * Every track point in the file, in document order, as one line.
     *
     * What a file's segments are joined into when they are not a track with side tracks - and what an
     * import has always produced for every file, which is why it survives here beside [parseSegments].
     */
    fun parse(xml: String): List<GeoPoint> = parseSegments(xml).flatten()

    /** The one `<trkpt>` conversion: what a fix is, wherever it is read from. */
    private fun pointsOf(parent: Element): List<GeoPoint> {
        val trackPoints = elementsOf(parent, "trkpt")
        return trackPoints.mapNotNull { element ->
            val lat = element.getAttribute("lat").toDoubleOrNull() ?: return@mapNotNull null
            val lon = element.getAttribute("lon").toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(
                lat = lat,
                lng = lon,
                altitudeM = childText(element, "ele")?.toDoubleOrNull(),
                accuracyM = childText(element, "hdop")?.toDoubleOrNull()?.let { (it * 5.0).toFloat() },
                timeMs = childText(element, "time")?.let { parseIsoTime(it) } ?: 0L
            )
        }
    }
}
