package nz.mckenzie.sprayday.web

import nz.mckenzie.sprayday.offline.HttpRequest
import nz.mckenzie.sprayday.offline.HttpResponse
import nz.mckenzie.sprayday.offline.HttpRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Assert.fail
import org.junit.Test
import java.io.IOException
import java.net.HttpURLConnection
import java.net.Socket
import java.net.URL

/**
 * The editor's server, over a real socket.
 *
 * What is being held here is the gate and the shape of the answers, and the gate is the reason this
 * file exists: this server is a door into the operator's own data on the farm's Wi-Fi, so "403
 * without the token, the page included" is a claim that has to be proved rather than assumed. The
 * database is faked, because what the documents contain is [WebEditorJsonTest]'s business, and the
 * tile route is faked, because that route is [nz.mckenzie.sprayday.offline.LocalTileServerTest]'s
 * business - here it only has to be the same route object the app's own server uses.
 */
class WebEditorServerTest {

    private val token = "7f3a9c7f3a9c7f3a9c7f3a9c7f3a9c7f"

    private val data = FakeData()
    private val tiles = FakeTileRoute()

    private lateinit var server: WebEditorServer
    private var port: Int = 0

    private fun start(requestedPort: Int = 0) {
        server = WebEditorServer(
            host = "127.0.0.1",
            token = token,
            data = data,
            tileRoute = tiles.route,
            requestedPort = requestedPort
        )
        port = server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private data class Response(val code: Int, val body: String, val contentType: String?)

    private fun get(path: String, withToken: Boolean = true): Response {
        val url = "http://127.0.0.1:$port$path" + if (withToken) "?k=$token" else ""
        val connection = (URL(url).openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            Response(
                code = code,
                body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty(),
                contentType = connection.getHeaderField("Content-Type")
            )
        } finally {
            connection.disconnect()
        }
    }

    /** A request written by hand, for the one case a browser's own client will not let us send. */
    private fun rawGet(path: String, host: String): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(
                "GET $path HTTP/1.1\r\nHost: $host\r\nConnection: close\r\n\r\n"
                    .toByteArray(Charsets.UTF_8)
            )
            socket.getOutputStream().flush()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
        }
    }

    private class FakeData : WebEditorData {
        var lastAuthority: String? = null

        override suspend fun state(authority: String) = document(authority, "state")

        override suspend fun assets() = """{"what":"assets"}"""

        override suspend fun style(authority: String) = document(authority, "style")

        override fun page(path: String): HttpResponse? =
            if (path == "/" || path == "/index.html" || path == "/app.js") {
                HttpResponse.bytes(200, "text/html; charset=utf-8", PAGE.toByteArray())
            } else {
                null
            }

        private fun document(authority: String, what: String): String {
            lastAuthority = authority
            return """{"what":"$what","authority":"$authority"}"""
        }

        companion object {
            const val PAGE = "<html><body>the editor</body></html>"
        }
    }

    /** Stands in for the app's own tile route: the same object, and it says who asked for what. */
    private class FakeTileRoute {
        var seen: HttpRequest? = null

        val route = HttpRoute(
            claims = { it.path.startsWith("/tiles/") },
            handler = { request ->
                seen = request
                HttpResponse.bytes(200, "image/webp", byteArrayOf(1, 2, 3))
            }
        )
    }

    @Test
    fun `nothing at all is served without the token, the page included`() {
        start()

        assertEquals(403, get("/", withToken = false).code)
        assertEquals(403, get(WebEditorServer.STATE_PATH, withToken = false).code)
        assertEquals(403, get(WebEditorServer.STYLE_PATH, withToken = false).code)
        assertEquals(403, get(WebEditorServer.ASSETS_PATH, withToken = false).code)
        assertEquals(403, get("/tiles/osm/7/125/80.png", withToken = false).code)
    }

    @Test
    fun `the wrong token is refused, and so is a near miss on the right one`() {
        start()

        assertEquals(403, get("/api/state?k=nonsense", withToken = false).code)
        // The first thirty-one characters are not the token: no prefix is a token.
        assertEquals(403, get("/api/state?k=${token.dropLast(1)}", withToken = false).code)
        assertEquals(403, get("/api/state?k=${token}x", withToken = false).code)
    }

    @Test
    fun `the right token gets the page and the three documents`() {
        start()

        val page = get("/")
        assertEquals(200, page.code)
        assertEquals(FakeData.PAGE, page.body)

        val state = get(WebEditorServer.STATE_PATH)
        assertEquals(200, state.code)
        assertEquals("application/json; charset=utf-8", state.contentType)
        assertEquals("""{"what":"state","authority":"127.0.0.1:$port"}""", state.body)

        assertEquals("""{"what":"assets"}""", get(WebEditorServer.ASSETS_PATH).body)
        assertEquals("""{"what":"style","authority":"127.0.0.1:$port"}""", get(WebEditorServer.STYLE_PATH).body)
    }

    @Test
    fun `a page file is served, and a path that names nothing is a 404`() {
        start()

        assertEquals(200, get("/app.js").code)
        assertEquals(404, get("/not-a-file.js").code)
        // A path that walks up out of the page's own directory is a path nothing ships.
        assertEquals(404, get("/../../databases/spray_day.db").code)
    }

    @Test
    fun `the documents are built for the address the page was opened on`() {
        start()

        val response = rawGet("${WebEditorServer.STATE_PATH}?k=$token", "192.168.1.23:8799")

        assertTrue(response.startsWith("HTTP/1.1 200 OK"))
        assertEquals("192.168.1.23:8799", data.lastAuthority)
        assertTrue("the document points back at that address: $response", response.contains("192.168.1.23:8799"))
    }

    @Test
    fun `a tile is served by the app's own route, with the token taken off the URL first`() {
        start()

        val response = get("/tiles/linz-aerial/14/1017/660.webp")

        assertEquals(200, response.code)
        assertEquals("image/webp", response.contentType)
        // The tile route judges the whole target, query and all, exactly as it does for the map -
        // so the query the browser added is gone before the route sees it.
        assertEquals("/tiles/linz-aerial/14/1017/660.webp", tiles.seen?.target)
        assertEquals("/tiles/linz-aerial/14/1017/660.webp", tiles.seen?.path)
    }

    @Test
    fun `the port it asks for is the port it gets, and the address says which`() {
        start()

        assertTrue("a bound server has a port", port > 0)
        assertEquals("127.0.0.1:$port", server.authority)
        assertTrue(server.isRunning)
    }

    @Test
    fun `a port already in use is stepped over rather than refused`() {
        val first = WebEditorServer("127.0.0.1", token, data, tiles.route, requestedPort = 0)
        val firstPort = first.start()
        try {
            val second = WebEditorServer("127.0.0.1", token, data, tiles.route, requestedPort = firstPort)
            val secondPort = second.start()
            try {
                assertEquals("the next port rather than the same one", firstPort + 1, secondPort)
            } finally {
                second.stop()
            }
        } finally {
            first.stop()
        }
    }

    @Test
    fun `turning the switch off closes the door`() {
        start()

        server.stop()

        assertFalse(server.isRunning)
        try {
            get("/")
            fail("a stopped editor should not answer")
        } catch (expected: IOException) {
            // Nothing is listening any more, which is what the switch being off means.
        }
    }
}
