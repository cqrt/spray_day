package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.CoroutineScope
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.SupervisorJob
import kotlinx.coroutines.cancel
import kotlinx.coroutines.launch
import java.io.OutputStream
import java.io.Reader
import java.net.InetAddress
import java.net.ServerSocket
import java.net.Socket
import java.net.URLDecoder

/**
 * The HTTP plumbing this app's servers share: a socket, an accept loop, a request parser, a
 * response writer and a small route table. Nothing in here knows what a tile or an asset is.
 *
 * It was split out of [LocalTileServer] when a second server - the one that serves the track editor
 * to a computer on the same Wi-Fi - needed the same plumbing. The alternatives were worse: giving
 * the second server its own socket handling leaves two hand-rolled parsers to keep honest, and
 * generalising the tile server itself would put the imagery every map in the app depends on behind
 * the editor's switch. So the plumbing moved and the routes stayed where the knowledge is.
 *
 * One deliberate omission is left, because this is a move and not a redesign: there is **no
 * keep-alive**, so every answer says `Connection: close`, as the tile server always has. The request
 * body arrived with the editor's first write - `PUT /api/assets/<id>` - and is read here, up to a
 * cap and as text. A body promised in chunks is refused rather than half-read: a promise to send the
 * length in pieces is not a length, and guessing at one is how a server hangs.
 */
