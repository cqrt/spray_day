package nz.mckenzie.sprayday.domain.track

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node

/**
 * Minimal KML reader: pulls every line a KML 2.2 (or Google Earth 1.0) file holds, as the paths the
 * file itself is in.
 *
 * KML writes a place's shape in a `<Placemark>`: a `<LineString>` for a path, the `<LinearRing>` of
 * a `<Polygon>` for ground with an edge, and - in Google's extension - a `<gx:Track>` for a GPS
 * track. Each of those carries its vertices differently:
 *
 * - `<coordinates>` is one whitespace-separated run of `lon,lat[,alt]` tuples, **longitude first**
 *   (the reverse of GPX's `lat`/`lon` attributes, and the trap this reader exists to get right).
 * - `<gx:coord>` is `lon lat [alt]`, space-separated, one per `<when>` child beside it.
 *
 * A `<Polygon>` is read as its boundary rings - a carpark comes in as the closed line around its
 * ground, which is what every other tool reads a KML polygon as, and one edit on the phone turns it
 * back into a carpark. `<Point>` is deliberately not read: a place is not a line, and a file of
 * points is not a track.
 *
 * Paths are collected in **document order**, not grouped by kind, because the caller's own rule
 * decides which path is the line and which are its side tracks from the order it meets them in.
 */
object KmlParser {

    /**
     * The file's own lines, in document order, each a path.
     *
     * A `<LineString>`, a `<LinearRing>` and a `<gx:Track>` are each one path, and one is read
     * wherever it appears - inside a `<Placemark>`, a `<MultiGeometry>`, or loose under the document.
     * A geometry element is not descended into once it has been read, so a nest of them cannot hand
     * the same vertices back twice.
     */
    fun parseSegments(xml: String): List<List<GeoPoint>> = segmentsOf(xmlDocument(xml))

    /**
     * The same paths, from a document already parsed.
     *
     * [nz.mckenzie.sprayday.domain.track.TrackInterchange] parses once and then asks the format's
     * reader for the paths, so a file is never read off disk twice to work out which format it is.
     */
    internal fun segmentsOf(document: Document): List<List<GeoPoint>> {
        val paths = mutableListOf<List<GeoPoint>>()
        visit(document) { element ->
            val name = localNameOf(element)
            when {
                name.equals("LineString", ignoreCase = true) -> paths += coordinatesOf(element)
                name.equals("LinearRing", ignoreCase = true) -> paths += coordinatesOf(element)
                name.equals("Track", ignoreCase = true) -> paths += trackOf(element)
                else -> return@visit false
            }
            true
        }
        return paths.filter { it.isNotEmpty() }
    }

    /** Every point of the file, in document order, as one line - the same flattening GPX has. */
    fun parse(xml: String): List<GeoPoint> = parseSegments(xml).flatten()

    /**
     * Walks the document, handing [onElement] every element and descending only where it says so.
     *
     * Returning true from [onElement] means "this element's vertices are read; do not look inside
     * it", which is what stops a `<LineString>`'s own `<coordinates>` being visited as another
     * geometry a second time.
     */
    private fun visit(node: Node, onElement: (Element) -> Boolean) {
        var child = node.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                if (!onElement(element)) visit(element, onElement)
            }
            child = child.nextSibling
        }
    }

    /** The `lon,lat[,alt]` run inside a LineString or LinearRing's own `<coordinates>`. */
    private fun coordinatesOf(parent: Element): List<GeoPoint> =
        elementsOf(parent, "coordinates").flatMap { tupleRun(it.textContent.orEmpty()) }

    /** A KML `<coordinates>` body: tuples separated by any whitespace, each `lon,lat[,alt]`. */
    private fun tupleRun(text: String): List<GeoPoint> =
        text.trim().split(WHITESPACE).mapNotNull { tuple ->
            val parts = tuple.split(',')
            if (parts.size < 2) return@mapNotNull null
            val lon = parts[0].trim().toDoubleOrNull() ?: return@mapNotNull null
            val lat = parts[1].trim().toDoubleOrNull() ?: return@mapNotNull null
            GeoPoint(lat = lat, lng = lon, altitudeM = parts.getOrNull(2)?.trim()?.toDoubleOrNull())
        }

    /**
     * A Google Earth `<gx:Track>`: its `<gx:coord>` points, each taking the time of the `<when>` in
     * the same position. A `<when>` with no matching `<gx:coord>` is ignored, and a coord with none
     * is a fix whose time is simply unknown - which [GeoPoint] keeps as 0 rather than inventing one.
     */
    private fun trackOf(parent: Element): List<GeoPoint> {
        val times = mutableListOf<Long>()
        val points = mutableListOf<GeoPoint>()
        var child = parent.firstChild
        while (child != null) {
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                when (localNameOf(element)) {
                    "when" -> times += parseIsoTime(element.textContent.orEmpty().trim())
                    "coord" -> coordOf(element.textContent.orEmpty())?.let { points += it }
                }
            }
            child = child.nextSibling
        }
        return points.mapIndexed { index, point -> point.copy(timeMs = times.getOrElse(index) { 0L }) }
    }

    /** One `<gx:coord>`: `lon lat [alt]`, space-separated. */
    private fun coordOf(text: String): GeoPoint? {
        val parts = text.trim().split(WHITESPACE)
        if (parts.size < 2) return null
        val lon = parts[0].toDoubleOrNull() ?: return null
        val lat = parts[1].toDoubleOrNull() ?: return null
        return GeoPoint(lat = lat, lng = lon, altitudeM = parts.getOrNull(2)?.toDoubleOrNull())
    }

    private fun localNameOf(element: Element): String =
        element.localName ?: element.nodeName.substringAfterLast(':')

    private val WHITESPACE = Regex("\\s+")
}
