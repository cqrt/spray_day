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

        // What LINZ publishes, per layer and property - read out of real tiles rather than off the
        // schema page, see build/verify/place-name-kinds.txt and build/verify/southland-data.txt.
        // LINZ files landscape under `natural` and the places a person lives in or near under `place`,
        // what a place is on or in under `water`, and everything that flows under `kind` on the
        // water lines - so a band asks one of those for values that layer really has.
        val published = mapOf(
            PlaceLabels.SOURCE_LAYER to mapOf(
                PlaceLabels.KIND_PROPERTY to
                    setOf("city", "town", "village", "locality", "suburb", "lake", "island", "archipelago"),
                PlaceLabels.NATURAL_PROPERTY to setOf("peak", "bay", "cape", "peninsula"),
                PlaceLabels.WATER_PROPERTY to setOf("bay", "lagoon", "sea", "seachannel")
            ),
            PlaceLabels.WATER_LINES_LAYER to mapOf("kind" to setOf("river"))
        )
        bands.forEach { band ->
            val properties = published[band.sourceLayer]
            assertTrue(
                "${band.id} comes from a layer this app knows the names in: ${band.sourceLayer}",
                properties != null
            )
            val allowed = properties!![band.property]
            assertTrue(
                "${band.id} filters on a property that layer has: ${band.property}",
                allowed != null
            )
            assertTrue(
                "${band.id} asks for kinds LINZ publishes there: ${band.kinds}",
                band.kinds.all { it in allowed!! }
            )
        }

        val asked = bands.flatMap { band ->
            band.kinds.map { "${band.sourceLayer}.${band.property}=$it" }
        }
        assertEquals("and none of them twice", asked.size, asked.toSet().size)

        assertTrue("towns and cities are drawn from a wide view", "town" in PlaceLabels.TOWNS.kinds)
        assertTrue("lakes come almost as early", PlaceLabels.LAKES.minZoom < PlaceLabels.LOCALITIES.minZoom)
        assertTrue("bays with them", PlaceLabels.BAYS.minZoom == PlaceLabels.LAKES.minZoom)
        assertTrue("localities wait for a close view", PlaceLabels.LOCALITIES.minZoom > PlaceLabels.TOWNS.minZoom)
        assertTrue(
            "peaks wait for a closer view than the water does",
            PlaceLabels.PEAKS.minZoom > PlaceLabels.LAKES.minZoom
        )
        assertTrue(
            "and rivers are the last of the lot, because they are the densest",
            bands.all { it.minZoom <= PlaceLabels.RIVERS.minZoom }
        )
        assertTrue("peaks are the quietest of them", PlaceLabels.PEAKS.sizeAtMaxZoom < PlaceLabels.TOWNS.sizeAtMaxZoom)

        assertEquals("a peak is landscape rather than somewhere anyone lives", PlaceLabels.NATURAL_PROPERTY, PlaceLabels.PEAKS.property)
        assertEquals("and a lake is somewhere", PlaceLabels.KIND_PROPERTY, PlaceLabels.LAKES.property)
        assertEquals("a bay is what a place is on", PlaceLabels.WATER_PROPERTY, PlaceLabels.BAYS.property)

        // The one band that is not a label on a point: a river is a line, and its name runs along it.
        assertEquals(
            "rivers come from the water lines rather than the place labels",
            PlaceLabels.WATER_LINES_LAYER,
            PlaceLabels.RIVERS.sourceLayer
        )
        assertEquals("and their name is placed along the line", PlaceLabels.Placement.LINE, PlaceLabels.RIVERS.placement)
        assertEquals(
            "and it is in `name`, which is not the property the place labels use - the mistake that " +
                "drew no rivers at all, see build/verify/places/probe-name-property.mjs",
            PlaceLabels.WATER_LINES_NAME_PROPERTY,
            PlaceLabels.RIVERS.nameProperty
        )
        assertTrue(
            "every other band takes its name from the place labels' own property",
            bands.filter { it.id != PlaceLabels.RIVERS.id }
                .all { it.nameProperty == PlaceLabels.NAME_PROPERTY }
        )
        assertEquals(
            "every other band is a name at a point",
            listOf(PlaceLabels.RIVERS.id),
            bands.filter { it.placement == PlaceLabels.Placement.LINE }.map { it.id }
        )
    }
}
