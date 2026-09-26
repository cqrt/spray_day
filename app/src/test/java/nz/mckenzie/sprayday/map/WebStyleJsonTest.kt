package nz.mckenzie.sprayday.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.jsonArray
import kotlinx.serialization.json.jsonObject
import kotlinx.serialization.json.jsonPrimitive
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.tiles.Basemap
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the page is told to draw.
 *
 * These are the claims that decide whether the desk and the phone show the same farm: the same layer
 * ids, the same dash patterns, the same pictures, and the tiles coming from the phone rather than
 * from LINZ with a key in them. Every one of them is a copy of a decision made elsewhere, so every
 * one of them is a chance to drift - which is what this file is for.
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
    fun `the work is drawn in the app's own layers, in the app's own order`() {
        assertEquals(
            listOf("background", Basemap.LINZ_AERIAL.id) + AssetLayerIds.ALL,
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
        AssetLineStyles.FENCELINE.zip(fencelines).forEach { (expected, actual) ->
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
            """["all",["==",["get","kind"],"FENCELINE"],["==",["get","shape"],"LINE"]]""",
            layer(AssetLayerIds.FENCELINES)["filter"].toString()
        )
    }

    @Test
    fun `the line caps and joins are where a browser will accept them`() {
        val lines = listOf(AssetLayerIds.TRACKS, AssetLayerIds.ROADS, AssetLayerIds.FENCELINES)

        lines.forEach { id ->
            val layout = layer(id)["layout"]!!.jsonObject
            assertEquals("round", layout["line-cap"]!!.jsonPrimitive.content)
            assertEquals("round", layout["line-join"]!!.jsonPrimitive.content)

            // A paint property the spec does not know is not a warning to MapLibre GL JS: it refuses
            // the style, and the desk shows a blank rectangle where the farm should be. The app's own
            // map is an Android SDK, which is how this went unnoticed until a browser saw it.
            assertTrue("$id must not carry line-cap in paint", paint(id)["line-cap"] == null)
            assertTrue("$id must not carry line-join in paint", paint(id)["line-join"] == null)
        }
    }

    @Test
    fun `a place asks for the marker the feature names, and falls back to the grey ring`() {
        val places = layer(AssetLayerIds.pointOf(AssetKind.OTHER_PLACE))

        assertEquals(
            """["all",["==",["get","kind"],"OTHER_PLACE"],["==",["get","shape"],"POINT"]]""",
            places["filter"].toString()
        )
        assertEquals(
            """["coalesce",["get","icon"],"${PlaceIcons.FALLBACK_IMAGE_NAME}"]""",
            places["layout"]!!.jsonObject["icon-image"].toString()
        )
    }

    @Test
    fun `every kind of place has a marker layer of its own, filtered to that kind`() {
        PlaceIcons.KINDS.forEach { kind ->
            val filter = layer(AssetLayerIds.pointOf(kind))["filter"].toString()

            assertTrue(
                "a ${kind.name} with no layer of its own could not be hidden on its own: $filter",
                filter.contains("\"${kind.name}\"")
            )
        }
    }

    @Test
    fun `the page is told which pictures to fetch, and how big to draw them`() {
        assertEquals(
            PlaceIcons.IMAGE_NAMES,
            style["placeIcons"]!!.jsonArray.map { it.jsonPrimitive.content }
        )
        assertEquals(
            "the size the markers are drawn at, which is the phone's own marker size",
            PlaceIcons.MARKER_DP,
            style["markerDp"]!!.jsonPrimitive.content.toFloat(),
            1e-6f
        )
    }

    @Test
    fun `with nothing drawn yet, the page opens where the phone's map opens`() {
        assertEquals(
            "[${DEFAULT_CAMERA_TARGET.longitude},${DEFAULT_CAMERA_TARGET.latitude}]",
            style["center"].toString()
        )
        assertEquals(
            DEFAULT_CAMERA_ZOOM,
            style["zoom"]!!.jsonPrimitive.content.toDouble(),
            1e-6
        )
    }

    @Test
    fun `a carpark is ground - a surface under the lines, in the feature's own colour`() {
        val fill = layer(AssetLayerIds.CARPARKS_FILL)

        assertEquals("fill", fill["type"]!!.jsonPrimitive.content)
        assertEquals(
            """["all",["==",["get","kind"],"CARPARK"],["==",["get","shape"],"AREA"],["==",["geometry-type"],"Polygon"]]""",
            fill["filter"].toString()
        )
        assertEquals(
            "the carpark's own traffic light, read off the feature, so the fill and the edge cannot disagree",
            """["get","stroke"]""",
            paint(AssetLayerIds.CARPARKS_FILL)["fill-color"].toString()
        )
        assertEquals(
            "the same strength the app's own map draws it at",
            0.25,
            paint(AssetLayerIds.CARPARKS_FILL)["fill-opacity"]!!.jsonPrimitive.content.toDouble(),
            1e-9
        )

        val ids = layerIds()
        assertTrue(
            "a surface is drawn under everything that is drawn on it: $ids",
            ids.indexOf(AssetLayerIds.CARPARKS_FILL) < ids.indexOf(AssetLayerIds.TRACKS)
        )
        assertTrue(
            "and the boundary is drawn after the fill: $ids",
            ids.indexOf(AssetLayerIds.CARPARKS_FILL) < ids.indexOf(AssetLayerIds.CARPARKS)
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
            listOf("background", Basemap.OPENSTREETMAP.id) + AssetLayerIds.ALL,
            osm["layers"]!!.jsonArray.map { it.jsonObject["id"]!!.jsonPrimitive.content }
        )
    }
}
