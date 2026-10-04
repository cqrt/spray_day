package nz.mckenzie.sprayday.domain.track

import org.w3c.dom.Document
import org.w3c.dom.Element
import org.w3c.dom.Node
import java.io.ByteArrayInputStream
import java.time.Instant
import javax.xml.parsers.DocumentBuilderFactory

/**
 * The XML reading GPX and KML share.
 *
 * Both formats are XML and both are read with the same hardened parser: namespace-aware, and with
 * DOCTYPE declarations and external entities refused, because a track file is data somebody else
 * wrote and it is opened from wherever the operator got it. Keeping that setup in one place means a
 * second reader cannot quietly be written with a laxer one.
 */
internal fun xmlDocument(xml: String): Document {
    val factory = DocumentBuilderFactory.newInstance().apply {
        isNamespaceAware = true
        // Opened from the local filesystem, but never resolve remote DTDs/entities regardless.
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
 * A namespace-agnostic lookup with a plain tag scan behind it, for the files that carry no namespace
 * declaration at all - which GPX files in the wild often do not.
 */
internal fun elementsOf(parent: Node, localName: String): List<Element> {
    val found = if (parent is Document) {
        parent.getElementsByTagNameNS("*", localName)
    } else {
        (parent as? Element)?.getElementsByTagNameNS("*", localName)
    }
    val nodes = if (found != null && found.length > 0) {
        found
    } else if (parent is Document) {
        parent.getElementsByTagName(localName)
    } else {
        (parent as? Element)?.getElementsByTagName(localName)
    }
    return (0 until (nodes?.length ?: 0)).mapNotNull { index -> nodes?.item(index) as? Element }
}

/** The text of the named child element, trimmed, or null when there is no such child. */
internal fun childText(parent: Element, localName: String): String? {
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

/** An ISO-8601 instant to epoch milliseconds, or 0 when the value is not one. */
internal fun parseIsoTime(value: String): Long = runCatching {
    Instant.parse(value).toEpochMilli()
}.getOrDefault(0L)

private fun DocumentBuilderFactory.setFeatureQuietly(name: String, value: Boolean) {
    runCatching { setFeature(name, value) }
}
