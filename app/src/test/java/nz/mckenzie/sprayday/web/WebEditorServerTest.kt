package nz.mckenzie.sprayday.web

import nz.mckenzie.sprayday.domain.backup.AssetRecord
import nz.mckenzie.sprayday.offline.HttpRequest
import nz.mckenzie.sprayday.offline.HttpResponse
import nz.mckenzie.sprayday.offline.HttpRoute
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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

    /** The text of a write. What belongs in it is [WebEditorEditTest]'s business; that it arrives whole, and what comes back, is here. */
    private val writeBody = """{"name":"Estuary road","version":"v1"}"""

    /**
     * The body a drawing mode sends: the details the desk is looking at and the line it has drawn, and
     * no version, because a new asset has nothing to be stale against.
     */
    private val newBody =
        """{"name":"Gully track","kind":"TRACK","shape":"LINE","method":"UNSET","intervalDays":"120",""" +
            """"points":[{"lat":-41.5,"lng":173.8},{"lat":-41.6,"lng":173.9}]}"""

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

    /** A request with a body, written by hand: the shape a browser sends, body and all. */
    private fun send(
        path: String,
        method: String,
        body: String = "",
        withToken: Boolean = true
    ): Response {
        val bytes = body.toByteArray(Charsets.UTF_8)
        val head = buildString {
            append("$method $path")
            // A path that already carries a query - a delete, whose version travels in one - gets the
            // token joined onto it rather than asked for twice.
            if (withToken) append(if (path.contains('?')) "&k=$token" else "?k=$token")
            append(" HTTP/1.1\r\n")
            append("Host: 127.0.0.1:$port\r\n")
            append("Content-Type: application/json\r\n")
            append("Content-Length: ${bytes.size}\r\n")
            append("Connection: close\r\n\r\n")
        }
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(head.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().write(bytes)
            socket.getOutputStream().flush()
            return split(socket.getInputStream().readBytes().toString(Charsets.UTF_8))
        }
    }

    /** An answer read back as it arrived, so a test can hold the code, the type and the document. */
    private fun split(response: String): Response {
        val head = response.substringBefore("\r\n\r\n")
        return Response(
            code = head.substringAfter("HTTP/1.1 ").substringBefore(' ').toInt(),
            body = response.substringAfter("\r\n\r\n"),
            contentType = head.lineSequence()
                .firstOrNull { it.startsWith("Content-Type:", ignoreCase = true) }
                ?.substringAfter(":")?.trim()
        )
    }

    /** Stands in for the database: the documents are [WebEditorJsonTest]'s business. */
    private class FakeData : WebEditorData {
        var lastAuthority: String? = null

        /** What a write was asked to do, so a route is held to handing the id and the body over. */
        var lastWrite: Pair<Long, String?>? = null

        /** The body a new asset was made from. */
        var lastCreate: String? = null

        /** The id and the version a delete was asked for, version and all. */
        var lastRemove: Pair<Long, String?>? = null

        override suspend fun state(authority: String) = document(authority, "state")

        override suspend fun assets() = """{"what":"assets"}"""

        override suspend fun style(authority: String) = document(authority, "style")

        /**
         * The three outcomes a write route has to pass on, told apart by the id it was asked for -
         * so one fake covers a save, a stale card and an asset that is not there. What makes an edit
         * one of those is [WebEditorEditTest]'s business; what a route does with the answer is this
         * file's.
         */
        override suspend fun save(id: Long, body: String?): WebEditorWrite {
            lastWrite = id to body
            return when (id) {
                STALE_ID -> WebEditorWrite.Refused(WebEditorRefusal.STALE, "somebody changed it")
                MISSING_ID -> WebEditorWrite.Refused(WebEditorRefusal.MISSING, "no such track")
                else -> WebEditorWrite.Saved(record(id))
            }
        }

        /** A new asset, refused for one body so a create's own refusal has a route to travel. */
        override suspend fun create(body: String?): WebEditorWrite {
            lastCreate = body
            return if (body.isNullOrBlank()) {
                WebEditorWrite.Refused(
                    WebEditorRefusal.INVALID,
                    "Give it a name so it can be found later"
                )
            } else {
                WebEditorWrite.Created(record(7L))
            }
        }

        override suspend fun remove(id: Long, version: String?): WebEditorWrite {
            lastRemove = id to version
            return when (id) {
                IN_USE_ID -> WebEditorWrite.Refused(
                    WebEditorRefusal.IN_USE,
                    "\"Estuary road\" has 3 sprays on the phone, so it is not deleted from here."
                )
                MISSING_ID -> WebEditorWrite.Refused(WebEditorRefusal.MISSING, "no such track")
                else -> WebEditorWrite.Removed("Estuary road is gone from the phone.")
            }
        }

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

        /** The record a save, a create or a delete answers with, as the phone would build it. */
        private fun record(id: Long) = WebEditorAssetRecord(
            asset = AssetRecord(
                id = id,
                name = "Estuary road",
                intervalDays = 120,
                createdAtEpochMs = 1_700_000_000_000L
            ),
            dueStatus = "DUE_SOON",
            version = "version-$id",
            removal = WebEditorRemoval(
                allowed = true,
                sentence = "Nothing is recorded against \"Estuary road\", so deleting it here takes " +
                    "nothing else with it."
            )
        )

        companion object {
            const val PAGE = "<html><body>the editor</body></html>"

            /** The id the fake refuses as stale, and the one it has never heard of. */
            const val STALE_ID = 409L
            const val MISSING_ID = 404L

            /** The id the fake will not delete, because there are sprays on it. */
            const val IN_USE_ID = 423L
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

    @Test
    fun `a write needs the token as surely as a read does`() {
        start()

        assertEquals(403, send("/api/assets/7", "PUT", writeBody, withToken = false).code)
        assertNull("a refused write never reached the data", data.lastWrite)
    }

    @Test
    fun `a write reaches the data with the id from the path and the body as it arrived`() {
        start()

        val response = send("/api/assets/7", "PUT", writeBody)

        assertEquals(200, response.code)
        assertEquals("application/json; charset=utf-8", response.contentType)
        assertEquals(7L to writeBody, data.lastWrite)
        // The answer is the asset, because that is what lets the page update its own copy at once.
        assertTrue("the answer is the asset: ${response.body}", response.body.contains("\"Estuary road\""))
        assertTrue(
            "and the version the next edit must quote: ${response.body}",
            response.body.contains("\"version-7\"")
        )
    }

    @Test
    fun `a refusal keeps its own status and carries the words for the operator`() {
        start()

        val stale = send("/api/assets/${FakeData.STALE_ID}", "PUT", writeBody)
        assertEquals(409, stale.code)
        assertEquals("""{"reason":"stale","message":"somebody changed it"}""", stale.body)

        val missing = send("/api/assets/${FakeData.MISSING_ID}", "PUT", writeBody)
        assertEquals(404, missing.code)
        assertEquals("""{"reason":"not-found","message":"no such track"}""", missing.body)
    }

    @Test
    fun `a path that does not name an asset is a 404, not an asset`() {
        start()

        // Every one of these would become an id to a parser that took what it was given - and an id
        // is the one thing a write cannot be allowed to invent.
        assertEquals(404, send("/api/assets/seven", "PUT", writeBody).code)
        assertEquals(404, send("/api/assets/7/geometry", "PUT", writeBody).code)
        assertEquals(404, send("/api/assets/", "PUT", writeBody).code)
        assertEquals(404, send("/api/assets/0", "PUT", writeBody).code)
        // The GeoJSON document is not an asset's path: its own route answers a GET from it, and a
        // write to it is a path nothing here serves.
        assertEquals(200, get(WebEditorServer.ASSETS_PATH).code)
        assertEquals(404, send("/api/assets.geojson", "PUT", writeBody).code)

        assertNull("nothing was written", data.lastWrite)
    }

    @Test
    fun `the id parser takes digits and nothing else`() {
        assertEquals(7L, WebEditorServer.assetId("/api/assets/7"))
        assertEquals(123456L, WebEditorServer.assetId("/api/assets/123456"))
        assertNull(WebEditorServer.assetId("/api/assets/"))
        assertNull(WebEditorServer.assetId("/api/assets/seven"))
        assertNull(WebEditorServer.assetId("/api/assets/7/geometry"))
        assertNull(WebEditorServer.assetId("/api/assets/0"))
        assertNull(WebEditorServer.assetId("/api/assets/-1"))
        assertNull(WebEditorServer.assetId("/api/assets.geojson"))
        assertNull(WebEditorServer.assetId("/api/assets"))
        assertNull(WebEditorServer.assetId("/api/state"))
        // Longer than a Long is not an id either, rather than a number that wrapped into one.
        assertNull(WebEditorServer.assetId("/api/assets/99999999999999999999"))
    }

    @Test
    fun `a new asset is made at the collection's path and answered with a 201, not a 200`() {
        start()

        val response = send(WebEditorServer.COLLECTION_PATH, "POST", newBody)

        // 201 rather than 200: a page that made something and a page that changed something are doing
        // different things, and a person reading the answer off a command line can tell them apart.
        assertEquals(201, response.code)
        assertEquals("application/json; charset=utf-8", response.contentType)
        assertEquals("the body arrived whole", newBody, data.lastCreate)
        assertTrue("the answer is the new record: ${response.body}", response.body.contains("\"Estuary road\""))
        assertTrue(
            "with the version the next edit has to quote: ${response.body}",
            response.body.contains("\"version-7\"")
        )
    }

    @Test
    fun `making a new asset needs the token, and its own refusal keeps its status`() {
        start()

        assertEquals(403, send(WebEditorServer.COLLECTION_PATH, "POST", newBody, withToken = false).code)
        assertNull("a refused create never reached the data", data.lastCreate)

        val refused = send(WebEditorServer.COLLECTION_PATH, "POST", "")
        assertEquals(400, refused.code)
        assertEquals(
            """{"reason":"invalid","message":"Give it a name so it can be found later"}""",
            refused.body
        )
    }

    @Test
    fun `making one is not saving one, so an id on the end of the path is a 404`() {
        start()

        // `/api/assets/7` is an asset, and a POST to it would be asking the phone to make a second
        // asset numbered 7. Nothing serves that, so nothing answers it.
        assertEquals(404, send("/api/assets/7", "POST", newBody).code)
        assertNull(data.lastCreate)
    }

    @Test
    fun `a delete reaches the data with the id from the path and the version from the query`() {
        start()

        val response = send("/api/assets/7?version=v7", "DELETE")

        assertEquals(200, response.code)
        assertEquals("application/json; charset=utf-8", response.contentType)
        assertEquals(7L to "v7", data.lastRemove)
        assertEquals("""{"message":"Estuary road is gone from the phone."}""", response.body)
    }

    @Test
    fun `a delete that says no version is the data's to refuse, not the route's to invent`() {
        start()

        assertEquals(200, send("/api/assets/7", "DELETE").code)
        // Null rather than an empty string: "the request did not say which track it read" is one thing,
        // and a route that filled the gap in would be making up the answer to the only question that
        // protects a season's record.
        assertEquals(7L to null, data.lastRemove)
    }

    @Test
    fun `a delete the phone will not take keeps its own status and its numbers`() {
        start()

        val inUse = send("/api/assets/${FakeData.IN_USE_ID}?version=v1", "DELETE")

        // 409 with a reason of its own: a page that reloaded the work on every 409 would reload it for
        // a refusal that says nothing has moved.
        assertEquals(409, inUse.code)
        assertTrue("the reason is the delete's own: ${inUse.body}", inUse.body.contains("\"reason\":\"in-use\""))
        assertTrue("and the count is in the words: ${inUse.body}", inUse.body.contains("3 sprays"))
    }

    @Test
    fun `a delete needs the token as surely as a read does`() {
        start()

        assertEquals(403, send("/api/assets/7?version=v7", "DELETE", withToken = false).code)
        assertNull("a refused delete never reached the data", data.lastRemove)
    }

    @Test
    fun `a path that does not name an asset is a 404 for a delete too`() {
        start()

        assertEquals(404, send("/api/assets/seven", "DELETE").code)
        assertEquals(404, send("/api/assets/", "DELETE").code)
        assertEquals(404, send("/api/assets/0", "DELETE").code)
        // The collection is where a new asset is made, not an asset in itself: a delete there names
        // nothing, and a route that guessed would be guessing with a season's record.
        assertEquals(404, send(WebEditorServer.COLLECTION_PATH, "DELETE").code)

        assertNull("nothing was deleted", data.lastRemove)
    }
}
