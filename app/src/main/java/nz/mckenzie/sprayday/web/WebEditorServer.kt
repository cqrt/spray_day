package nz.mckenzie.sprayday.web

import nz.mckenzie.sprayday.map.PlaceIcons
import nz.mckenzie.sprayday.offline.HttpRequest
import nz.mckenzie.sprayday.offline.HttpResponse
import nz.mckenzie.sprayday.offline.HttpRoute
import nz.mckenzie.sprayday.offline.HttpServer
import java.security.MessageDigest

/**
 * The editor, served to a computer on the same Wi-Fi.
 *
 * A second [HttpServer] rather than the tile server wearing a second hat, and on the LAN rather
 * than on loopback: the tile server's promise - *bound to loopback only, so nothing is exposed to
 * the network* - is not weakened by one line to make this feature easy, because the two servers
 * have different answers to "who may ask". This one is a door into the same databases, so it is
 * shut unless somebody turns the switch on, it says no without the token, and it never outlives the
 * switch.
 *
 * **The token is a setting rather than a law.** It is what stops the other devices on the Wi-Fi -
 * a visitor's phone, a tablet in the ute, a printer - and, more to the point, any web page open on
 * the operator's own computer from reaching this door; a router keeps the internet out and does
 * nothing at all about either of those. That is what lets the switch be thrown on a network nobody
 * vouches for. On a network the operator owns, where every device on it is theirs, the secret buys
 * them little and costs them a paste, so a run may be served with no token at all: [token] is then
 * null, the gate below claims nothing, and the address on the Settings card is the bare one that
 * says so. What is *not* offered is an empty token, or a default that turns this off - an install
 * that never touches the switch asks for a token exactly as it always has.
 *
 * Everything it serves it serves from the phone: the documents, the page, and the tiles - the very
 * store the app's own map draws from, through the very route, so what a laptop draws is what has
 * already been downloaded for a trip with no reception.
 *
 * It holds no state and knows no rules. Whether an asset may be edited, what a delete would take
 * with it, what is due: all of that is [WebEditorData], which is where the repository is reached
 * for and where the answering happens. This class is routing, a token check and a port.
 */
