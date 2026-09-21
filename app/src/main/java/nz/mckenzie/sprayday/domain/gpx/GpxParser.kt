package nz.mckenzie.sprayday.domain.gpx

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

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
    fun parseSegments(xml: String): List<List<GeoPoint>> {
        val document = document(xml)
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
                timeMs = childText(element, "time")?.let { parseTime(it) } ?: 0L
            )
        }
    }

    private fun document(xml: String): org.w3c.dom.Document {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // GPX files are opened from the local filesystem, but never resolve
            // remote DTDs/entities regardless.
            setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        }

        return ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use { input ->
            factory.newDocumentBuilder().parse(input)
        }
    }

    /**
     * The elements with this local name inside [parent], in document order.
     *
     * A namespace-agnostic lookup with a plain tag scan behind it, for the files that carry no
     * namespace declaration at all.
     */
    private fun elementsOf(parent: org.w3c.dom.Node, localName: String): List<Element> {
        val found = if (parent is org.w3c.dom.Document) {
            parent.getElementsByTagNameNS("*", localName)
        } else {
            (parent as? Element)?.getElementsByTagNameNS("*", localName)
        }
        val nodes = if (found != null && found.length > 0) {
            found
        } else if (parent is org.w3c.dom.Document) {
            parent.getElementsByTagName(localName)
        } else {
            (parent as? Element)?.getElementsByTagName(localName)
        }
        return (0 until (nodes?.length ?: 0)).mapNotNull { index -> nodes?.item(index) as? Element }
    }

    private fun childText(parent: Element, localName: String): String? {
        for (i in 0 until parent.childNodes.length) {
            val child = parent.childNodes.item(i)
            if (child.nodeType == Node.ELEMENT_NODE) {
                val element = child as Element
                val name = element.localName ?: element.nodeName
                if (name.equals(localName, ignoreCase = true)) {
                    return element.textContent?.trim()
                }
            }
        }
        return null
    }

    private fun parseTime(value: String): Long = runCatching {
        Instant.parse(value).toEpochMilli()
    }.getOrDefault(0L)

    private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
        runCatching { setFeature(name, value) }
    }
}
