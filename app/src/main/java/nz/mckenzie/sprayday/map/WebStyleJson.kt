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
 * The layer set is the app's own, in the app's own order: the ground's surface first, because
 * everything else is drawn on top of it, then what you spray along, what you drive along, the rest of
 * the infrastructure, the ground's own boundary, and the places on top.
 */
object WebStyleJson {

    /** The width the app draws its own lines at, so the two maps agree about a line's weight. */
    private const val LINE_WIDTH = 5

    /** Just short of opaque, as the app's own map has it, so the imagery reads underneath. */
    private const val LINE_OPACITY = 0.9

    /**
     * How much of a carpark's own colour its ground carries, as the app's own map has it.
     *
     * The two maps drawing the same carpark differently would be the drift this file exists to stop,
     * so the number is here for the same reason the dash patterns are.
     */
    private const val GROUND_FILL_OPACITY = 0.25

    /** The near-black green the app shows before any tile arrives. */
    private const val BACKGROUND = "#0B1F13"

    /**
     * The marker pictures the page must have before it draws the work.
     *
     * The phone decides which pictures exist, what they are called, and what they look like: it
     * renders each one with the same code the app's own map and list use, and serves them at
     * `/api/markers/<name>.png`. The page fetches the names in this list - see `app.js` - so a bench
     * seat on the desk is the bench seat on the phone rather than a second drawing of one.
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
        // Where the phone's own map opens, so a page with nothing drawn on it yet opens there too
        // rather than on the Atlantic: the app's default camera, from the app's own constant.
        put("center", buildJsonArray {
            add(DEFAULT_CAMERA_TARGET.longitude)
            add(DEFAULT_CAMERA_TARGET.latitude)
        })
        put("zoom", DEFAULT_CAMERA_ZOOM)
        // A root key MapLibre does not know is ignored by it and read by the page.
        put("placeIcons", buildJsonArray { placeIcons.forEach { add(it) } })
        // How big the page should draw them, in its own pixels times its own pixel ratio: the phone
        // draws a marker at the size it is told, so the page has to say. Without it the page would
        // have to keep its own copy of a size the phone decides.
        put("markerDp", PlaceIcons.MARKER_DP)
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
        // The ground's fill, under everything the phone draws on it - the same arrangement, and the
        // same reason, as the app's own map.
        add(groundFillLayer())
        lineLayers().forEach { add(it) }
        PlaceIcons.KINDS.forEach { kind -> add(placeLayer(kind)) }
    }

    /**
     * The ground inside a carpark's boundary.
     *
     * A boundary on its own reads as a fence, which is the report this answers: ground is a surface.
     * The colour is the feature's own - the carpark's traffic light - so the fill and the edge cannot
     * disagree, and it is the same quarter strength the app draws it at.
     *
     * **Ground, and only ground.** A fill layer fills polygons: handed a line it fills whatever pieces
     * the tile boundaries left of it, which is a patchwork rather than a surface and was how the ground
     * used to come out. The ring is written as a Polygon for exactly this reason (`AssetGeoJson`), and
     * the filter says so as well, because a part-walked carpark's own halves *are* lines - they are
     * drawn by the boundary layer, over this.
     */
    private fun groundFillLayer(): JsonObject = buildJsonObject {
        put("id", AssetLayerIds.CARPARKS_FILL)
        put("type", "fill")
        put("source", ASSETS_SOURCE)
        put(
            "filter",
            buildJsonArray {
                add("all")
                add(dataIs("kind", AssetKind.CARPARK.name))
                add(dataIs("shape", AssetShape.AREA.name))
                add(geometryIs("Polygon"))
            }
        )
        put(
            "paint",
            buildJsonObject {
                put("fill-color", dataProperty("stroke"))
                put("fill-opacity", GROUND_FILL_OPACITY)
            }
        )
    }

    /**
     * One line layer per kind: a dash pattern is a constant in MapLibre rather than something a
     * feature can carry, so the kind picks the layer and the filter keeps them apart - the same
     * arrangement, and the same ids, as the map the operator holds in their hand.
     */
    private fun lineLayers(): List<JsonObject> = listOf(
        Triple(AssetLayerIds.TRACKS, AssetKind.TRACK, AssetShape.LINE),
        Triple(AssetLayerIds.ROADS, AssetKind.ROAD, AssetShape.LINE),
        Triple(AssetLayerIds.FENCELINES, AssetKind.FENCELINE, AssetShape.LINE),
        // Ground with an edge is drawn by a line layer too - its boundary is a line round the ground -
        // and its filter asks for `AREA`, which is what tells a carpark's edge from a track's.
        Triple(AssetLayerIds.CARPARKS, AssetKind.CARPARK, AssetShape.AREA)
    ).map { (layerId, kind, shape) ->
        buildJsonObject {
            put("id", layerId)
            put("type", "line")
            put("source", ASSETS_SOURCE)
            put("filter", lineFilter(kind, shape))
            put(
                "layout",
                buildJsonObject {
                    // In `layout`, not `paint`. The two look interchangeable and are not: MapLibre GL
                    // JS throws a whole style away over a property in the wrong place, and throws it
                    // away quietly - which is a blank desk with no line on it and nothing to read.
                    put("line-cap", "round")
                    put("line-join", "round")
                }
            )
            put(
                "paint",
                buildJsonObject {
                    // The colour is the feature's own, so a track half sprayed is two colours in
                    // one layer rather than two layers to keep in step.
                    put("line-color", dataProperty("stroke"))
                    put("line-width", LINE_WIDTH)
                    put("line-opacity", LINE_OPACITY)
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
     * The places: one marker layer per kind, named by the feature.
     *
     * One layer per kind so that a kind can be hidden on its own - the app's own map does the same,
     * and the two must agree about which layer an operator's switch hides. The feature says which
     * marker it wants, exactly as it does on the phone, and a kind or colour nothing was drawn for
     * gets the grey ring: a place drawn in the wrong colour is a wrong answer and a place not drawn
     * at all is a missing one, which is worse.
     */
    private fun placeLayer(kind: AssetKind): JsonObject = buildJsonObject {
        put("id", AssetLayerIds.pointOf(kind))
        put("type", "symbol")
        put("source", ASSETS_SOURCE)
        put("filter", pointFilter(kind))
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

    private fun lineFilter(kind: AssetKind, shape: AssetShape = AssetShape.LINE): JsonArray = buildJsonArray {
        add("all")
        add(dataIs("kind", kind.name))
        add(dataIs("shape", shape.name))
    }

    private fun pointFilter(kind: AssetKind): JsonArray = buildJsonArray {
        add("all")
        add(dataIs("kind", kind.name))
        add(dataIs("shape", AssetShape.POINT.name))
    }

    /**
     * `["==", ["geometry-type"], value]` - a test on the shape the map has drawn a feature as.
     *
     * The layer's own test rather than the feature's `shape` property, because the two are not always
     * the same thing: a part-walked carpark is a ring in its `shape` and lines on the map, and a fill
     * layer paints whatever geometry it is handed.
     */
    private fun geometryIs(geometry: String): JsonArray = buildJsonArray {
        add("==")
        add(buildJsonArray { add("geometry-type") })
        add(geometry)
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
