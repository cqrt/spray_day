package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket

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
 * Bound to loopback only, so nothing is exposed to the network. This replaces MapLibre's own
 * offline storage for imagery, whose hosted-style download we measured pulling ~10x the tiles
 * actually needed.
 */
class LocalTileServer(
    /** Every source this server can serve; the path names one of them. */
    private val sources: List<TileSource>,
    private val host: String = LOOPBACK
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null

    var port: Int = 0
        private set

    val isRunning: Boolean get() = socket?.isClosed == false

    /** Binds to an ephemeral loopback port. Returns the port. */
    fun start(): Int {
        check(socket == null) { "the tile server is already running" }
        val server = ServerSocket(0, BACKLOG, InetAddress.getByName(host))
        socket = server
        port = server.localPort
        scope.launch { acceptLoop(server) }
        return port
    }

    fun stop() {
        runCatching { socket?.close() }
        socket = null
        scope.cancel()
    }

    /**
     * The XYZ template to put in a style for [sourceId], e.g.
     * `http://127.0.0.1:41234/tiles/osm/{z}/{x}/{y}.png`.
     */
    fun tileUrlTemplate(sourceId: String): String {
        val source = sourceFor(sourceId) ?: error("no tile source called \"$sourceId\"")
        return "http://$host:$port/tiles/$sourceId/{z}/{x}/{y}${source.suffix}"
    }

    private fun sourceFor(id: String): TileSource? = sources.firstOrNull { it.id == id }

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { runCatching { handle(client) } }
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { connection ->
            connection.soTimeout = SOCKET_TIMEOUT_MS
            val reader = connection.getInputStream().bufferedReader()
            val requestLine = reader.readLine() ?: return
            // Drain the headers; leaving them unread makes some clients wait.
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
            }
            val path = requestLine.split(' ').getOrNull(1) ?: ""
            serve(path, connection.getOutputStream())
        }
    }

    private suspend fun serve(path: String, out: OutputStream) {
        if (path == STATUS_PATH) {
            respondText(out, 200, "ok")
            return
        }

        val request = parseTilePath(path)
        val source = request?.let { sourceFor(it.source) }
        if (request == null || source == null || request.suffix != source.suffix) {
            respondText(out, 404, "not found")
            return
        }
        val tile = request.tile

        source.store.read(tile.zoom, tile.x, tile.y)?.let { bytes ->
            respondTile(out, 200, source.contentType, bytes)
            return
        }

        when (val fetched = source.upstream()?.fetch(tile.zoom, tile.x, tile.y)) {
            is TileFetcher.Result.Tile -> {
                // Storing on the way through is what turns browsing into an offline pack for
                // imagery, and what keeps a browsed map from being downloaded twice.
                source.store.write(tile.zoom, tile.x, tile.y, fetched.bytes)
                respondTile(out, 200, source.contentType, fetched.bytes)
            }

            TileFetcher.Result.NotFound, null -> respondText(out, 404, "no imagery")
            is TileFetcher.Result.Failed -> respondText(out, 502, "upstream failed")
        }
    }

    private fun respondTile(out: OutputStream, code: Int, contentType: String, bytes: ByteArray) {
        writeResponse(out, code, contentType, bytes)
    }

    private fun respondText(out: OutputStream, code: Int, body: String) {
        writeResponse(out, code, "text/plain; charset=utf-8", body.toByteArray())
    }

    private fun writeResponse(out: OutputStream, code: Int, contentType: String, body: ByteArray) {
        val status = if (code == 200) "200 OK" else "$code Error"
        out.write(
            (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: $contentType\r\n" +
                    "Content-Length: ${body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII)
        )
        out.write(body)
        out.flush()
    }

    companion object {
        const val LOOPBACK = "127.0.0.1"
        const val STATUS_PATH = "/status"

        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 20_000

        /** `/tiles/{source}/{z}/{x}/{y}<suffix>` - the suffix is checked against the source. */
        private val TILE_PATH = Regex(
            "^/tiles/([a-z0-9][a-z0-9-]{0,30})/(\\d{1,2})/(\\d{1,7})/(\\d{1,7})(\\.[a-z0-9]{2,5})$"
        )

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
