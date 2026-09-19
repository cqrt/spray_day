package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.domain.tiles.Basemap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.net.InetAddress
import java.net.ServerSocket

/**
 * How OpenStreetMap's answers are interpreted, and what the app says about itself when it asks.
 *
 * Driven against a throwaway socket on loopback rather than the live service, and rather than the
 * app's own tile server, because their tile usage policy is mostly about *how the request is
 * made*: it requires a `User-Agent` naming the application. The app's tile server does not keep
 * request headers, so this one does - the request is the thing worth asserting, and a stubbed
 * fetcher could not show it.
 */
class OsmTileFetcherTest {

    /** What the server was told, so a test can assert the request rather than only the result. */
    private val userAgents = mutableListOf<String>()

    private val paths = mutableListOf<String>()

    private var server: RecordingTileServer? = null

    @After
    fun tearDown() {
        server?.stop()
    }

    /** Answers every request with one canned response, recording what was asked. */
    private fun startServer(status: Int = 404, body: ByteArray = ByteArray(0)) {
        server?.stop()
        server = RecordingTileServer(status, body, userAgents, paths)
    }

    private fun fetcher() = OsmTileFetcher(
        appVersion = "0.6.16",
        urlFor = { zoom, x, y -> "http://127.0.0.1:${server!!.port}/$zoom/$x/$y.png" }
    )

    @Test
    fun `a served tile comes back as a tile`() = runBlocking {
        startServer(status = 200, body = byteArrayOf(7, 8, 9))

        val result = fetcher().fetch(14, 1017, 660)

        assertTrue("expected a tile, got $result", result is TileFetcher.Result.Tile)
        assertEquals(listOf<Byte>(7, 8, 9), (result as TileFetcher.Result.Tile).bytes.toList())
        assertEquals(listOf("/14/1017/660.png"), paths)
    }

    @Test
    fun `the app names itself, because their policy requires it`() = runBlocking {
        startServer()

        fetcher().fetch(14, 1017, 660)

        assertEquals(
            listOf("SprayDay/0.6.16 (+https://github.com/cqrt/spray_day)"),
            userAgents
        )
    }

    @Test
    fun `the user agent is built from whatever version the app is`() {
        assertEquals(
            "SprayDay/1.2.3 (+https://github.com/cqrt/spray_day)",
            sprayDayUserAgent("1.2.3")
        )
    }

    @Test
    fun `a 404 means no tile rather than a failure`() = runBlocking {
        startServer(status = 404)

        assertEquals(TileFetcher.Result.NotFound, fetcher().fetch(14, 1017, 660))
    }

    @Test
    fun `rate limiting is reported as rate limiting`() = runBlocking {
        startServer(status = 429)

        val result = fetcher().fetch(14, 1017, 660)

        assertTrue("expected a failure, got $result", result is TileFetcher.Result.Failed)
        assertTrue((result as TileFetcher.Result.Failed).message.contains("rate limiting"))
    }

    @Test
    fun `their older blocked answer is treated the same way`() = runBlocking {
        startServer(status = 418)

        val result = fetcher().fetch(14, 1017, 660)

        assertTrue("expected a failure, got $result", result is TileFetcher.Result.Failed)
        assertTrue((result as TileFetcher.Result.Failed).message.contains("rate limiting"))
    }

    @Test
    fun `a refusal names the code`() = runBlocking {
        startServer(status = 403)

        val result = fetcher().fetch(14, 1017, 660)

        assertTrue((result as TileFetcher.Result.Failed).message.contains("403"))
    }

    @Test
    fun `an empty body is a failure rather than an empty tile`() = runBlocking {
        startServer(status = 200)

        assertTrue(fetcher().fetch(14, 1017, 660) is TileFetcher.Result.Failed)
    }

    @Test
    fun `an unreachable host is a failure rather than an exception`() = runBlocking {
        // Port 1 on loopback: nothing is listening, so the connection is refused.
        val offline = OsmTileFetcher(
            urlFor = { _, _, _ -> "http://127.0.0.1:1/14/1017/660.png" },
            connectTimeoutMs = 1_000,
            readTimeoutMs = 1_000
        )

        assertTrue(offline.fetch(14, 1017, 660) is TileFetcher.Result.Failed)
    }

    @Test
    fun `the default url is their host, over https, with no key`() {
        assertEquals(
            "https://tile.openstreetmap.org/14/1017/660.png",
            Basemap.OPENSTREETMAP.tileUrl("", 14, 1017, 660)
        )
    }
}

/**
 * A socket that answers every request with one canned response, recording the request line and the
 * user agent it was sent.
 *
 * Plain sockets rather than a server library, for the same reason the app's own tile server is
 * one: it is a few lines, it is exactly the HTTP the fetcher will meet, and it needs nothing on
 * the classpath that Android's test runtime does not have.
 */
private class RecordingTileServer(
    private val status: Int,
    private val body: ByteArray,
    private val userAgents: MutableList<String>,
    private val paths: MutableList<String>
) {

    private val socket = ServerSocket(0, 4, InetAddress.getByName("127.0.0.1"))

    val port: Int get() = socket.localPort

    init {
        Thread {
            while (!socket.isClosed) {
                val client = runCatching { socket.accept() }.getOrNull() ?: break
                runCatching { answer(client) }
            }
        }.apply { isDaemon = true }.start()
    }

    private fun answer(client: java.net.Socket) {
        client.use { connection ->
            connection.soTimeout = 5_000
            val reader = connection.getInputStream().bufferedReader()
            val requestLine = reader.readLine().orEmpty()
            paths += requestLine.split(' ').getOrNull(1).orEmpty()
            while (true) {
                val line = reader.readLine() ?: break
                if (line.isEmpty()) break
                if (line.startsWith("User-Agent:", ignoreCase = true)) {
                    userAgents += line.substringAfter(':').trim()
                }
            }

            val head = "HTTP/1.1 $status ${if (status == 200) "OK" else "Error"}\r\n" +
                "Content-Type: image/png\r\n" +
                "Content-Length: ${body.size}\r\n" +
                "Connection: close\r\n\r\n"
            connection.getOutputStream().apply {
                write(head.toByteArray())
                write(body)
                flush()
            }
        }
    }

    fun stop() {
        runCatching { socket.close() }
    }
}