class WebEditorServer(
    private val host: String,
    /** This run's token, or null when this run does not ask for one - see the class comment. */
    private val token: String?,
    private val data: WebEditorData,
    /**
     * One of the pictures a place is drawn with: the name the style asked for, the size the browser
     * asked for, and the bytes of the picture - or null for a name that is no picture.
     *
     * A function rather than something this class does, because the drawing is the one part of the
     * desk's answer that has to be Android: it is rendered with the app's own glyph code where the
     * service is started, and the routing here stays free of it.
     */
    private val markers: (String, Int) -> ByteArray? = { _, _ -> null },
    /** The tile route the app's own map uses, so the desk draws the phone's own tiles. */
    private val tileRoute: HttpRoute,
    /** The port to ask for; the next free ones are tried after it. */
    private val requestedPort: Int = WebEditorLink.DEFAULT_PORT
) {

    private var server: HttpServer? = null

    /** The port it bound, or 0 before it starts. */
    val port: Int get() = server?.port ?: 0

    val isRunning: Boolean get() = server?.isRunning == true

    /** `host:port`, once bound - the address the page is opened at. */
    val authority: String get() = "$host:$port"

    /**
     * Binds the first port that is free and returns it.
     *
     * A bookmarkable port is worth asking for and not worth insisting on: 8799 is the one the
     * operator's bookmark points at, so it is tried first, the two after it are tried next, and if
     * something else on the phone holds all three the OS is asked for any free port at all. The
     * address on the Settings card is read off the socket either way, so a fallback is a surprise
     * for nobody.
     */
    fun start(): Int {
        check(server == null) { "the editor's server is already running" }

        var lastFailure: Throwable? = null
        for (candidate in ports()) {
            val attempt = HttpServer(host = host, requestedPort = candidate, routes = routes())
            val bound = runCatching { attempt.start() }
            if (bound.isSuccess) {
                server = attempt
                return bound.getOrThrow()
            }
            lastFailure = bound.exceptionOrNull()
        }
        throw IllegalStateException("no port would take the editor's server", lastFailure)
    }

    fun stop() {
        server?.stop()
        server = null
    }

    private fun ports(): List<Int> =
        (requestedPort until requestedPort + PORT_ATTEMPTS).toList() + HttpServer.ANY_PORT

    /**
     * The routes, in the order they are judged.
     *
     * The gate is a route rather than a check inside each answer on purpose: it claims **everything**
     * that does not carry the token, so a route added later cannot be served by forgetting one line -
     * the page included, which is why opening the address without the token is a refusal rather than
     * a login screen. Nothing here is reachable without it - and when this run asks for no token at
     * all, the gate claims nothing, which is the whole of what that setting does.
     */
    private fun routes(): List<HttpRoute> = listOf(
        HttpRoute(
            claims = { token != null && !authorised(it) },
            handler = { HttpResponse.text(403, "no token") }
        ),
        jsonRoute(STATE_PATH) { authority -> data.state(authority) },
        HttpRoute(
            claims = { it.method == GET && it.path == ASSETS_PATH },
            handler = { json(data.assets()) }
        ),
        jsonRoute(STYLE_PATH) { authority -> data.style(authority) },
        HttpRoute(
            claims = { it.method == POST && it.path == COLLECTION_PATH },
            // A new asset: the only write whose path is not an asset's own, because the id it gets is
            // the phone's to issue. The answer is the record, so the page can open the card on it
            // without asking again.
            handler = { request -> write(data.create(request.body)) }
        ),
        HttpRoute(
            claims = { it.method == PUT && assetId(it.path) != null },
            // The id is asked for twice, once to decide whether this route answers at all and once
            // to answer with - so a route can never answer for a path it did not claim, and there is
            // no ignoring of a null that the claim above already ruled out.
            handler = { request ->
                assetId(request.path)?.let { id -> write(data.save(id, request.body)) }
                    ?: HttpServer.NOT_FOUND
            }
        ),
        HttpRoute(
            claims = { it.method == DELETE && assetId(it.path) != null },
            // The version travels in the query rather than in a body: a DELETE carrying one is a thing
            // the way between a browser and an HttpURLConnection is entitled to drop, and the token is
            // already travelling in that query. A delete without one is refused by the data, in words,
            // rather than by this route: what a missing version means is the same verdict an edit gets.
            handler = { request ->
                assetId(request.path)?.let { id -> write(data.remove(id, request.query[VERSION_PARAM])) }
                    ?: HttpServer.NOT_FOUND
            }
        ),
        HttpRoute(
            claims = { it.method == GET && tileRoute.claims(it) },
            // The browser's tile URLs carry the token in the query, and the tile route judges the
            // whole target - query and all - because that is what it has always done for the map.
            // So it is handed the path: the token has been checked by the gate above, and what is
            // left is a tile path.
            handler = { request -> tileRoute.handler(request.copy(target = request.path)) }
        ),
        // The markers a place is drawn with: the phone's own pictures, rendered at whatever size the
        // browser's screen needs. Before the page route, because a marker is not a file the editor
        // ships - see [MARKERS_PATH_PREFIX] - and after the tiles, which are bigger and commoner.
        HttpRoute(
            claims = { it.method == GET && markerName(it.path) != null },
            handler = { request ->
                val name = markerName(request.path)
                val bytes = name?.let { markers(it, pxFor(request.query)) }
                if (bytes == null) HttpServer.NOT_FOUND else HttpResponse.bytes(200, PNG, bytes)
            }
        ),
        // Everything else is the page and the files it needs, or a plain 404 for a path that names
        // nothing the editor ships.
        HttpRoute(
            claims = { it.method == GET },
            handler = { data.page(it.path) ?: HttpServer.NOT_FOUND }
        )
    )

    private fun jsonRoute(path: String, body: suspend (String) -> String) = HttpRoute(
        claims = { it.method == GET && it.path == path },
        handler = { request -> json(body(authorityFor(request))) }
    )

    private fun json(body: String, status: Int = 200): HttpResponse =
        HttpResponse.bytes(status, JSON, body.toByteArray(Charsets.UTF_8))

    /**
     * A write's outcome as an answer: the new record, the sentence about what is gone, or the refusal
     * with its own status.
     *
     * The status is [WebEditorRefusal]'s and [WebEditorWrite]'s, not this class's guess, so what a page
     * is told when a save is refused is decided where the refusal is decided - and a new asset is told
     * apart from an edited one by the shape of the answer rather than by the route it arrived on. The
     * three successes are 200, 201 and 200: a made thing and a changed thing are not the same event,
     * while a delete has nothing left to describe.
     */
    private fun write(result: WebEditorWrite): HttpResponse = when (result) {
        is WebEditorWrite.Saved -> json(WebEditorJson.saved(result.record))
        is WebEditorWrite.Created -> json(WebEditorJson.saved(result.record), status = 201)
        is WebEditorWrite.Removed -> json(WebEditorJson.removed(result.message))
        is WebEditorWrite.Refused -> HttpResponse.bytes(
            result.refusal.status,
            JSON,
            WebEditorJson.refused(result.refusal, result.message).toByteArray(Charsets.UTF_8)
        )
    }

    /**
     * Whether the request carries this run's token.
     *
     * Compared without an early exit, so how long the answer takes says nothing about how much of
     * the token was right. It is a LAN secret rather than a password, but a comparison that leaks
     * its prefix is the one habit not worth keeping. A run with no token accepts everything, and
     * the gate above is what decides whether this is asked at all.
     */
    private fun authorised(request: HttpRequest): Boolean {
        val wanted = token ?: return true
        val given = request.query[TOKEN_PARAM] ?: return false
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), wanted.toByteArray(Charsets.UTF_8))
    }

    /**
     * The address the page was asked on, so the documents it is handed point back at the same one.
     *
     * A phone usually holds more than one address on the Wi-Fi and the operator may open any of
     * them, so the request's own `Host` is the only honest answer to "where are the tiles from
     * here". With no `Host` (not a browser), the server's own address is used.
     */
    private fun authorityFor(request: HttpRequest): String =
        request.header("host")?.takeIf { it.isNotBlank() } ?: authority

    companion object {
        const val STATE_PATH = "/api/state"
        const val ASSETS_PATH = "/api/assets.geojson"
        const val STYLE_PATH = "/api/style"

    /**
     * Where the pictures a place is drawn with are served, e.g.
     * `/api/markers/sprayday-place-sign-2e7d32.png`.
     *
     * The phone draws every marker it knows how to draw - see `map/MarkerIcons.kt` - and the page
     * fetches the ones the style names. That way a bench seat on the desk is the bench seat on the
     * phone, rather than a second drawing of one that can drift.
     */
    const val MARKERS_PATH_PREFIX = "/api/markers/"

    /**
     * How big the browser wants a marker, in its own pixels.
     *
     * A marker is a picture drawn at its own pixels rather than scaled by the style, so the size is
     * the page's to ask for: a laptop with a 1:1 screen wants 22 pixels and one with a 2:1 screen
     * wants 44, and both want it crisp.
     */
    const val PX_PARAM = "px"

    /** Nothing smaller than a smudge or larger than a thumbnail, whatever a URL asks for. */
    private const val MIN_MARKER_PX = 8
    private const val MAX_MARKER_PX = 256

    /**
     * The picture name in a path like `/api/markers/sprayday-place-sign-2e7d32.png`, or null when the
     * path is not one of those.
     *
     * Judged as strictly as an asset id is: a marker name is lowercase letters, digits and dashes, so
     * a path with anything else in it - a `..`, a slash, a query somebody hoped would be ignored - is
     * a path nothing serves rather than a name to be looked up and found wanting.
     */
    fun markerName(path: String): String? {
        val tail = path.removePrefix(MARKERS_PATH_PREFIX)
        if (tail == path) return null

        val name = tail.removeSuffix(".png")
        if (name == tail || name.isEmpty()) return null
        if (!name.all { it.isLowerCase() || it.isDigit() || it == '-' }) return null
        return name
    }

    /** The size the browser asked for, or the app's own marker size when it did not say. */
    private fun pxFor(query: Map<String, String>): Int = query[PX_PARAM]
        ?.toIntOrNull()
        ?.coerceIn(MIN_MARKER_PX, MAX_MARKER_PX)
        ?: PlaceIcons.MARKER_DP.toInt()

        /** Where a new asset is made: `/api/assets`, with no id on the end of it. */
        const val COLLECTION_PATH = "/api/assets"

        /** What the token is called in a URL, e.g. `/api/state?k=7f3a…`. */
        const val TOKEN_PARAM = "k"

        /**
         * What a delete says it is deleting, e.g. `/api/assets/7?k=…&version=9b1c…`.
         *
         * Named `version` rather than `v`, unlike the token's `k`: this one is written by a page's own
         * code and read by a person with a command line when something goes wrong, and a name costs
         * nothing in a URL that already carries a token.
         */
        const val VERSION_PARAM = "version"

        /**
         * Every interface the phone has.
         *
         * The editor is the one server here that is meant to be reached from off the phone, and the
         * phone does not get to choose which of its addresses the laptop will use: it may be the
         * Wi-Fi address, or - through `adb forward`, which is how the checks in the plan reach it -
         * loopback. What guards it is the token, not the interface.
         */
        const val ANY_ADDRESS = "0.0.0.0"

        /** What an asset's own path starts with, e.g. `/api/assets/7`. */
        const val ASSET_PATH_PREFIX = "/api/assets/"

        private const val GET = "GET"

        /** A new asset, at the collection's own path. */
        private const val POST = "POST"

        /** An asset's own details, and its line, at the asset's own path. */
        private const val PUT = "PUT"

        /** Taking an asset away, at the asset's own path. */
        private const val DELETE = "DELETE"

        /**
         * The id in a path like `/api/assets/7`, or null when the path is not one of those.
         *
         * The only part of a write that arrives as text, and it is parsed strictly for the same
         * reason the tile path is: `/api/assets/seven`, `/api/assets/7/geometry` and `/api/assets/`
         * have to be paths nothing serves rather than asset 0, or asset 7 with something attached.
         * The digits are checked before the number, so a path cannot become an id through a `toLong`
         * that quietly ignored what it did not understand.
         */
        fun assetId(path: String): Long? {
            val tail = path.removePrefix(ASSET_PATH_PREFIX)
            if (tail == path || tail.isEmpty() || tail.any { !it.isDigit() }) return null
            return tail.toLongOrNull()?.takeIf { it > 0L }
        }

        private const val JSON = "application/json; charset=utf-8"

    /** A marker is a drawing with edges rather than a photograph, so it travels as a PNG. */
    private const val PNG = "image/png"

        /** 8799 and the two after it, before any free port will do. */
        private const val PORT_ATTEMPTS = 3
    }
}

