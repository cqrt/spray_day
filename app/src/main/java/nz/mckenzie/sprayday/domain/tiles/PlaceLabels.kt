package nz.mckenzie.sprayday.domain.tiles

/**
 * The place names LINZ publishes, drawn over the aerial imagery.
 *
 * **The problem this answers.** The aerial imagery is a photograph: it says what the ground looks
 * like and nothing about what any of it is called. The drawn map has names, but it is a different
 * basemap - an operator reading the photograph to decide where to spray cannot see that the paddock
 * they are looking at is beside Waipukurau.
 *
 * **Where the names come from.** LINZ publishes the same data the imagery comes from as **vector
 * tiles**, and one layer of them is a point per named place with the name in [NAME_PROPERTY]. It is
 * the same service, the same key and the same licence as the imagery, so nothing new is being trusted
 * - see https://basemaps.linz.govt.nz. Only that one layer is drawn: the tile also carries contours,
 * buildings and streets, which the app either has a better answer for or does not want on a spray map.
 *
 * **Both maps read this file.** The phone's own style and the desk's are built by different code for
 * good reasons, so the numbers a name is written with live here and both read them - the same reason
 * [nz.mckenzie.sprayday.map.TrackNames] exists. A town written one size on the phone and another on
 * the desk is the drift this file is here to stop.
 *
 * **The tiles are small** - about fifteen kilobytes where the operator works, against tens of
 * kilobytes per aerial tile - and they are kept in the same store, by the same server, as everything
 * else the map fetches. So names arrive for the ground that has been looked at, offline, exactly as
 * the imagery does over ground that has been looked at.
 */
object PlaceLabels {

    /** Stable id: it names the source in the tile URL and in the style. Never renamed. */
    const val ID = "linz-place-labels"

    /** The layer inside LINZ's vector tiles that carries the names. */
    const val SOURCE_LAYER = "place_labels"

    /**
     * The property the name itself is in.
     *
     * `label` rather than the `name` a vanilla Shortbread tile would carry: LINZ's own schema for this
     * layer lists `label`, and it was read out of a real tile rather than assumed - the reading is in
     * `build/verify/place-names.txt`.
     */
    const val NAME_PROPERTY = "label"

    /** The property that says what kind of place a point is: a city, a town, a locality, a bay. */
    const val KIND_PROPERTY = "place"

    /** How one of these tiles is named on disk and in a URL, and what the server answers with. */
    const val TILE_SUFFIX = ".pbf"
    const val CONTENT_TYPE = "application/x-protobuf"

    /**
     * The deepest zoom LINZ publishes for these tiles.
     *
     * Past it the map overzooms - it keeps drawing the deepest tile it has, scaled up - which for a
     * point with a name on it is exactly right: the name stays where the place is, and only its
     * lettering grows. See `Basemap.maxZoom` for the same idea on the imagery.
     */
    const val MAX_ZOOM = 15

    /**
     * The credit the licence requires.
     *
     * The same provider and the same licence as the imagery, so a map that already carries the imagery's
     * credit carries this one too - see `Basemap.LINZ_AERIAL.attribution`. This is the whole of it, for
     * a source that has to speak for itself in a style document; [SHORT_ATTRIBUTION] is what the map's
     * own strip adds when the names are on, because there the licence and the publisher are already
     * named by the imagery's credit.
     */
    const val ATTRIBUTION = "LINZ CC BY 4.0 \u00a9 Topographic data"

    /** What the map's credit strip adds when the names are drawn - see [ATTRIBUTION]. */
    const val SHORT_ATTRIBUTION = "\u00a9 Topographic data"

    /**
     * The place kinds drawn as one band of names: what they are, when they appear, how big they are.
     *
     * Two bands rather than one layer with a filter, because the thing that changes between them is
     * *when* they are drawn - a country view with every locality on it is a muddle - and a MapLibre
     * layer's zoom range is a layer property rather than a filter's. One layer per band is also how
     * the app draws its own places, and how LINZ's own style draws these.
     */
    data class Band(
        /** The style layer's id, which must be unique across both maps. */
        val id: String,
        /** The `place` values this band draws. */
        val kinds: List<String>,
        /** The zoom a name in this band first appears at. */
        val minZoom: Float,
        /** The letter size at [minZoom], and at the deepest zoom the tiles go to, in style pixels. */
        val sizeAtMinZoom: Float,
        val sizeAtMaxZoom: Float
    )

