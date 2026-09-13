package nz.mckenzie.sprayday.domain.gpx

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * Minimal GPX reader: pulls every track point out of a GPX 1.1 (or 1.0) file,
 * flattening track segments in document order.
 *
 * Namespace-agnostic lookups are used because GPX files in the wild appear both
 * with and without a default `xmlns`, and with various prefixes.
 */
object GpxParser {

    fun parse(xml: String): List<GeoPoint> {
        val factory = DocumentBuilderFactory.newInstance().apply {
            isNamespaceAware = true
            // GPX files are opened from the local filesystem, but never resolve
            // remote DTDs/entities regardless.
            setFeatureQuietly("http://apache.org/xml/features/disallow-doctype-decl", true)
            setFeatureQuietly("http://xml.org/sax/features/external-general-entities", false)
            setFeatureQuietly("http://xml.org/sax/features/external-parameter-entities", false)
        }

        val document = ByteArrayInputStream(xml.toByteArray(Charsets.UTF_8)).use { input ->
            factory.newDocumentBuilder().parse(input)
        }

        val points = mutableListOf<GeoPoint>()
        // Prefer a namespace-agnostic lookup, but fall back to a plain tag scan for
        // files written without a namespace declaration.
        val trackPoints = document.getElementsByTagNameNS("*", "trkpt")
            .takeIf { it.length > 0 }
            ?: document.getElementsByTagName("trkpt")
        for (i in 0 until trackPoints.length) {
            val element = trackPoints.item(i) as? Element ?: continue
            val lat = element.getAttribute("lat").toDoubleOrNull() ?: continue
            val lon = element.getAttribute("lon").toDoubleOrNull() ?: continue
            points += GeoPoint(
                lat = lat,
                lng = lon,
                altitudeM = childText(element, "ele")?.toDoubleOrNull(),
                accuracyM = childText(element, "hdop")?.toDoubleOrNull()?.let { (it * 5.0).toFloat() },
                timeMs = childText(element, "time")?.let { parseTime(it) } ?: 0L
            )
        }
        return points
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
