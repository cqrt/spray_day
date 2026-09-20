package nz.mckenzie.sprayday.map

import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.add
import kotlinx.serialization.json.buildJsonArray
import kotlinx.serialization.json.buildJsonObject
import kotlinx.serialization.json.put
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.tiles.Basemap

/**
 * The style the editor's page loads, built from the same tables the app's own map is built from.
 *
 * The alternative - a `style.json` sitting in `assets/web` with the layer ids, the dash patterns
 * and the due colours written out a second time - is a copy of four decisions that drifts the first
 * time one of them changes, and the drift shows up as a desk that draws a road solid while the
 * phone draws it dashed. So the page is handed what the phone already knows: [AssetLayerIds] for
 * the layer ids (the very ones the map's own switches hide), [AssetLineStyles] for the dashes,
 * [PlaceIcons] for the houses. The only thing left for the page to decide is the pixels.
 *
 * The layer set is the app's own four, in the app's own order: what you spray along, what you drive
 * along, the rest of the infrastructure, then the places on top.
 */
object WebStyleJson {

    /** The width the app draws its own lines at, so the two maps agree about a line's weight. */
    private const val LINE_WIDTH = 5

    /** Just short of opaque, as the app's own map has it, so the imagery reads underneath. */
    private const val LINE_OPACITY = 0.9

    /** The near-black green the app shows before any tile arrives. */
    private const val BACKGROUND = "#0B1F13"

    /**
     * The pictures the page must draw before it draws the work.
     *
     * The phone decides which pictures exist and what they are called; the page paints them, by the
     * names the style's own features ask for. A canvas drawing of the house is a few lines of the
     * page, where five bitmaps would be five bitmaps to keep in step with the marker.
     */
    val placeIcons: List<String> = PlaceIcons.IMAGE_NAMES

    /**
     * The style for [basemap], with its tiles coming from [tileUrlTemplate] - which is this phone
     * on the Wi-Fi, so the page never sees the LINZ key and a downloaded area is drawn from the
     * same store the app draws it from. [assetsUrl] is where the page reads the work itself.
     */
    fun build(basemap: Basemap, tileUrlTemplate: String, assetsUrl: String): String = buildJsonObject {
        put("version", 8)
        put("name", "Spray Day - the work")
        // A root key MapLibre does not know is ignored by it and read by the page.
        put("placeIcons", buildJsonArray { placeIcons.forEach { add(it) } })
        put("sources", sources(basemap, tileUrlTemplate, assetsUrl))
        put("layers", layers(basemap))
    }.toString()

    private fun sources(basemap: Basemap, tileUrlTemplate: String, assetsUrl: String) = buildJsonObject {
        put(
            basemap.id,
            buildJsonObject {
                put("type", "raster")
                put("tiles", buildJsonArray { add(tileUrlTemplate) })
                put("tileSize", 256)
                put("minzoom", 0)
                put("maxzoom", basemap.maxZoom)
                // The credit the licence requires, in the source's own attribution, so the map
                // itself carries it - the same as the app's own map.
                put("attribution", basemap.attributionHtml())
            }
        )
        put(
            ASSETS_SOURCE,
            buildJsonObject {
                put("type", "geojson")
                put("data", assetsUrl)
            }
        )
    }

    private fun layers(basemap: Basemap) = buildJsonArray {
        add(
            buildJsonObject {
                put("id", "background")
                put("type", "background")
                put("paint", buildJsonObject { put("background-color", BACKGROUND) })
            }
        )
        add(
            buildJsonObject {
                put("id", basemap.id)
                put("type", "raster")
                put("source", basemap.id)
                put("minzoom", 0)
                put("maxzoom", basemap.maxZoom)
            }
        )
        lineLayers().forEach { add(it) }
        add(placeLayer())
    }

    /**
     * One line layer per kind: a dash pattern is a constant in MapLibre rather than something a
     * feature can carry, so the kind picks the layer and the filter keeps them apart - the same
     * arrangement, and the same ids, as the map the operator holds in their hand.
     */
    private fun lineLayers(): List<JsonObject> = listOf(
        AssetLayerIds.TRACKS to AssetKind.TRACK,
        AssetLayerIds.ROADS to AssetKind.ROAD,
        AssetLayerIds.FENCELINES to AssetKind.INFRASTRUCTURE
    ).map { (layerId, kind) ->
        buildJsonObject {
            put("id", layerId)
            put("type", "line")
            put("source", ASSETS_SOURCE)
            put("filter", lineFilter(kind))
            put(
                "paint",
                buildJsonObject {
                    // The colour is the feature's own, so a track half sprayed is two colours in
                    // one layer rather than two layers to keep in step.
                    put("line-color", dataProperty("stroke"))
                    put("line-width", LINE_WIDTH)
                    put("line-opacity", LINE_OPACITY)
                    put("line-cap", "round")
                    put("line-join", "round")
                    AssetLineStyles.forKind(kind)?.let { dash ->
                        // Multiples of the line width, which is what a dasharray is: the same
                        // numbers the app hands its own map, so a dash reads at the same spacing.
                        put("line-dasharray", buildJsonArray { dash.forEach { add(it) } })
                    }
                }
            )
        }
    }

    /**
     * The places: a house each, named by the feature.
     *
     * The feature says which house it wants, exactly as it does on the app's own map, and a colour
     * nothing was drawn for gets the grey one - a place drawn in the wrong colour is a wrong answer
     * and a place not drawn at all is a missing one, which is worse.
     */
    private fun placeLayer(): JsonObject = buildJsonObject {
        put("id", AssetLayerIds.PLACES)
        put("type", "symbol")
        put("source", ASSETS_SOURCE)
        put("filter", dataIs("shape", AssetShape.POINT.name))
        put(
            "layout",
            buildJsonObject {
                put(
                    "icon-image",
                    buildJsonArray {
                        add("coalesce")
                        add(dataProperty(AssetGeoJson.ICON_PROPERTY))
                        add(PlaceIcons.FALLBACK_IMAGE_NAME)
                    }
                )
                put("icon-anchor", "center")
                // Every place is drawn, wherever it is: a dot never hid from another dot, and a spot
                // that vanishes because a second one is near it reads as a spot that has been deleted.
                put("icon-allow-overlap", true)
                put("icon-ignore-placement", true)
            }
        )
    }

    private fun lineFilter(kind: AssetKind): JsonArray = buildJsonArray {
        add("all")
        add(dataIs("kind", kind.name))
        add(dataIs("shape", AssetShape.LINE.name))
    }

    /** `["==", ["get", name], value]` - a test the map layer makes on every feature it draws. */
    private fun dataIs(name: String, value: String): JsonArray = buildJsonArray {
        add("==")
        add(dataProperty(name))
        add(value)
    }

    /** `["get", name]` - a property of the feature being drawn. */
    private fun dataProperty(name: String): JsonArray = buildJsonArray {
        add("get")
        add(name)
    }
}
