package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.PlaceLabels
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
    fun `the letters for a name are asked for on the map's own address, and nowhere at all without one`() {
        val withLetters = BasemapStyles.rasterStyleJson(
            Basemap.LINZ_AERIAL,
            "http://127.0.0.1:41234/tiles/linz-aerial/{z}/{x}/{y}.webp",
            glyphsUrl = GlyphFonts.urlTemplateFrom(
                "http://127.0.0.1:41234/tiles/linz-aerial/{z}/{x}/{y}.webp"
            )
        )

        assertTrue(
            "a style that draws names must say where the letters come from",
            withLetters.contains("\"glyphs\": \"http://127.0.0.1:41234/fonts/{fontstack}/{range}.pbf\"")
        )

        val without = BasemapStyles.rasterStyleJson(
            Basemap.LINZ_AERIAL,
            "https://imagery.example.com/aerial/{z}/{x}/{y}.webp"
        )
        assertFalse(
            "a style talking to a provider has nowhere to get letters from, so it must not pretend",
            without.contains("glyphs")
        )
    }

    @Test
    fun `the place names are a vector source over the imagery, and only when asked for`() {
        val tiles = "http://127.0.0.1:41234/tiles/linz-aerial/{z}/{x}/{y}.webp"
        val letters = GlyphFonts.urlTemplateFrom(tiles)
        val names = PlaceLabels.urlTemplateFrom(tiles)

        val asked = BasemapStyles.rasterStyleJson(Basemap.LINZ_AERIAL, tiles, letters, names)

        assertTrue(
            "the names are features the map draws rather than a picture of somebody's map",
            asked.contains("\"type\": \"vector\"")
        )
        assertTrue("from the app's own server: $names", asked.contains(names))
        assertTrue(asked.contains("\"source-layer\": \"${PlaceLabels.SOURCE_LAYER}\""))
        PlaceLabels.BANDS.forEach { band ->
            assertTrue("${band.id} is drawn", asked.contains("\"id\": \"${band.id}\""))
        }
        assertTrue(
            "a name is written from the letters that travel in the app",
            asked.contains("\"text-font\": [\"${GlyphFonts.STACK}\"]")
        )
        assertTrue("with the app's own colours", asked.contains(PlaceLabels.COLOUR))
        assertTrue(asked.contains(PlaceLabels.HALO_COLOUR))
        assertTrue(
            "and over the imagery rather than under it",
            asked.indexOf(PlaceLabels.TOWNS.id) > asked.indexOf("\"id\": \"${Basemap.LINZ_AERIAL.id}\"")
        )

        val notAsked = BasemapStyles.rasterStyleJson(Basemap.LINZ_AERIAL, tiles, letters)
        assertFalse(
            "nothing is fetched for a switch nobody has thrown",
            notAsked.contains(PlaceLabels.ID)
        )

        val nowhereToGetThem = BasemapStyles.rasterStyleJson(Basemap.LINZ_AERIAL, tiles, null, names)
        assertFalse(
            "and a style with nowhere to get the names from draws none, rather than drawing boxes",
            nowhereToGetThem.contains(PlaceLabels.ID)
        )
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
