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
        method: String = "GET",
        body: String? = null
    ): Response {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            // The method first: a connection refuses to be told what it is after it has been told it
            // has something to write.
            requestMethod = method
            connectTimeout = 5_000
            readTimeout = 5_000
            headers.forEach { (name, value) -> setRequestProperty(name, value) }
            if (body != null) doOutput = true
        }
        if (body != null) {
            connection.outputStream.use { it.write(body.toByteArray(Charsets.UTF_8)) }
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

    /** A request written by hand, for the shapes a client library will not send. */
    private fun raw(request: String, half: Boolean = false): String {
        Socket("127.0.0.1", port).use { socket ->
            socket.getOutputStream().write(request.toByteArray(Charsets.UTF_8))
            socket.getOutputStream().flush()
            // Half-closing is how a request that promised more than it sent looks from here: the
            // client is still listening, and it is done talking.
            if (half) socket.shutdownOutput()
            return socket.getInputStream().readBytes().toString(Charsets.UTF_8)
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
        // Nothing carried one, so there is nothing to hand a route that asks.
        assertNull(plain.body)
    }

    @Test
    fun `a request that carries nothing has no body`() {
        start(
            HttpRoute(claims = { it.path == "/status" }, handler = { request ->
                HttpResponse.text(200, request.body ?: "no body")
            })
        )

        assertEquals("no body", get("/status").body)
    }

    @Test
    fun `a route sees the body of a request that carries one`() {
        start(
            HttpRoute(claims = { it.method == "POST" }, handler = { request ->
                HttpResponse.text(200, request.body ?: "no body")
            })
        )

        assertEquals(
            """{"kind":"TRACK"}""",
            get("/api/assets", method = "POST", body = """{"kind":"TRACK"}""").body
        )
    }

    @Test
    fun `a body is measured in bytes, so a macron does not leave it half-read`() {
        start(
            HttpRoute(claims = { it.method == "PUT" }, handler = { request ->
                HttpResponse.text(200, request.body ?: "no body")
            })
        )

        // `Ō` is two bytes and one character, which is the whole reason the declared length is
        // counted in bytes while the reading is counted in characters: a body read by character
        // count would stop one byte short of what the request said it was sending.
        val body = """{"name":"Ōkahu track","notes":"under the hill"}"""
        val bytes = body.toByteArray(Charsets.UTF_8).size
        assertTrue("this test is pointless without the extra byte", bytes > body.length)

        val response = raw(
            "PUT /api/assets/7 HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Content-Length: $bytes\r\n\r\n$body"
        )

        assertTrue("the route saw the whole body: $response", response.endsWith("\r\n\r\n$body"))
    }

    @Test
    fun `a body promised in chunks is refused, not half-read`() {
        start(HttpRoute(claims = { true }, handler = { HttpResponse.text(200, "should not be reached") }))

        val response = raw(
            "POST /api/assets HTTP/1.1\r\nHost: 127.0.0.1\r\n" +
                "Transfer-Encoding: chunked\r\n\r\n5\r\nhello\r\n0\r\n\r\n"
        )

        assertTrue("a chunked body is a 411: $response", response.startsWith("HTTP/1.1 411"))
    }

    @Test
    fun `a body longer than the cap is refused before any of it is read`() {
        start(HttpRoute(claims = { true }, handler = { HttpResponse.text(200, "should not be reached") }))

        // Nine hundred kilobytes declared and none of it sent: a server that began reading because
        // the header said so would be the one waiting, and this test would hang rather than fail.
        val response = raw(
            "POST /api/assets HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 900000\r\n\r\n"
        )

        assertTrue("an oversized body is a 413: $response", response.startsWith("HTTP/1.1 413"))
    }

    @Test
    fun `a body that stops short of its own length is a bad request`() {
        start(HttpRoute(claims = { true }, handler = { HttpResponse.text(200, "should not be reached") }))

        val response = raw(
            "PUT /api/assets/1 HTTP/1.1\r\nHost: 127.0.0.1\r\nContent-Length: 50\r\n\r\n" +
                """{"name":"K""",
            half = true
        )

        assertTrue("a body that never arrived whole is a 400: $response", response.startsWith("HTTP/1.1 400"))
    }
}
