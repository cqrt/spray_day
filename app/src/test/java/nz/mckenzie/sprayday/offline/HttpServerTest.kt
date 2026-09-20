package nz.mckenzie.sprayday.offline

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
 * The HTTP plumbing on its own, over a real loopback socket.
 *
 * It is what both servers in the app are built on, so what it promises - a route table, first match
 * wins, the parts of a request a route can see, and answers that say how long they are - is pinned
 * here rather than only through the tile server that happened to need it first. Nothing here would
 * notice if the tile server changed; that is the point of the split, and the reason it is worth
 * testing apart from [LocalTileServerTest].
 */
class HttpServerTest {

    private lateinit var server: HttpServer
    private var port: Int = 0

    private fun start(vararg routes: HttpRoute) {
        server = HttpServer(host = "127.0.0.1", routes = routes.toList())
        port = server.start()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private data class Response(
        val code: Int,
        val body: String,
        val contentType: String?,
        val contentLength: String?
    )

    private fun get(
        path: String,
        headers: Map<String, String> = emptyMap(),
        method: String = "GET"
    ): Response {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
        }
        return try {
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            Response(
                code = code,
                body = stream?.use { it.readBytes() }?.toString(Charsets.UTF_8).orEmpty(),
                contentType = connection.getHeaderField("Content-Type"),
                contentLength = connection.getHeaderField("Content-Length")
            )
        } finally {
            connection.disconnect()
        }
    }


    @Test
    fun `a route answers with its own body, its own type and its own length`() {
        start(HttpRoute(claims = { it.path == "/status" }, handler = { HttpResponse.text(200, "ok") }))

        val response = get("/status")

        assertEquals(200, response.code)
        assertEquals("ok", response.body)
        assertEquals("text/plain; charset=utf-8", response.contentType)
        assertEquals("2", response.contentLength)
    }

    @Test
    fun `a path no route claims is a 404`() {
        start(HttpRoute(claims = { it.path == "/status" }, handler = { HttpResponse.text(200, "ok") }))

        val response = get("/nope")

        assertEquals(404, response.code)
        assertEquals("not found", response.body)
    }

    @Test
    fun `the first route to claim a request is the one that answers it`() {
        start(
            HttpRoute(claims = { it.path == "/twice" }, handler = { HttpResponse.text(200, "first") }),
            HttpRoute(claims = { it.path == "/twice" }, handler = { HttpResponse.text(200, "second") })
        )

        assertEquals("first", get("/twice").body)
    }

    @Test
    fun `a route sees the method, the target, the path, the query and the headers`() {
        start(
            HttpRoute(claims = { it.path == "/echo" }, handler = { request ->
                HttpResponse.text(
                    200,
                    listOf(
                        request.method,
                        request.target,
                        request.path,
                        request.query["k"].orEmpty(),
                        // Repeated names: only the first value of one is carried.
                        request.query["dup"].orEmpty(),
                        // Header names are case-insensitive, so a route may ask in any case.
                        request.header("x-toKEN").orEmpty()
                    ).joinToString("|")
                )
            })
        )

        val response = get("/echo?k=7f%20a&dup=one&dup=two", headers = mapOf("X-Token" to "secret"))

        assertEquals("GET|/echo?k=7f%20a&dup=one&dup=two|/echo|7f a|one|secret", response.body)
    }

    @Test
    fun `a route may judge anything about the request, not only its path`() {
        start(HttpRoute(claims = { it.method == "POST" }, handler = { HttpResponse.text(200, "posted") }))

        assertEquals(404, get("/anything").code)
        assertEquals("posted", get("/anything", method = "POST").body)
    }

    @Test
    fun `a request with nothing to read is not answered`() {
        start(HttpRoute(claims = { it.path == "/status" }, handler = { HttpResponse.text(200, "ok") }))

        // A connection that says nothing and then goes away: there is nothing to answer to.
        Socket("127.0.0.1", port).use { quiet -> quiet.getOutputStream().flush() }

        // And the server carries on.
        assertEquals(200, get("/status").code)
    }

    @Test
    fun `the server knows where it is, and stopping shuts the door`() {
        start()

        assertTrue("a bound server has a port", port > 0)
        assertEquals("127.0.0.1:$port", server.authority)
        assertTrue(server.isRunning)

        server.stop()

        assertFalse(server.isRunning)
        try {
            get("/status")
            fail("a stopped server should not answer")
        } catch (expected: IOException) {
            // Nothing is listening any more, which is what stop() is for.
        }
    }

    @Test
    fun `the path is the target without its query, and the target keeps it`() {
        val withQuery = HttpRequest(
            method = "GET",
            target = "/tiles/osm/1/2/3.png?x=1",
            headers = emptyMap()
        )

        // An anchored route matches the target, query and all; one that wants the path asks for it.
        assertEquals("/tiles/osm/1/2/3.png?x=1", withQuery.target)
        assertEquals("/tiles/osm/1/2/3.png", withQuery.path)
        assertEquals(mapOf("x" to "1"), withQuery.query)

        val plain = HttpRequest(method = "GET", target = "/status", headers = emptyMap())
        assertEquals("/status", plain.path)
        assertTrue(plain.query.isEmpty())
        assertNull(plain.header("X-Token"))
    }
}
