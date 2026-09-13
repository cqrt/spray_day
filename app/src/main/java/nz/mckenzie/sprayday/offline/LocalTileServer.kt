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
 * A tiny HTTP tile server on loopback, so the map has **one** tile path whether
 * or not there is a network.
 *
 * The map requests `http://127.0.0.1:<port>/tiles/{z}/{x}/{y}.webp`:
 *
 *  - a tile in the local store is served straight from disk, so downloaded areas
 *    work with no reception, and anything browsed online is kept;
 *  - a tile that is not held is fetched from LINZ and stored on the way through,
 *    so the offline store fills itself as the operator uses the map;
 *  - with no upstream (no key, or no network) a miss is a plain 404 and the map
 *    shows its background for that tile.
 *
 * Bound to loopback only, so nothing is exposed to the network. This replaces
 * MapLibre's own offline storage for imagery, whose hosted-style download we
 * measured pulling ~10x the tiles actually needed.
 */
class LocalTileServer(
    private val store: OfflineTileStore,
    /** Re-read per request, so a key changed in Settings takes effect at once. */
    private val upstreamProvider: () -> TileFetcher? = { null },
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

    /** The XYZ template to put in a style, e.g. `http://127.0.0.1:41234/tiles/{z}/{x}/{y}.webp`. */
    fun tileUrlTemplate(): String = "http://$host:$port/tiles/{z}/{x}/{y}$SUFFIX"

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

        val tile = parseTilePath(path)
        if (tile == null) {
            respondText(out, 404, "not found")
            return
        }

        store.read(tile.zoom, tile.x, tile.y)?.let { bytes ->
            respondTile(out, 200, bytes)
            return
        }

        when (val fetched = upstreamProvider()?.fetch(tile.zoom, tile.x, tile.y)) {
            is TileFetcher.Result.Tile -> {
                // Storing on the way through is what turns browsing into an
                // offline pack.
                store.write(tile.zoom, tile.x, tile.y, fetched.bytes)
                respondTile(out, 200, fetched.bytes)
            }

            TileFetcher.Result.NotFound, null -> respondText(out, 404, "no imagery")
            is TileFetcher.Result.Failed -> respondText(out, 502, "upstream failed")
        }
    }

    private fun respondTile(out: OutputStream, code: Int, bytes: ByteArray) {
        writeResponse(out, code, "image/webp", bytes)
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
        const val SUFFIX = ".webp"
        const val STATUS_PATH = "/status"

        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 20_000
        private val TILE_PATH = Regex("^/tiles/(\\d{1,2})/(\\d{1,7})/(\\d{1,7})\\.webp$")

        /** `/tiles/{z}/{x}/{y}.webp` to a tile reference, or null if it is not a tile path. */
        fun parseTilePath(path: String): TileRef? {
            val match = TILE_PATH.matchEntire(path) ?: return null
            return TileRef(
                zoom = match.groupValues[1].toInt(),
                x = match.groupValues[2].toInt(),
                y = match.groupValues[3].toInt()
            )
        }
    }
}
