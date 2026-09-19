package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.tiles.Basemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the style document must contain for the map to draw a basemap at all, and for its licence
 * to be satisfied: the tiles, the zoom the source publishes to, and the credit.
 */
class BasemapStylesTest {

    private val key = "c01abcDEF123"

    @Test
    fun `aerial style is a version 8 document with the raster source`() {
        val json = BasemapStyles.rasterStyleJson(
            Basemap.LINZ_AERIAL,
            Basemap.LINZ_AERIAL.tileTemplate(key)
        )

        assertTrue(json.trimStart().startsWith("{"))
        assertTrue(json.contains("\"version\": 8"))
        assertTrue(json.contains("\"type\": \"raster\""))
        assertTrue(json.contains(Basemap.LINZ_AERIAL.tileTemplate(key)))
        assertTrue(json.contains("\"maxzoom\": 22"))
        assertTrue(json.contains("\"id\": \"linz-aerial\""))
    }

    @Test
    fun `aerial style carries the required attribution`() {
        val json = BasemapStyles.rasterStyleJson(
            Basemap.LINZ_AERIAL,
            Basemap.LINZ_AERIAL.tileTemplate(key)
        )

        assertTrue(json.contains("LINZ CC BY 4.0"))
        assertTrue(json.contains("Imagery Basemap contributors"))
        assertTrue(json.contains(Basemap.LINZ_ATTRIBUTION_LINK))
    }

    @Test
    fun `the drawn map's style points at the tile server it was given, not at openstreetmap`() {
        val template = "http://127.0.0.1:41234/tiles/osm/{z}/{x}/{y}.png"

        val json = BasemapStyles.rasterStyleJson(Basemap.OPENSTREETMAP, template)

        assertTrue(json.contains(template))
        assertFalse(
            "the map itself must not be pointed at their service: the app's fetcher is what " +
                "sends a user agent and caches what is looked at",
            json.contains(Basemap.OSM_HOST)
        )
        assertTrue(json.contains("\"maxzoom\": 19"))
        assertTrue(json.contains("\"id\": \"osm\""))
        assertTrue(json.contains(Basemap.OPENSTREETMAP.attribution))
        assertTrue(json.contains(Basemap.OSM_COPYRIGHT_LINK))
    }

    @Test
    fun `a style with no key to be had has no sources to fetch`() {
        val json = BasemapStyles.blankStyleJson()

        assertEquals(1, Regex("\\{\"version\"|\"version\": 8").findAll(json).count())
        assertTrue(json.contains("\"layers\""))
        assertFalse("a blank style must not ask anybody for a tile", json.contains("\"tiles\""))
        assertFalse(json.contains("maxzoom"))
    }

    @Test
    fun `every basemap builds a style with its own zoom and credit`() {
        Basemap.entries.forEach { basemap ->
            val json = BasemapStyles.rasterStyleJson(basemap, basemap.tileTemplate(key))

            assertTrue(json.contains("\"maxzoom\": ${basemap.maxZoom}"))
            assertTrue(json.contains(basemap.attribution))
            assertTrue(json.contains("\"id\": \"${basemap.id}\""))
            assertTrue(json.contains("\"source\": \"${basemap.id}\""))
        }
    }
}
