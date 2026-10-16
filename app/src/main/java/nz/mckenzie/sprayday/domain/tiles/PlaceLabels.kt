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
     * The property the name itself is in, in `place_labels`.
     *
     * `label` rather than the `name` a vanilla Shortbread tile would carry: LINZ's own schema for this
     * layer lists `label`, and it was read out of a real tile rather than assumed - the reading is in
     * `build/verify/place-names.txt`. **The water lines are the other way round** - their names are in
     * `name`, which is not a guess either - which is why a band says which property its names are in
     * rather than this file assuming one: see [Band.nameProperty] and [WATER_LINES_NAME_PROPERTY].
     */
    const val NAME_PROPERTY = "label"

    /**
     * The property a name is in, in `water_lines` - which is **not** the one above.
     *
     * Found the hard way: the rivers band was written asking for `label`, drew nothing at all, and said
     * nothing about why - a missing property is a name the map simply does not have. Read out of real
     * tiles afterwards (`build/verify/places/probe-name-property.mjs`), which is where both of these
     * should have been read from in the first place.
     */
    const val WATER_LINES_NAME_PROPERTY = "name"

    /** The property that says what kind of place a point is: a city, a town, a locality, a peak, a lake. */
    const val KIND_PROPERTY = "place"

    /**
     * The second property that says what kind of thing a point is.
     *
     * LINZ files landscape under `natural` and settlements under `place`, so a band says which of the
     * two it filters on - see [Band.property]. Peaks are `natural`; everything with a name a person
     * lives in or near is `place`.
     */
    const val NATURAL_PROPERTY = "natural"

    /**
     * The third: what a place is on or in - a bay, a lagoon, a sea, a channel.
     *
     * The bays are worth knowing about in this app rather than filed away as trivia: **the sounds of
     * Fiordland are `water=bay`**, so Milford, Doubtful and Dusky all arrive with the bays rather than
     * anywhere else. Read out of real tiles - see `build/verify/southland-data.txt`.
     */
    const val WATER_PROPERTY = "water"

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
     * How sharply a letter may turn, for the one band whose name is drawn along a line.
     *
     * The engine's own default is forty-five degrees, which loses the name of a river with an ordinary
     * bend in it. The app's own track names allow as much as the engine does for the same reason - a
     * name bent a little reads better than a name that is not there - and a river bends more than a
     * track does.
     */
    const val LINE_MAX_ANGLE = 180f

    /**
     * The place kinds drawn as one layer of names: what they are, when they appear, how big they are.
     *
     * One layer per band rather than one with a filter, because the thing that changes between them is
     * *when* they are drawn - a country view with every locality on it is a muddle - and a MapLibre
     * layer's zoom range is a layer property rather than a filter's. One layer per band is also how
     * the app draws its own places, and how LINZ's own style draws these.
     */
    data class Band(
        /** The style layer's id, which must be unique across both maps. */
        val id: String,
        /**
         * The property this band's kinds live in: `place` for the places a person lives in or near,
         * `natural` for the landscape, `water` for what a place is on or in - see [KIND_PROPERTY],
         * [NATURAL_PROPERTY] and [WATER_PROPERTY]. LINZ files them under separate names rather than
         * one, so a band has to say which it means.
         */
        val property: String,
        /** The values of [property] this band draws. */
        val kinds: List<String>,
        /** The zoom a name in this band first appears at. */
        val minZoom: Float,
        /** The letter size at [minZoom], and at the deepest zoom the tiles go to, in style pixels. */
        val sizeAtMinZoom: Float,
        val sizeAtMaxZoom: Float,
        /**
         * Which layer of LINZ's vector tiles the features come from.
         *
         * [SOURCE_LAYER] - the place labels - for everything that is a named **point**. Rivers are the
         * exception in this app: LINZ carries them in [WATER_LINES_LAYER] as lines, and a line is what
         * a river's name wants to be drawn along.
         */
        val sourceLayer: String = SOURCE_LAYER,
        /**
         * How the name is placed: [Placement.POINT] at the feature, [Placement.LINE] along it.
         *
         * A point's name sits where the place is named. A **line**'s name runs along the line, which
         * for a river is the only reading that says anything: a river is long and thin, and a name at
         * one point on it would not say which way it goes or how far.
         */
        val placement: Placement = Placement.POINT,
        /**
         * Which property of the feature the name is in.
         *
         * [NAME_PROPERTY] for everything in `place_labels`, and [WATER_LINES_NAME_PROPERTY] for the
         * water lines, because LINZ does not use one word for this either. A band that asks for a
         * property its layer does not have draws nothing and says nothing - which is a silence worth
         * remembering: see [WATER_LINES_NAME_PROPERTY].
         */
        val nameProperty: String = NAME_PROPERTY
    )

    /** Where a band's name is written: at the thing, or along it. See [Band.placement]. */
    enum class Placement { POINT, LINE }

    /**
     * The one kind of thing this app draws that LINZ names on a line rather than at a point.
     *
     * See [RIVERS], and [Band.placement] for why.
     */
    const val WATER_LINES_LAYER = "water_lines"

    /**
     * Towns and cities: the answer to "which town is this near", which is the whole question.
     *
     * Drawn from a wide view, because that is the view a town answers - the operator zoomed out to
     * get their bearings, and the name of the nearest town is what they are looking for.
     */
    val TOWNS = Band(
        id = "linz-place-towns",
        property = KIND_PROPERTY,
        kinds = listOf("city", "town"),
        minZoom = 6f,
        sizeAtMinZoom = 11f,
        sizeAtMaxZoom = 14f
    )

    /**
     * Lakes: the other thing an operator finds their bearings by.
     *
     * LINZ carries a lake as a **point** in this layer as well as a shape in `water_polygons`, and the
     * point is what a name wants: it sits where the lake is named, at the size the dam or the shoreline
     * is known by. Drawn from a wide view almost as soon as towns are - a lake is a big thing, and one
     * of the few features that reads at the scale of a whole district.
     */
    val LAKES = Band(
        id = "linz-place-lakes",
        property = KIND_PROPERTY,
        kinds = listOf("lake"),
        minZoom = 7f,
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
        property = KIND_PROPERTY,
        kinds = listOf("village", "locality", "suburb"),
        minZoom = 10f,
        sizeAtMinZoom = 10f,
        sizeAtMaxZoom = 13f
    )

    /**
     * Bays - and, in this country, the sounds: Milford, Doubtful and Dusky are all `water=bay`.
     *
     * Drawn from the same wide view the lakes are, and for the same reason: a bay or a sound is a big
     * thing that reads at the scale of a district, and one of the few names worth having when the
     * operator is looking at a coastline rather than at ground.
     */
    val BAYS = Band(
        id = "linz-place-bays",
        property = WATER_PROPERTY,
        kinds = listOf("bay"),
        minZoom = 7f,
        sizeAtMinZoom = 11f,
        sizeAtMaxZoom = 13f
    )

    /**
     * Rivers and streams: the one band whose name runs **along** the thing rather than sitting on it.
     *
     * LINZ carries these in `water_lines` as lines, and a line is what a river's name wants: a river is
     * long and thin, and a name at one point on it would say neither which way it goes nor how far. So
     * this band is the app's one [Placement.LINE], and its "kinds" are the values of the `kind`
     * property in that layer - which is `river` for everything that flows, burns and creeks included.
     *
     * **Held back to zoom 12**, and this is the densest band of the lot: a Fiordland tile at z11 has
     * over a hundred named streams in it, and at z12 the operator is looking at a few square kilometres
     * of it. The map drops a name that will not fit without touching another, which is what keeps a
     * catchment from becoming a screen of text.
     */
    val RIVERS = Band(
        id = "linz-place-rivers",
        property = "kind",
        kinds = listOf("river"),
        minZoom = 12f,
        sizeAtMinZoom = 10f,
        sizeAtMaxZoom = 12f,
        sourceLayer = WATER_LINES_LAYER,
        placement = Placement.LINE,
        nameProperty = WATER_LINES_NAME_PROPERTY
    )

    /**
     * Peaks: which hill that is, once the operator is looking at the hill.
     *
     * Held back further than the rest, and further than a named summit might at first seem to deserve:
     * LINZ publishes peaks from low zooms, and a range has dozens of them, so a wide view with every
     * summit on it is a screen of names with the ground lost underneath. They are also the band no
     * spray decision is made from - they are for reading the country rather than the paddock.
     */
    val PEAKS = Band(
        id = "linz-place-peaks",
        property = NATURAL_PROPERTY,
        kinds = listOf("peak"),
        minZoom = 11f,
        sizeAtMinZoom = 10f,
        sizeAtMaxZoom = 12f
    )

    /**
     * Every band, in the order they are drawn: the widest answer first, the finest over it.
     *
     * Drawn in this order for the same reason the app draws the ground under its lines - a name that
     * belongs to something bigger is under one that belongs to something smaller, so a peak is never
     * hidden by the lake five kilometres away.
     */
    val BANDS: List<Band> = listOf(TOWNS, LAKES, BAYS, LOCALITIES, PEAKS, RIVERS)

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
