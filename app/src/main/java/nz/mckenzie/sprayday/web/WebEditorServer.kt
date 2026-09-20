package nz.mckenzie.sprayday.web

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
    private val token: String,
    private val data: WebEditorData,
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
     * a login screen. Nothing here is reachable without it.
     */
    private fun routes(): List<HttpRoute> = listOf(
        HttpRoute(
            claims = { !authorised(it) },
            handler = { HttpResponse.text(403, "no token") }
        ),
        jsonRoute(STATE_PATH) { authority -> data.state(authority) },
        HttpRoute(
            claims = { it.method == GET && it.path == ASSETS_PATH },
            handler = { json(data.assets()) }
        ),
        jsonRoute(STYLE_PATH) { authority -> data.style(authority) },
        HttpRoute(
            claims = { it.method == GET && tileRoute.claims(it) },
            // The browser's tile URLs carry the token in the query, and the tile route judges the
            // whole target - query and all - because that is what it has always done for the map.
            // So it is handed the path: the token has been checked by the gate above, and what is
            // left is a tile path.
            handler = { request -> tileRoute.handler(request.copy(target = request.path)) }
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

    private fun json(body: String): HttpResponse =
        HttpResponse.bytes(200, JSON, body.toByteArray(Charsets.UTF_8))

    /**
     * Whether the request carries this run's token.
     *
     * Compared without an early exit, so how long the answer takes says nothing about how much of
     * the token was right. It is a LAN secret rather than a password, but a comparison that leaks
     * its prefix is the one habit not worth keeping.
     */
    private fun authorised(request: HttpRequest): Boolean {
        val given = request.query[TOKEN_PARAM] ?: return false
        return MessageDigest.isEqual(given.toByteArray(Charsets.UTF_8), token.toByteArray(Charsets.UTF_8))
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

        /** What the token is called in a URL, e.g. `/api/state?k=7f3a…`. */
        const val TOKEN_PARAM = "k"

        /**
         * Every interface the phone has.
         *
         * The editor is the one server here that is meant to be reached from off the phone, and the
         * phone does not get to choose which of its addresses the laptop will use: it may be the
         * Wi-Fi address, or - through `adb forward`, which is how the checks in the plan reach it -
         * loopback. What guards it is the token, not the interface.
         */
        const val ANY_ADDRESS = "0.0.0.0"

        /** Only reads are served in this phase: the web may not change anything yet. */
        private const val GET = "GET"

        private const val JSON = "application/json; charset=utf-8"

        /** 8799 and the two after it, before any free port will do. */
        private const val PORT_ATTEMPTS = 3
    }
}

/**
 * What the editor's routes read, and the only place the repository is reached for.
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
     * The page and the files it asks for, from `app/src/main/assets/web`.
     *
     * Null for a path the editor does not ship, which the server answers as a 404. The path is the
     * request's own, without its query, and it is looked up by exact name: this is not a file
     * server, and a page that could ask for `../../databases/spray_day.db` would be one.
     */
    fun page(path: String): HttpResponse?
}