    /**
     * Towns and cities: the answer to "which town is this near", which is the whole question.
     *
     * Drawn from a wide view, because that is the view a town answers - the operator zoomed out to
     * get their bearings, and the name of the nearest town is what they are looking for.
     */
    val TOWNS = Band(
        id = "linz-place-towns",
        kinds = listOf("city", "town"),
        minZoom = 6f,
        sizeAtMinZoom = 11f,
        sizeAtMaxZoom = 14f
    )

    /**
     * Villages, localities and suburbs: the finer names, once the operator is close enough for them.
     *
     * Held back to a close view for the reason the app holds its own track names back: several of these
     * on one screen read as a muddle, and a locality answers a question - "where exactly is this" -
     * that is only asked at the scale of a few kilometres.
     */
    val LOCALITIES = Band(
        id = "linz-place-localities",
        kinds = listOf("village", "locality", "suburb"),
        minZoom = 10f,
        sizeAtMinZoom = 10f,
        sizeAtMaxZoom = 13f
    )

    /** Every band, in the order they are drawn - the wider answer first, the finer one over it. */
    val BANDS: List<Band> = listOf(TOWNS, LOCALITIES)

    /**
     * White letters with a dark outline, as the app writes its own names - see
     * [nz.mckenzie.sprayday.map.TrackNames.COLOUR].
     *
     * White because it is the one colour the traffic light never uses, so a place name never says
     * anything about when work is due, and because over a photograph (green paddocks, grey roads,
     * brown hills) white with a dark edge is what is readable.
     */
    const val COLOUR = "#FFFFFF"
    const val HALO_COLOUR = "#000000"

    /**
     * How thick the outline is.
     *
     * Thicker than a track's ([nz.mckenzie.sprayday.map.TrackNames.HALO_WIDTH]) because what is behind
     * it is a photograph rather than a flat map: a halo that is only just enough over a drawn map
     * leaves a name over dark bush unreadable.
     */
    const val HALO_WIDTH = 1.4f

    /**
     * The one tile [zoom], [x], [y], as LINZ serves it.
     *
     * The fetcher takes this rather than building it, so the URL is one decision in one place - the
     * same arrangement `Basemap.tileUrl` has for the imagery.
     */
    fun tileUrl(apiKey: String, zoom: Int, x: Int, y: Int): String =
        "${Basemap.LINZ_HOST}/v1/tiles/$TILESET/${Basemap.LINZ_TILE_MATRIX}/$zoom/$x/$y$TILE_SUFFIX" +
            "?api=${Basemap.sanitiseKey(apiKey)}"

    /**
     * The XYZ template for a style, given the address the app's tile server hands out.
     *
     * The server's address is taken from the basemap's own template rather than passed in a second
     * time, the way [nz.mckenzie.sprayday.map.GlyphFonts.urlTemplateFrom] takes the letters' address
     * from the tiles': one address for everything the map fetches, so the desk asks the phone for
     * names for the same reason it asks it for tiles.
     */
    fun urlTemplateFrom(tilesUrlTemplate: String): String =
        tilesUrlTemplate.substringBefore(TILES_MARK) + "/tiles/$ID/{z}/{x}/{y}$TILE_SUFFIX"

    /**
     * The XYZ template for a style that talks to LINZ itself, for the tests and for a preview that has
     * the app's own server to hand.
     */
    fun urlTemplate(apiKey: String): String =
        "${Basemap.LINZ_HOST}/v1/tiles/$TILESET/${Basemap.LINZ_TILE_MATRIX}/{z}/{x}/{y}$TILE_SUFFIX" +
            "?api=${Basemap.sanitiseKey(apiKey)}"

    /**
     * The tileset's name inside LINZ's service.
     *
     * **`topographic-v2`, and the `-v2` is not decoration**: the earlier `topographic` tileset answers
     * with a previous schema that has no `place_labels` layer at all, which was checked against the
     * live service rather than read off a page. See `build/verify/place-names.txt`. Readable by the
     * test for that reason: a plausible-looking edit here turns the feature off without turning
     * anything red.
     */
    internal const val TILESET = "topographic-v2"

    /** Where `/tiles/` sits in a tile address; everything before it is the server's own address. */
    private const val TILES_MARK = "/tiles/"
}
