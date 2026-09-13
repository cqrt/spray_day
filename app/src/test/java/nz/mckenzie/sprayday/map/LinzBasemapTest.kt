package nz.mckenzie.sprayday.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class LinzBasemapTest {

    private val key = "c01abcDEF123"

    @Test
    fun `aerial tile template uses the linz xyz path and api parameter`() {
        val template = LinzBasemap.aerialTileTemplate(key)
        assertEquals(
            "https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=$key",
            template
        )
    }

    @Test
    fun `topographic style url points at the hosted style json`() {
        assertTrue(LinzBasemap.topographicStyleUrl(key).endsWith("style/topographic.json?api=$key"))
    }

    @Test
    fun `keys are sanitised for url and json safety`() {
        assertEquals("abc123", LinzBasemap.sanitiseKey("  abc123  "))
        assertEquals("abc123", LinzBasemap.sanitiseKey("\"abc123\""))
        assertEquals("abc-123_XY", LinzBasemap.sanitiseKey("abc-123_XY\n"))
        assertEquals("", LinzBasemap.sanitiseKey("  "))
    }

    @Test
    fun `style json is a version 8 document with the raster source`() {
        val json = LinzBasemap.aerialStyleJson(key)

        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(json.contains("\"version\": 8"))
        assertTrue(json.contains("\"type\": \"raster\""))
        assertTrue(json.contains(LinzBasemap.aerialTileTemplate(key)))
        assertTrue(json.contains("\"maxzoom\": 22"))
        assertTrue(json.contains("\"id\": \"linz-aerial\""))
    }

    @Test
    fun `style json carries the required attribution`() {
        val json = LinzBasemap.aerialStyleJson(key)

        assertTrue(json.contains("LINZ CC BY 4.0"))
        assertTrue(json.contains("Imagery Basemap contributors"))
        assertTrue(json.contains(LinzBasemap.ATTRIBUTION_LINK))
    }

    @Test
    fun `attribution html uses single quotes so it needs no json escaping`() {
        val html = LinzBasemap.attributionHtml()
        assertTrue(html.startsWith("<a href='"))
        assertFalse(html.contains("\""))
    }

    @Test
    fun `a key pasted with quotes or newlines still produces a clean style`() {
        val messy = "  \"${key}\"\n"

        val json = LinzBasemap.aerialStyleJson(messy)

        assertTrue(json.contains("?api=$key"))
        assertFalse(json.contains("\"$key"))
    }

    @Test
    fun `blank key still produces a structurally valid style`() {
        val json = LinzBasemap.aerialStyleJson("")

        assertEquals(1, Regex("\\{\"version\"|\"version\": 8").findAll(json).count())
        assertTrue(json.contains("?api="))
        assertTrue(json.contains("\"layers\""))
    }
}
