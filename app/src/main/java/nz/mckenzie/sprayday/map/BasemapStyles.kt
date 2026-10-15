package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.tiles.Basemap
import nz.mckenzie.sprayday.domain.tiles.PlaceLabels

/**
 * The MapLibre style document for a basemap.
 *
 * One raster source and one raster layer, built here rather than loaded from the provider's own
 * hosted style, for three reasons that all still hold: the tile URL can be swapped for the app's
 * tile server, so a downloaded area works with no reception; the key - where a source needs one -
 * is injected at runtime; and what is in the document is exactly what the app draws, rather than
 * whatever a hosted style happens to declare today.
 *
 * The credit the licence requires goes in with it, in the source's own attribution, so the map
 * itself carries it; the screens also draw an always-visible strip, because a credit behind a
 * tap is a credit that is not visible.
 */
object BasemapStyles {

    /**
     * The style for [basemap], pointing at [tileUrlTemplate].
     *
     * [tileUrlTemplate] is either the app's own tile server or the provider's own URL - the map
     * cannot tell the difference, which is the point of the server existing.
     *
     * [glyphsUrl] is where the letters for a track's name come from, or null when there is nowhere to
     * get them from. The letters travel inside the app and are handed out by the app's own server, so a
     * style built to talk to a provider directly has none: it writes no names, which is honest rather
     * than broken, because there is nowhere to get the letters from.
     *
     * [placeNamesUrl] is where LINZ's place-name tiles come from - see [PlaceLabels] - or null when the
     * operator has not asked for them. **Both** are needed: names are drawn where there is a server to
     * fetch the tiles from and letters to write them with, so a style pointed straight at a provider
     * draws no names at all rather than drawing boxes.
     */
    fun rasterStyleJson(
        basemap: Basemap,
        tileUrlTemplate: String,
        glyphsUrl: String? = null,
        placeNamesUrl: String? = null
    ): String {
        val names = if (placeNamesUrl != null && glyphsUrl != null) placeNamesUrl else null

        return """
            {
              "version": 8,
              "name": "Spray Day - ${basemap.displayName}",
              ${glyphsUrl?.let { "\"glyphs\": \"$it\"," } ?: ""}
              "sources": {
                "${basemap.id}": {
                  "type": "raster",
                  "tiles": ["$tileUrlTemplate"],
                  "tileSize": 256,
                  "minzoom": 0,
                  "maxzoom": ${basemap.maxZoom},
                  "attribution": "${basemap.attributionHtml()}"
                }${names?.let { ",\n" + placeLabelsSource(it) }.orEmpty()}
              },
              "layers": [
                {
                  "id": "background",
                  "type": "background",
                  "paint": { "background-color": "#0B1F13" }
                },
                {
                  "id": "${basemap.id}",
                  "type": "raster",
                  "source": "${basemap.id}",
                  "minzoom": 0,
                  "maxzoom": ${basemap.maxZoom}
                }${names?.let { ",\n" + labelsLayers() }.orEmpty()}
              ]
            }
        """.trimIndent()
    }

    /**
     * The place names' own source, as a style entry.
     *
     * A vector source rather than a raster one: LINZ publishes these as tiles of features, and the
     * app draws the one layer of them that carries a name. That is also what makes them worth having
     * over a picture of somebody else's map - the name is text the map writes, so it is written in the
     * same letters, at the same weight and in the same colour on both maps, and it can be switched off
     * without switching the imagery off with it.
     */
    private fun placeLabelsSource(url: String): String = """
        "${PlaceLabels.ID}": {
          "type": "vector",
          "tiles": ["$url"],
          "minzoom": 0,
          "maxzoom": ${PlaceLabels.MAX_ZOOM},
          "attribution": "${PlaceLabels.ATTRIBUTION}"
        }
    """.trimIndent()

    /**
     * Every band of names, as style layers - see [PlaceLabels.BANDS].
     *
     * One layer per band because what changes between them is the zoom they appear at, which a
     * MapLibre layer says and a filter cannot.
     */
    private fun labelsLayers(): String =
        PlaceLabels.BANDS.joinToString(",\n") { band -> placeLabelLayer(band) }

    /**
     * One band of place names, as a style layer.
     *
     * Two things are the map's rather than the app's, and deliberately: a name that will not fit
     * without touching another is **dropped** rather than drawn on top of it (the opposite of how the
     * app treats its own track names, which are never allowed to vanish) - because a place name is
     * furniture the operator did not ask for by name, and a screen of overlapping town names is worse
     * than a town name that is not there yet. Everything else - the size, when it appears, the colours,
     * the outline - comes from [PlaceLabels], so both maps write a town the same way.
     */
    private fun placeLabelLayer(band: PlaceLabels.Band): String = """
        {
          "id": "${band.id}",
          "type": "symbol",
          "source": "${PlaceLabels.ID}",
          "source-layer": "${PlaceLabels.SOURCE_LAYER}",
          "minzoom": ${band.minZoom},
          "filter": ["in", ["get", "${PlaceLabels.KIND_PROPERTY}"], ["literal", ${
        band.kinds.joinToString(", ") { "\"$it\"" }
    }]],
          "layout": {
            "text-field": ["get", "${PlaceLabels.NAME_PROPERTY}"],
            "text-font": ["${GlyphFonts.STACK}"],
            "text-size": ["interpolate", ["linear"], ["zoom"], ${band.minZoom}, ${
        band.sizeAtMinZoom
    }, ${PlaceLabels.MAX_ZOOM}, ${band.sizeAtMaxZoom}],
            "text-allow-overlap": false,
            "text-padding": 4
          },
          "paint": {
            "text-color": "${PlaceLabels.COLOUR}",
            "text-halo-color": "${PlaceLabels.HALO_COLOUR}",
            "text-halo-width": ${PlaceLabels.HALO_WIDTH}
          }
        }
    """.trimIndent()

    /**
     * A style with no network sources at all, used while a basemap that needs a key has none -
     * so the app shows a calm placeholder instead of firing thousands of tiles that will come
     * back HTTP 400.
     *
     * OpenStreetMap never needs this: it has no key to be missing, and its tiles are fetched by
     * the app's tile server whether or not a LINZ key exists.
     */
    fun blankStyleJson(): String = """
        {
          "version": 8,
          "name": "Spray Day - no basemap key",
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#10241A" }
            }
          ]
        }
    """.trimIndent()
}
