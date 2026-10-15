package nz.mckenzie.sprayday.domain.tiles

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Where the place names are fetched from, and how they are banded.
 *
 * The URLs are pinned because they are the part a person cannot check by looking at a map: a tileset
 * named wrongly, or a key dropped from the query, draws nothing at all, and the map's answer to that
 * is a blank patch rather than a complaint. The tileset name in particular is not free to change:
 * LINZ's earlier `topographic` tileset answers happily and has no `place_labels` layer in it, so a
 * plausible-looking edit there turns the feature off without turning anything red.
 */
class PlaceLabelsTest {

    private val tiles = "http://127.0.0.1:41234/tiles/linz-aerial/{z}/{x}/{y}.webp"

    @Test
    fun `a style takes the names from the address the tiles come from`() {
        // One server for everything the map fetches, so the desk asks the phone for names for the
        // same reason it asks it for imagery - and the key never leaves the phone.
        assertEquals(
            "http://127.0.0.1:41234/tiles/linz-place-labels/{z}/{x}/{y}.pbf",
            PlaceLabels.urlTemplateFrom(tiles)
        )
    }

    @Test
    fun `a tile URL names LINZ's current tileset, the tile matrix, and the key`() {
        val url = PlaceLabels.tileUrl("c01abcDEF123", 10, 1009, 641)

        assertTrue(
            "the tile itself: $url",
            url.startsWith(
                "https://basemaps.linz.govt.nz/v1/tiles/${PlaceLabels.TILESET}/" +
                    "WebMercatorQuad/10/1009/641.pbf"
            )
        )
        assertTrue("and the key, as every other LINZ request carries it: $url", url.contains("api=c01abcDEF123"))
    }

    @Test
    fun `the tileset used is the one that actually carries the place labels`() {
        // `topographic` is a real LINZ tileset that answers 200 and has no `place_labels` layer at
        // all - it was read out of a live tile, see build/verify/place-names.txt. So the `-v2` is
        // load-bearing rather than tidiness, and this is the test that keeps it.
        assertEquals("topographic-v2", PlaceLabels.TILESET)

        val url = PlaceLabels.tileUrl("key", 1, 1, 1)
        assertTrue("the v2 tileset: $url", url.contains("/tiles/topographic-v2/"))
        assertFalse("and never the older one: $url", url.contains("/tiles/topographic/"))
    }

    @Test
    fun `a key that would break the URL is cleaned the same way the imagery's key is`() {
        assertEquals(
            PlaceLabels.tileUrl("c01abcDEF123", 1, 2, 3),
            PlaceLabels.tileUrl("  c01abcDEF123\n", 1, 2, 3)
        )
    }

    @Test
    fun `the bands are drawn in order, at rising zooms, and ask for the places LINZ publishes`() {
        val bands = PlaceLabels.BANDS
        assertEquals("the widest answer first, the finest over it", bands.sortedBy { it.minZoom }, bands)
        assertTrue(
            "every band appears at a zoom before the tiles run out",
            bands.all { it.minZoom < PlaceLabels.MAX_ZOOM }
        )
        assertTrue(
            "a name grows as the map closes in, never the other way",
            bands.all { it.sizeAtMaxZoom >= it.sizeAtMinZoom }
        )

        // What LINZ publishes, per property - read out of real tiles rather than off the schema page,
        // see build/verify/place-name-kinds.txt. LINZ files landscape under `natural` and the places a
        // person lives in or near under `place`, so a band asks one of the two for values it has.
        val published = mapOf(
            PlaceLabels.KIND_PROPERTY to
                setOf("city", "town", "village", "locality", "suburb", "lake", "island", "archipelago"),
            PlaceLabels.NATURAL_PROPERTY to setOf("peak", "bay", "cape", "peninsula")
        )
        bands.forEach { band ->
            val allowed = published[band.property]
            assertTrue(
                "a band filters on one of the two properties LINZ uses, and ${band.id} does not",
                allowed != null
            )
            assertTrue(
                "${band.id} asks for kinds LINZ publishes: ${band.kinds}",
                band.kinds.all { it in allowed!! }
            )
        }

        val asked = bands.flatMap { band -> band.kinds.map { "${band.property}=$it" } }
        assertEquals("and none of them twice", asked.size, asked.toSet().size)

        assertTrue("towns and cities are drawn from a wide view", "town" in PlaceLabels.TOWNS.kinds)
        assertTrue("lakes come almost as early", PlaceLabels.LAKES.minZoom < PlaceLabels.LOCALITIES.minZoom)
        assertTrue("localities wait for a close view", PlaceLabels.LOCALITIES.minZoom > PlaceLabels.TOWNS.minZoom)
        assertTrue(
            "and peaks wait for the closest view of the four",
            bands.all { it.minZoom <= PlaceLabels.PEAKS.minZoom }
        )
        assertTrue("peaks are the quietest of them", PlaceLabels.PEAKS.sizeAtMaxZoom < PlaceLabels.TOWNS.sizeAtMaxZoom)
        assertEquals("a peak is landscape rather than somewhere anyone lives", PlaceLabels.NATURAL_PROPERTY, PlaceLabels.PEAKS.property)
        assertEquals("and a lake is somewhere", PlaceLabels.KIND_PROPERTY, PlaceLabels.LAKES.property)
    }
}