class HttpServer(
    private val host: String,
    private val routes: List<HttpRoute>,
    /** The port to ask for; [ANY_PORT] means "whatever is free". */
    private val requestedPort: Int = ANY_PORT
) {

    private val scope = CoroutineScope(SupervisorJob() + Dispatchers.IO)
    private var socket: ServerSocket? = null

    /** The port it bound, or 0 before it starts. */
    var port: Int = 0
        private set

    val isRunning: Boolean get() = socket?.isClosed == false

    /** `host:port`, once bound - what a URL to this server is built from. */
    val authority: String get() = "$host:$port"

    /** Binds the socket and starts accepting. Returns the port it got. */
    fun start(): Int {
        check(socket == null) { "the server is already running" }
        val server = ServerSocket(requestedPort, BACKLOG, InetAddress.getByName(host))
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

    private fun acceptLoop(server: ServerSocket) {
        while (!server.isClosed) {
            val client = runCatching { server.accept() }.getOrNull() ?: break
            scope.launch { runCatching { handle(client) } }
        }
    }

    private suspend fun handle(client: Socket) {
        client.use { connection ->
            connection.soTimeout = SOCKET_TIMEOUT_MS
            when (val reading = readRequest(connection)) {
                // A connection that said nothing: there is nothing to answer to.
                is Reading.Silent -> return@use
                is Reading.Refused -> writeResponse(connection.getOutputStream(), reading.response)
                is Reading.Read -> writeResponse(
                    connection.getOutputStream(),
                    answer(reading.request)
                )
            }
        }
    }

    private suspend fun answer(request: HttpRequest): HttpResponse =
        routes.firstOrNull { it.claims(request) }?.handler?.invoke(request) ?: NOT_FOUND

    /** What came off the socket: a request to answer, a refusal, or nothing worth answering. */
    private sealed interface Reading {

        /** A connection that said nothing at all - no request line. There is nothing to answer. */
        object Silent : Reading

        /** A request refused before any route saw it, and what to say about that. */
        data class Refused(val response: HttpResponse) : Reading

        /** A request, with its body when it had one. */
        data class Read(val request: HttpRequest) : Reading
    }

    /**
     * The request line, the headers and the body - or what to answer when there is no readable
     * request line at all, which closes the socket because there is nothing to answer to.
     *
     * The body is read from the same buffered reader as the headers rather than from the socket,
     * because that reader has already pulled part of the body into its own buffer and asking the
     * stream for the rest would lose it. Reading it character by character is deliberate too: the
     * declared length is in bytes, so a body with a macron in a track's name would be cut off or
     * over-read by a character count, and over-reading means a server waiting for bytes that were
     * never sent until the socket times out. Bodies here are a few hundred bytes of JSON, so the
     * characters are not worth a second buffer to avoid.
     */
    private fun readRequest(connection: Socket): Reading {
        val reader = connection.getInputStream().bufferedReader()
        val requestLine = reader.readLine() ?: return Reading.Silent
        // Drain the headers, keeping the ones a route may look at. Leaving them unread makes some
        // clients wait for a response that is never coming.
        val headers = mutableMapOf<String, String>()
        while (true) {
            val line = reader.readLine() ?: break
            if (line.isEmpty()) break
            val name = line.substringBefore(':', "").trim().lowercase()
            if (name.isEmpty()) continue
            headers[name] = line.substringAfter(':', "").trim()
        }
        val parts = requestLine.split(' ')
        val request = HttpRequest(
            method = parts.getOrNull(0).orEmpty(),
            target = parts.getOrNull(1).orEmpty(),
            headers = headers
        )

        // Saying "chunked" is a promise to send the length in pieces, and nothing here reads
        // pieces. The editor's page never does it - a fetch carrying a string always says how long
        // the string is - so the honest answer is to refuse rather than to guess.
        if (headers["transfer-encoding"]?.contains("chunked", ignoreCase = true) == true) {
            return Reading.Refused(CHUNKED_BODY)
        }

        val declared = headers["content-length"]?.trim() ?: return Reading.Read(request)
        val length = declared.toIntOrNull() ?: return Reading.Refused(BAD_BODY)
        if (length <= 0) return Reading.Read(request)
        if (length > MAX_BODY_BYTES) return Reading.Refused(BODY_TOO_LONG)
        val body = readBody(reader, length) ?: return Reading.Refused(BAD_BODY)
        return Reading.Read(request.copy(body = body))
    }

    /** The body, or null when it did not arrive whole. */
    private fun readBody(reader: Reader, length: Int): String? {
        val text = StringBuilder(length)
        val one = CharArray(1)
        var read = 0
        while (read < length) {
            if (reader.read(one) <= 0) return null
            val bytes = one[0].toString().toByteArray(Charsets.UTF_8).size
            // A character that would run past the declared length is a body that was cut off
            // mid-character, not a body this server should take a guess at.
            if (read + bytes > length) return null
            text.append(one[0])
            read += bytes
        }
        return text.toString()
    }

    private fun writeResponse(out: OutputStream, response: HttpResponse) {
        // Every code but 200 reads as "404 Error" rather than "404 Not Found": the reason phrase is
        // decoration and the number is the answer, and copying RFC 9110's table in here would be a
        // table to keep in step with nothing.
        val status = if (response.code == 200) "200 OK" else "${response.code} Error"
        out.write(
            (
                "HTTP/1.1 $status\r\n" +
                    "Content-Type: ${response.contentType}\r\n" +
                    "Content-Length: ${response.body.size}\r\n" +
                    "Connection: close\r\n" +
                    "\r\n"
                ).toByteArray(Charsets.US_ASCII)
        )
        out.write(response.body)
        out.flush()
    }

    companion object {
        /** The port to ask for when any free one will do. */
        const val ANY_PORT = 0

        /** What a request no route claims is answered with. */
        val NOT_FOUND = HttpResponse.text(404, "not found")

        private const val BACKLOG = 16
        private const val SOCKET_TIMEOUT_MS = 20_000

        /**
         * The longest body this server will read.
         *
         * One asset's details are a few hundred bytes; a quarter of a megabyte is room for fields
         * nobody has written yet many times over, and small enough that a request claiming to carry
         * a gigabyte is refused before anything is read into memory.
         */
        private const val MAX_BODY_BYTES = 256 * 1024

        private val BAD_BODY = HttpResponse.text(400, "the body did not arrive whole")
        private val BODY_TOO_LONG = HttpResponse.text(413, "that body is too long")
        private val CHUNKED_BODY = HttpResponse.text(411, "that body did not say how long it is")
    }
}


/**
 * One entry in a server's route table: whether it answers a request, and what it answers with.
 *
 * [claims] is given the whole request - method, target, query and headers - so that a server which
 * cares about more than the path says so in its own route, instead of this class growing an opinion
 * about methods it does not need. Routes are tried in order and the first match wins, so a server
 * lists its routes in the order it wants them judged.
 */
class HttpRoute(
    val claims: (HttpRequest) -> Boolean,
    val handler: suspend (HttpRequest) -> HttpResponse
)

/**
 * A request as it arrived: the method, the raw target, and the headers by lowercased name (header
 * names are case-insensitive, so anything downstream may look them up the way it thinks of them).
 *
 * [target] is carried alongside [path] and [query] because the tile path parser is anchored - it
 * judges the whole target, query and all - and this split is not allowed to change what the tile
 * server accepts. A route that wants the path on its own asks for [path].
 */
data class HttpRequest(
    val method: String,
    val target: String,
    val headers: Map<String, String>,
    /**
     * The body, decoded, or null when the request carried none.
     *
     * Text rather than bytes because the only body either server takes is a JSON document from the
     * editor's page, and because bytes and characters part company the moment an operator types a
     * macron into a track's name - which is why the declared length is counted in one of them and
     * the reading is counted in the other, down in `readBody`.
     */
    val body: String? = null
) {

    /** The target without its query, e.g. `/api/state`. */
    val path: String = target.substringBefore('?')

    /**
     * The query, decoded: first value wins for a repeated name, and a value that will not decode is
     * kept as it arrived, so a malformed token fails its own check rather than the whole request.
     */
    val query: Map<String, String> = parseQuery(target.substringAfter('?', ""))

    fun header(name: String): String? = headers[name.lowercase()]
}

/**
 * An answer. Both servers here send small things - a tile, a page, a JSON document - so one shape
 * with a body covers everything they do.
 */
class HttpResponse(val code: Int, val contentType: String, val body: ByteArray) {

    companion object {
        fun text(code: Int, body: String) =
            HttpResponse(code, "text/plain; charset=utf-8", body.toByteArray())

        fun bytes(code: Int, contentType: String, body: ByteArray) =
            HttpResponse(code, contentType, body)
    }
}

private fun parseQuery(raw: String): Map<String, String> {
    if (raw.isEmpty()) return emptyMap()
    val found = mutableMapOf<String, String>()
    raw.split('&').forEach { part ->
        if (part.isEmpty()) return@forEach
        val name = decode(part.substringBefore('='))
        if (name.isEmpty() || found.containsKey(name)) return@forEach
        found[name] = decode(part.substringAfter('=', ""))
    }
    return found
}

private fun decode(value: String): String =
    runCatching { URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)
