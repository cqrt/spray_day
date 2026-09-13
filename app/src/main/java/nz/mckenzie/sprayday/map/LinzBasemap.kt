package nz.mckenzie.sprayday.map

/**
 * Everything specific to the LINZ Basemaps tile service.
 *
 * Facts verified against LINZ's own documentation and examples:
 *  - XYZ tile template: /v1/tiles/aerial/WebMercatorQuad/{z}/{x}/{y}.webp
 *  - the key is passed as the `api` query parameter
 *  - aerial imagery is available to Web Mercator zoom 22
 *  - standard access keys are free but **expire every 90 days**
 *  - imagery is CC BY 4.0 and attribution must be visible in the app
 */
object LinzBasemap {

    const val HOST = "https://basemaps.linz.govt.nz"
    const val TILE_MATRIX = "WebMercatorQuad"

    /** Aerial imagery is published to Web Mercator zoom 22. */
    const val AERIAL_MAX_ZOOM = 22

    /** The topographic vector basemap only goes to zoom 15. */
    const val TOPOGRAPHIC_MAX_ZOOM = 15

    const val ATTRIBUTION = "LINZ CC BY 4.0 \u00a9 Imagery Basemap contributors"

    /** LINZ's own attribution page, as required by the licence. */
    const val ATTRIBUTION_LINK =
        "https://www.linz.govt.nz/products-services/data/licensing-and-using-data/attributing-linz-basemaps-data"

    /**
     * Strips anything that is not safe in a URL or a JSON string. Keys from
     * basemaps.linz.govt.nz are alphanumeric, so this only ever guards against
     * a pasted key bringing whitespace or quotes with it.
     */
    fun sanitiseKey(apiKey: String): String =
        apiKey.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }

    fun aerialTileTemplate(apiKey: String): String =
        "$HOST/v1/tiles/aerial/$TILE_MATRIX/{z}/{x}/{y}.webp?api=${sanitiseKey(apiKey)}"

    /** One specific tile, as the downloader and the fetcher need it. */
    fun aerialTileUrl(apiKey: String, zoom: Int, x: Int, y: Int): String =
        aerialTileUrlAt(HOST, apiKey, zoom, x, y)

    private fun aerialTileUrlAt(host: String, apiKey: String, zoom: Int, x: Int, y: Int): String =
        "$host/v1/tiles/aerial/$TILE_MATRIX/$zoom/$x/$y.webp?api=${sanitiseKey(apiKey)}"

    /** LINZ's hosted topographic vector style, keyed at the style URL. */
    fun topographicStyleUrl(apiKey: String): String =
        "$HOST/v1/tiles/topographic/$TILE_MATRIX/style/topographic.json?api=${sanitiseKey(apiKey)}"

    /**
     * LINZ's hosted **aerial** style, used only as the entry point for MapLibre's
     * OfflineManager, which requires a style URL rather than inline JSON.
     *
     * Verified against the live service: the aerial tile template inside this
     * document is byte-identical to [aerialTileTemplate], so tiles an offline
     * region caches are reused by our own inline style when the app is offline.
     * The hosted document also declares two unused terrain sources, which is why
     * the live map renders our leaner inline style instead.
     */
    fun hostedAerialStyleUrl(apiKey: String): String =
        "$HOST/v1/tiles/aerial/$TILE_MATRIX/style/aerial.json?api=${sanitiseKey(apiKey)}"

    /** Single-quoted href so the HTML drops straight into JSON without escaping. */
    fun attributionHtml(): String = "<a href='$ATTRIBUTION_LINK'>$ATTRIBUTION</a>"

    /**
     * A minimal MapLibre style document for the aerial basemap.
     *
     * We build our own style (rather than loading LINZ's hosted one) so the key
     * is injected at runtime and offline downloads are deterministic.
     */
    /**
     * A style with no network sources at all, used while the LINZ key is missing
     * or known to be bad - so the app shows a calm placeholder instead of firing
     * thousands of tiles that will come back HTTP 400.
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

    /**
     * A minimal style document for the aerial basemap, pointing at [tileUrlTemplate].
     *
     * We build our own style (rather than loading LINZ's hosted one) so the tile
     * URL can be swapped for the local tile server, and so offline downloads are
     * deterministic.
     */
    fun aerialStyleJsonForTemplate(tileUrlTemplate: String): String = """
        {
          "version": 8,
          "name": "Spray Day - LINZ Aerial Imagery",
          "sources": {
            "linz-aerial": {
              "type": "raster",
              "tiles": ["$tileUrlTemplate"],
              "tileSize": 256,
              "minzoom": 0,
              "maxzoom": $AERIAL_MAX_ZOOM,
              "attribution": "${attributionHtml()}"
            }
          },
          "layers": [
            {
              "id": "background",
              "type": "background",
              "paint": { "background-color": "#0B1F13" }
            },
            {
              "id": "linz-aerial",
              "type": "raster",
              "source": "linz-aerial",
              "minzoom": 0,
              "maxzoom": $AERIAL_MAX_ZOOM
            }
          ]
        }
    """.trimIndent()

    /** The style pointed straight at LINZ, used when the local server is not running. */
    fun aerialStyleJson(apiKey: String): String = aerialStyleJsonForTemplate(aerialTileTemplate(apiKey))
}
