package nz.mckenzie.sprayday.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nz.mckenzie.sprayday.domain.tiles.Basemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the page is told to draw.
 *
 * These are the claims that decide whether the desk and the phone show the same farm: the same four
 * layer ids, the same dash patterns, the same pictures, and the tiles coming from the phone rather
 * than from LINZ with a key in them. Every one of them is a copy of a decision made elsewhere, so
 * every one of them is a chance to drift - which is what this file is for.
 */
class WebStyleJsonTest {

    private val tiles = "http://192.168.1.23:8799/tiles/linz-aerial/{z}/{x}/{y}.webp?k=7f3a"
    private val assets = "http://192.168.1.23:8799/api/assets.geojson?k=7f3a"

    private val style: JsonObject =
        Json.parseToJsonElement(WebStyleJson.build(Basemap.LINZ_AERIAL, tiles, assets)).jsonObject

    private fun layerIds(): List<String> =
        style["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }

    private fun layer(id: String): JsonObject =
        style["layers"]!!.jsonArray.first { it.jsonObject["id"]!!.jsonPrimitive.content == id }.jsonObject

    private fun paint(id: String): JsonObject = layer(id)["paint"]!!.jsonObject

    @Test
    fun `the work is drawn in the app's own four layers, in the app's own order`() {
        assertEquals(
            listOf("background", Basemap.LINZ_AERIAL.id) + listOf(
                AssetLayerIds.TRACKS,
                AssetLayerIds.ROADS,
                AssetLayerIds.FENCELINES,
                AssetLayerIds.PLACES
            ),
            layerIds()
        )
    }

    @Test
    fun `the tiles and the credit come from the phone`() {
        val source = style["sources"]!!.jsonObject[Basemap.LINZ_AERIAL.id]!!.jsonObject

        assertEquals(
            listOf(tiles),
            source["tiles"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            Basemap.LINZ_AERIAL.attributionHtml(),
            source["attribution"]!!.jsonPrimitive.content
        )
        assertEquals(
            assets,
            style["sources"]!!.jsonObject[ASSETS_SOURCE]!!.jsonObject["data"]!!.jsonPrimitive.content
        )
    }

    @Test
    fun `a road is dashed exactly as the app dashes it, and a track is not dashed at all`() {
        val roads = paint(AssetLayerIds.ROADS)["line-dasharray"]!!.jsonArray
            .map { it.jsonPrimitive.content.toDouble() }
        val fencelines = paint(AssetLayerIds.FENCELINES)["line-dasharray"]!!.jsonArray
            .map { it.jsonPrimitive.content.toDouble() }

        AssetLineStyles.ROAD.zip(roads).forEach { (expected, actual) ->
            assertEquals(expected.toDouble(), actual, 1e-6)
        }
        AssetLineStyles.INFRASTRUCTURE.zip(fencelines).forEach { (expected, actual) ->
            assertEquals(expected.toDouble(), actual, 1e-6)
        }
        // Solid means no dash array at all, rather than a dash of zero.
        assertTrue(
            "a solid track must carry no dash array",
            paint(AssetLayerIds.TRACKS)["line-dasharray"] == null
        )
    }

    @Test
    fun `a line's colour is the feature's own, so a half-sprayed track is one layer in two colours`() {
        assertEquals(
            """["get","stroke"]""",
            paint(AssetLayerIds.TRACKS)["line-color"].toString()
        )
    }

    @Test
    fun `each line layer keeps its own kind apart from the others`() {
        assertEquals(
            """["all",["==",["get","kind"],"TRACK"],["==",["get","shape"],"LINE"]]""",
            layer(AssetLayerIds.TRACKS)["filter"].toString()
        )
        assertEquals(
            """["all",["==",["get","kind"],"ROAD"],["==",["get","shape"],"LINE"]]""",
            layer(AssetLayerIds.ROADS)["filter"].toString()
        )
        assertEquals(
            """["all",["==",["get","kind"],"INFRASTRUCTURE"],["==",["get","shape"],"LINE"]]""",
            layer(AssetLayerIds.FENCELINES)["filter"].toString()
        )
    }

    @Test
    fun `a place asks for the house the feature names, and falls back to the grey one`() {
        val places = layer(AssetLayerIds.PLACES)

        assertEquals("""["==",["get","shape"],"POINT"]""", places["filter"].toString())
        assertEquals(
            """["coalesce",["get","icon"],"${PlaceIcons.FALLBACK_IMAGE_NAME}"]""",
            places["layout"]!!.jsonObject["icon-image"].toString()
        )
    }

    @Test
    fun `the page is told which pictures to draw, by the names the style asks for`() {
        assertEquals(
            PlaceIcons.IMAGE_NAMES,
            style["placeIcons"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
    }

    @Test
    fun `the other basemap is a different style, with that basemap's tiles and credit`() {
        val osm = Json.parseToJsonElement(
            WebStyleJson.build(Basemap.OPENSTREETMAP, "http://192.168.1.23:8799/tiles/osm/{z}/{x}/{y}.png", assets)
        ).jsonObject

        val source = osm["sources"]!!.jsonObject[Basemap.OPENSTREETMAP.id]!!.jsonObject

        assertEquals(
            Basemap.OPENSTREETMAP.attributionHtml(),
            source["attribution"]!!.jsonPrimitive.content
        )
        assertEquals(
            listOf("background", Basemap.OPENSTREETMAP.id) + listOf(
                AssetLayerIds.TRACKS,
                AssetLayerIds.ROADS,
                AssetLayerIds.FENCELINES,
                AssetLayerIds.PLACES
            ),
            osm["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        )
    }
}
