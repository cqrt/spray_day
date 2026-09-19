package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.tiles.Basemap

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
     */
    fun rasterStyleJson(basemap: Basemap, tileUrlTemplate: String): String = """
        {
          "version": 8,
          "name": "Spray Day - ${basemap.displayName}",
          "sources": {
            "${basemap.id}": {
              "type": "raster",
              "tiles": ["$tileUrlTemplate"],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": ${basemap.maxZoom},
              "attribution": "${basemap.attributionHtml()}"
            }
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
            }
          ]
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
