package nz.mckenzie.sprayday.domain.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What each basemap's facts have to be.
 *
 * These are not merely constants: every one of them is a licence term, a pointer at somebody
 * else's service, or the thing that keeps two sources' tiles apart on disk. A change here that
 * looks harmless - a suffix, a zoom, an attribution string - is the sort of change that is
 * noticed by a licence holder rather than by a compiler, so they are pinned.
 */
class BasemapTest {

    private val key = "c01abcDEF123"

    /** The one the tests have always named, so the aerial facts are pinned to their own values. */
    private val aerial = Basemap.LINZ_AERIAL

    private val osm = Basemap.OPENSTREETMAP

    @Test
    fun `aerial tile template uses the linz xyz path and api parameter`() {
        assertEquals(
            "https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp?api=$key",
            aerial.tileTemplate(key)
        )
    }

    @Test
    fun `aerial tile url is one tile of the same path`() {
        assertEquals(
            "https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/14/1017/660.webp?api=$key",
            aerial.tileUrl(key, 14, 1017, 660)
        )
    }

    @Test
    fun `topographic style url points at the hosted style json`() {
        assertTrue(
            Basemap.linzTopographicStyleUrl(key).endsWith("style/topographic.json?api=$key")
        )
    }

    @Test
    fun `the key probe asks for linz aerial imagery`() {
        assertTrue(Basemap.linzStyleUrl(key).endsWith("style/aerial.json?api=$key"))
    }

    @Test
    fun `keys are sanitised for url and json safety`() {
        assertEquals("abc123", Basemap.sanitiseKey("  abc123  "))
        assertEquals("abc123", Basemap.sanitiseKey("\"abc123\""))
        assertEquals("abc-123_XY", Basemap.sanitiseKey("abc-123_XY\n"))
        assertEquals("", Basemap.sanitiseKey("  "))
    }

    @Test
    fun `a key pasted with quotes or newlines still produces a clean url`() {
        val messy = "  \"$key\"\n"

        val url = aerial.tileUrl(messy, 14, 1017, 660)

        assertTrue(url.contains("?api=$key"))
        assertFalse(url.contains("\"$key"))
    }

    @Test
    fun `the drawn map is fetched from their host over https and needs no key`() {
        val template = osm.tileTemplate("")

        assertEquals("https://tile.openstreetmap.org/{z}/{x}/{y}.png", template)
        assertEquals("https://tile.openstreetmap.org/14/1017/660.png", osm.tileUrl("", 14, 1017, 660))
        assertFalse(
            "a key must not be smuggled into a source that needs none",
            osm.tileTemplate(key).contains(key)
        )
    }

    @Test
    fun `the drawn map carries the credit and the link their guidelines require`() {
        assertEquals("\u00a9 OpenStreetMap contributors", osm.attribution)
        assertEquals("https://www.openstreetmap.org/copyright", osm.attributionLink)
    }

    @Test
    fun `the credit is single quoted so it drops into a style document unescaped`() {
        Basemap.entries.forEach { basemap ->
            val html = basemap.attributionHtml()

            assertTrue("${basemap.id} should link", html.startsWith("<a href='"))
            assertFalse("${basemap.id} should need no json escaping", html.contains("\""))
            assertTrue(html.contains(basemap.attribution))
            assertTrue(html.contains(basemap.attributionLink))
        }
    }

    @Test
    fun `only imagery may be fetched ahead of time`() {
        assertTrue(
            "offline areas download aerial imagery, so it has to be prefetchable",
            aerial.prefetchable
        )
        assertFalse(
            "OpenStreetMap's tile policy prohibits bulk downloading and offline packs",
            osm.prefetchable
        )
    }

    @Test
    fun `each basemap names its own tile files, content type and zoom`() {
        assertEquals(".webp", aerial.tileSuffix)
        assertEquals("image/webp", aerial.contentType)
        assertEquals(22, aerial.maxZoom)

        assertEquals(".png", osm.tileSuffix)
        assertEquals("image/png", osm.contentType)
        assertEquals(19, osm.maxZoom)

        // The tile server builds paths out of the id, so two sources cannot collide unless their
        // ids do.
        assertEquals(2, Basemap.entries.map { it.id }.toSet().size)
    }

    @Test
    fun `only the imagery needs a key`() {
        assertTrue(aerial.needsKey)
        assertFalse(osm.needsKey)
    }

    @Test
    fun `a stored value reads back as the basemap it names`() {
        assertEquals(aerial, Basemap.fromStorage("linz-aerial"))
        assertEquals(osm, Basemap.fromStorage("osm"))
    }

    @Test
    fun `an unknown or missing value reads as the default rather than failing`() {
        assertEquals(aerial, Basemap.fromStorage(null))
        assertEquals(aerial, Basemap.fromStorage(""))
        assertEquals(aerial, Basemap.fromStorage("terrain"))
        assertEquals(Basemap.DEFAULT, Basemap.fromStorage("something a later build wrote"))
    }
}