/**
 * What the editor's routes read and write, and the only place the repository is reached for.
 *
 * An interface rather than the repository itself for two reasons. The routes can then be tested
 * over a real socket with a fake standing in for the database, which is the only way to prove the
 * token gate, the 404s and the shape of the answers without an emulator; and the rule about what
 * the web may see or touch is written once, in the implementation, rather than as an understanding
 * between a route and a repository.
 *
 * The authority is passed in per request because the documents contain URLs - the tile template,
 * the GeoJSON the style's source reads - and those have to be the address the page was opened on.
 */
interface WebEditorData {

    /** `GET /api/state`: the work, its due colours, its blocks and the catalogue. */
    suspend fun state(authority: String): String

    /** `GET /api/assets.geojson`: the same features the app's own map draws. No authority: a
     * GeoJSON document carries the coordinates and nothing else. */
    suspend fun assets(): String

    /** `GET /api/style`: the style, built from the app's own layer tables. */
    suspend fun style(authority: String): String

    /**
     * `PUT /api/assets/<id>`: the details the desk changed, and what the phone made of them.
     *
     * The body is the page's JSON and it is judged here rather than in the route, so what a write
     * may change - and what it is refused for - is decided in one place ([WebEditorEdits]) and the
     * route stays a route. A null body is not an error to be thrown about: it is an edit that could
     * not be read, which is a refusal with a sentence in it like any other.
     */
    suspend fun save(id: Long, body: String?): WebEditorWrite

    /**
     * `POST /api/assets`: a new track or place, drawn and described on a computer.
     *
     * No id and no version anywhere in it: the id is the database's to issue, and there is no row yet
     * for a version to describe. The body is the page's JSON, judged by the same rules an edit goes
     * through - a blank row is handed to them where an edit's own row would be.
     */
    suspend fun create(body: String?): WebEditorWrite

    /**
     * `DELETE /api/assets/<id>`: taking a track away, when there is nothing on it to lose.
     *
     * [version] is the fingerprint the card was handed, read out of the query. Null when the request
     * carried none, which is a refusal with a sentence in it rather than a crash: a delete that cannot
     * say which track it read is a delete nobody can be held to.
     */
    suspend fun remove(id: Long, version: String?): WebEditorWrite

    /**
     * The page and the files it asks for, from `app/src/main/assets/web`.
     *
     * Null for a path the editor does not ship, which the server answers as a 404. The path is the
     * request's own, without its query, and it is looked up by exact name: this is not a file
     * server, and a page that could ask for `../../databases/spray_day.db` would be one.
     */
    fun page(path: String): HttpResponse?
}
