package nz.mckenzie.sprayday.domain.tiles

/**
 * A map to draw under the work.
 *
 * There are two kinds of thing here, and the difference matters more than the code:
 *
 *  - **Aerial imagery** (LINZ): a photograph of the ground, which is what a spray decision is
 *    made from, and which the app may *download ahead of time* into the offline store. Keys are
 *    per-source, and LINZ's expire every 90 days.
 *  - **A drawn map** (OpenStreetMap): roads, tracks, gates, streams and names, which is what the
 *    operator reads to find a way in or to say where they are. It needs no key and works the day
 *    a LINZ key has expired - but it shows nothing about what is growing, and its tiles may only
 *    be fetched as they are looked at. See [prefetchable].
 *
 * Everything specific to a source lives in this table: the tile URL, how tiles are named on
 * disk, the zoom it is published to, and the attribution its licence requires. That is on
 * purpose - the alternative is the same facts scattered across a style builder, a fetcher, a
 * settings screen and a licence notice, and drifting apart.
 */
enum class Basemap(
    /** Stable id: it names the source in the tile URL and in preferences. Never renamed. */
    val id: String,
    /** What the picker calls it. */
    val displayName: String,
    /** One line for the picker, in the operator's terms. */
    val summary: String,
    /** Whether tiles cannot be served at all without a key from the provider. */
    val needsKey: Boolean,
    /** How a tile file is named on disk and in the URL, e.g. `.webp`. */
    val tileSuffix: String,
    /** What the server answers with, e.g. `image/webp`. */
    val contentType: String,
    /** The deepest zoom the source publishes; the map overzooms beyond it. */
    val maxZoom: Int,
    /**
     * Whether the app may fetch these tiles **before** anyone looks at them.
     *
     * True for imagery, which is what offline areas are for: a block is downloaded once, on
     * purpose, and the operator drives around it with no reception. **False for OpenStreetMap,
     * and this is a licence term rather than a preference**: their tile usage policy prohibits
     * pre-emptive fetching - "bulk downloading" - and offline packs, and points anyone who wants
     * offline tiles at self-hosted ones instead. The tiles that have been looked at are still
     * cached as they are looked at, which is what the same policy requires.
     */
    val prefetchable: Boolean,
    /** The credit its licence requires, shown on every map that draws it. */
    val attribution: String,
    /** Where that credit links to, as the licence requires. */
    val attributionLink: String
) {

    LINZ_AERIAL(
        id = "linz-aerial",
        displayName = "Aerial imagery",
        summary = "LINZ aerial photography - what the ground actually looks like. Needs a LINZ " +
            "key, and can be downloaded before you go somewhere with no reception.",
        needsKey = true,
        tileSuffix = ".webp",
        contentType = "image/webp",
        maxZoom = 22,
        prefetchable = true,
        attribution = "LINZ CC BY 4.0 \u00a9 Imagery Basemap contributors",
        attributionLink = "https://www.linz.govt.nz/products-services/data/licensing-and-using-" +
            "data/attributing-linz-basemaps-data"
    ),

    OPENSTREETMAP(
        id = "osm",
        displayName = "OpenStreetMap",
        summary = "A drawn map of the same ground: roads, tracks, gates and names, and no key " +
            "needed. Shown as you browse it - it cannot be downloaded for offline use.",
        needsKey = false,
        tileSuffix = ".png",
        contentType = "image/png",
        maxZoom = 19,
        prefetchable = false,
        attribution = "\u00a9 OpenStreetMap contributors",
        attributionLink = "https://www.openstreetmap.org/copyright"
    );

    /**
     * The XYZ template to hand MapLibre, with the key in it where the source needs one.
     *
     * OpenStreetMap's URL is the one their policy names, over https: `http://` is refused, and a
     * library's default user agent is not enough - which is why their tiles are fetched by the
     * app's own tile server rather than by the map itself. See
     * [nz.mckenzie.sprayday.offline.OsmTileFetcher].
     */
    fun tileTemplate(apiKey: String): String = when (this) {
        LINZ_AERIAL ->
            "$LINZ_HOST/v1/tiles/aerial/$LINZ_TILE_MATRIX/{z}/{x}/{y}.webp?api=${sanitiseKey(apiKey)}"

        OPENSTREETMAP ->
            "$OSM_HOST/{z}/{x}/{y}.png"
    }

    /** One specific tile, as the fetchers need it. */
    fun tileUrl(apiKey: String, zoom: Int, x: Int, y: Int): String = when (this) {
        LINZ_AERIAL ->
            "$LINZ_HOST/v1/tiles/aerial/$LINZ_TILE_MATRIX/$zoom/$x/$y.webp?api=${sanitiseKey(apiKey)}"

        OPENSTREETMAP ->
            "$OSM_HOST/$zoom/$x/$y.png"
    }

    /** The credit as HTML, single-quoted so it drops into a style document without escaping. */
    fun attributionHtml(): String = "<a href='$attributionLink'>$attribution</a>"

    companion object {
        /** What the map draws until the operator chooses otherwise. */
        val DEFAULT: Basemap = LINZ_AERIAL

        const val LINZ_HOST = "https://basemaps.linz.govt.nz"
        const val LINZ_TILE_MATRIX = "WebMercatorQuad"

        /**
         * OpenStreetMap's standard tile layer.
         *
         * Over https, because their policy refuses `http://`, and their own host rather than a
         * mirror, because a mirror has its own policy.
         */
        const val OSM_HOST = "https://tile.openstreetmap.org"

        /**
         * The LINZ topographic vector basemap only goes to zoom 15.
         *
         * Named here for the day it is offered as a basemap of its own; this build does not have
         * it in the table, so nothing draws from it yet.
         */
        const val LINZ_TOPOGRAPHIC_MAX_ZOOM = 15

        /** LINZ's own attribution page, as the table has it. */
        val LINZ_ATTRIBUTION_LINK: String get() = LINZ_AERIAL.attributionLink

        /** Where OpenStreetMap's attribution must point, as the table has it. */
        val OSM_COPYRIGHT_LINK: String get() = OPENSTREETMAP.attributionLink

        /** Their recommended way for an operator to report a wrong road or a missing gate. */
        const val OSM_REPORT_LINK = "https://www.openstreetmap.org/fixthemap"

        /**
         * The basemap a stored value names, or the default.
         *
         * Anything unrecognised reads as the default rather than throwing: a preference written
         * by a later build, or a value edited by hand, must not stop the app drawing a map - and
         * imagery is the answer that always makes sense, because it is what the app is for.
         */
        fun fromStorage(value: String?): Basemap =
            entries.firstOrNull { it.id == value } ?: DEFAULT

        /**
         * Strips anything that is not safe in a URL or a JSON string. Keys from
         * basemaps.linz.govt.nz are alphanumeric, so this only ever guards against a pasted key
         * bringing whitespace or quotes with it.
         */
        fun sanitiseKey(apiKey: String): String =
            apiKey.trim().filter { it.isLetterOrDigit() || it == '-' || it == '_' }

        /**
         * LINZ's hosted **aerial** style, used only as the entry point for asking whether a key
         * works. See [nz.mckenzie.sprayday.offline.LinzKeyProbe] for why the probe is a style
         * document rather than a tile.
         */
        fun linzStyleUrl(apiKey: String): String =
            "$LINZ_HOST/v1/tiles/aerial/$LINZ_TILE_MATRIX/style/aerial.json" +
                "?api=${sanitiseKey(apiKey)}"

        /** LINZ's hosted topographic style, for a basemap this build does not offer yet. */
        fun linzTopographicStyleUrl(apiKey: String): String =
            "$LINZ_HOST/v1/tiles/topographic/$LINZ_TILE_MATRIX/style/topographic.json" +
                "?api=${sanitiseKey(apiKey)}"
    }
}
