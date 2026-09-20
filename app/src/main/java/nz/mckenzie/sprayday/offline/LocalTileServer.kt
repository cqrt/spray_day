package nz.mckenzie.sprayday.offline

/**
 * A tiny HTTP tile server on loopback, so the map has **one** tile path whether or not there is
 * a network - and, since there is more than one basemap, so that each source's tiles are served
 * the way that source's licence allows.
 *
 * The map requests `http://127.0.0.1:<port>/tiles/<source>/{z}/{x}/{y}<suffix>`:
 *
 *  - a tile in that source's store is served straight from disk, so downloaded areas work with no
 *    reception, and anything browsed online is kept;
 *  - a tile that is not held is fetched from the source and stored on the way through;
 *  - with no upstream (no key, or no network) a miss is a plain 404 and the map shows its
 *    background for that tile.
 *
 * The source in the path is what keeps the aerial imagery and the drawn map apart on disk, in
 * caching and in their licences. OpenStreetMap's tiles are cached only because they were looked
 * at - the app must never fetch them ahead of time, which is why nothing but the map itself ever
 * asks this server for them.
 *
 * Bound to loopback only, so nothing is exposed to the network - which is also why it built its own
 * socket, and why the editor's server, which is deliberately *not* loopback, was given a separate
 * one rather than a second binding here. The socket, the accept loop, the parsing and the response
 * writing now live in [HttpServer], shared by both; what stays in this file is the part that knows
 * what a tile is. This replaces MapLibre's own offline storage for imagery, whose hosted-style
 * download we measured pulling ~10x the tiles actually needed.
 */
class LocalTileServer(
    /** Every source this server can serve; the path names one of them. */
    private val sources: List<TileSource>,
    host: String = LOOPBACK
) {

    private val http = HttpServer(
        host = host,
        routes = listOf(
            statusRoute(),
            // A tile path is claimed by its prefix and judged inside the route rather than matched
            // by the tile pattern here: a path that begins like a tile but is not one has always
            // been answered with the same 404 as a path that is nothing at all, so claiming it
            // earlier would say the same thing twice.
            tileRoute(sources)
        )
    )

    /** The port it bound, or 0 before it starts. */
    val port: Int get() = http.port

    val isRunning: Boolean get() = http.isRunning

    /** Binds to an ephemeral loopback port. Returns the port. */
    fun start(): Int = http.start()

    fun stop() = http.stop()

    /**
     * The XYZ template to put in a style for [sourceId], e.g.
     * `http://127.0.0.1:41234/tiles/osm/{z}/{x}/{y}.png`.
     */
    fun tileUrlTemplate(sourceId: String): String {
        val source = sourceFor(sourceId) ?: error("no tile source called \"$sourceId\"")
        return "http://${http.authority}/tiles/$sourceId/{z}/{x}/{y}${source.suffix}"
    }

    private fun sourceFor(id: String): TileSource? = sources.firstOrNull { it.id == id }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val STATUS_PATH = "/status"

        /** Every tile path starts here; [parseTilePath] is what says whether one really is a tile. */
        private const val TILES_PREFIX = "/tiles/"

        /** `/tiles/{source}/{z}/{x}/{y}<suffix>` - the suffix is checked against the source. */
        private val TILE_PATH = Regex(
            "^/tiles/([a-z0-9][a-z0-9-]{0,30})/(\\d{1,2})/(\\d{1,7})/(\\d{1,7})(\\.[a-z0-9]{2,5})$"
        )

        /** The route that says the server is alive, unchanged since the day it was the only one. */
        fun statusRoute(): HttpRoute =
            HttpRoute(claims = { it.target == STATUS_PATH }, handler = { HttpResponse.text(200, "ok") })

        /**
         * The route that answers tiles, so a second server can serve the same tiles from the same
         * stores rather than growing a copy of this judgement.
         *
         * What a tile path is, which source it names, what a miss answers and what is stored on the
         * way through are one decision, and the editor's server is only a different door into the
         * same room: the tiles a laptop draws are the tiles already downloaded for the tractor, and
         * anything browsed on the desk is in the offline store for the next trip.
         *
         * The whole *target* is judged, query and all, exactly as it always has been. The editor's
         * server hands this route the path on its own, because its URLs carry the token in the
         * query - and by the time it does that, the token has been checked.
         */
        fun tileRoute(sources: List<TileSource>): HttpRoute = HttpRoute(
            claims = { it.path.startsWith(TILES_PREFIX) },
            handler = { request -> answerTile(sources, request) }
        )

        private suspend fun answerTile(sources: List<TileSource>, request: HttpRequest): HttpResponse {
            val parsed = parseTilePath(request.target) ?: return HttpServer.NOT_FOUND
            val source = sources.firstOrNull { it.id == parsed.source }
            if (source == null || parsed.suffix != source.suffix) return HttpServer.NOT_FOUND
            val tile = parsed.tile

            source.store.read(tile.zoom, tile.x, tile.y)?.let { bytes ->
                return HttpResponse.bytes(200, source.contentType, bytes)
            }

            return when (val fetched = source.upstream()?.fetch(tile.zoom, tile.x, tile.y)) {
                is TileFetcher.Result.Tile -> {
                    // Storing on the way through is what turns browsing into an offline pack for
                    // imagery, and what keeps a browsed map from being downloaded twice.
                    source.store.write(tile.zoom, tile.x, tile.y, fetched.bytes)
                    HttpResponse.bytes(200, source.contentType, fetched.bytes)
                }

                TileFetcher.Result.NotFound, null -> HttpResponse.text(404, "no imagery")
                is TileFetcher.Result.Failed -> HttpResponse.text(502, "upstream failed")
            }
        }

        /**
         * `/tiles/{source}/{z}/{x}/{y}<suffix>` to a request, or null if it is not a tile path.
         *
         * The source is carried through rather than judged here: whether it exists, and whether
         * the suffix is the one it publishes, is the server's business - this stays a pure
         * function of the path, which is what makes it worth testing on its own.
         */
        fun parseTilePath(path: String): TileRequest? {
            val match = TILE_PATH.matchEntire(path) ?: return null
            return TileRequest(
                source = match.groupValues[1],
                tile = TileRef(
                    zoom = match.groupValues[2].toInt(),
                    x = match.groupValues[3].toInt(),
                    y = match.groupValues[4].toInt()
                ),
                suffix = match.groupValues[5]
            )
        }
    }
}

/**
 * One basemap the tile server serves: where its tiles are kept, how they are named, and where a
 * tile that is not held comes from.
 *
 * [upstream] is a function rather than a fetcher because it is read per request: a LINZ key
 * pasted into Settings has to take effect on the next tile, not on the next app launch.
 */
data class TileSource(
    val id: String,
    val store: OfflineTileStore,
    val suffix: String,
    val contentType: String,
    val upstream: () -> TileFetcher? = { null }
)

/** A tile as the map asked for it: which source, which tile, and in what form. */
data class TileRequest(val source: String, val tile: TileRef, val suffix: String)
