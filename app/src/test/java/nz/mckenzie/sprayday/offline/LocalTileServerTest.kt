package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.net.HttpURLConnection
import java.net.URL

/**
 * Exercises the tile server over a real loopback socket, which is the point: the
 * map talks HTTP to it, so testing it through anything else would not prove the
 * thing that matters.
 */
class LocalTileServerTest {

    @get:Rule
    val temp = TemporaryFolder()

    private lateinit var store: OfflineTileStore
    private lateinit var server: LocalTileServer
    private var port: Int = 0

    private fun startServer(upstream: TileFetcher? = null) {
        store = OfflineTileStore(temp.root)
        server = LocalTileServer(store, upstreamProvider = { upstream })
        port = server.start()
    }

    @Before
    fun setUp() {
        startServer()
    }

    @After
    fun tearDown() {
        if (::server.isInitialized) server.stop()
    }

    private data class Response(val code: Int, val body: ByteArray, val contentType: String?)

    private fun get(path: String): Response {
        val connection = (URL("http://127.0.0.1:$port$path").openConnection() as HttpURLConnection).apply {
            connectTimeout = 5_000
            readTimeout = 5_000
        }
        return try {
            val code = connection.responseCode
            val stream = if (code == 200) connection.inputStream else connection.errorStream
            Response(
                code = code,
                body = stream?.use { it.readBytes() } ?: ByteArray(0),
                contentType = connection.getHeaderField("Content-Type")
            )
        } finally {
            connection.disconnect()
        }
    }

    @Test
    fun `a stored tile is served from disk`() {
        val bytes = byteArrayOf(1, 2, 3, 4, 5)
        store.write(14, 1017, 660, bytes)

        val response = get("/tiles/14/1017/660.webp")

        assertEquals(200, response.code)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), response.body.toList())
        assertEquals("image/webp", response.contentType)
    }

    @Test
    fun `a missing tile with no upstream is a 404 rather than an error`() {
        assertEquals(404, get("/tiles/14/1017/660.webp").code)
    }

    @Test
    fun `a tile fetched through the server is stored on the way past`() = runBlocking {
        val bytes = byteArrayOf(9, 9, 9)
        var fetches = 0
        server.stop()
        startServer(upstream = TileFetcher { _, _, _ ->
            fetches++
            TileFetcher.Result.Tile(bytes)
        })

        val first = get("/tiles/15/2034/1321.webp")
        val second = get("/tiles/15/2034/1321.webp")

        assertEquals(200, first.code)
        assertEquals(listOf<Byte>(9, 9, 9), first.body.toList())
        assertEquals("browsing should leave the tile on disk", 1, fetches)
        assertEquals(200, second.code)
        assertTrue(store.contains(15, 2034, 1321))
    }

    @Test
    fun `a tile the service has no imagery for is a 404`() {
        server.stop()
        startServer(upstream = TileFetcher { _, _, _ -> TileFetcher.Result.NotFound })

        assertEquals(404, get("/tiles/14/1017/660.webp").code)
    }

    @Test
    fun `an upstream failure is reported as a bad gateway`() {
        server.stop()
        startServer(upstream = TileFetcher { _, _, _ -> TileFetcher.Result.Failed("boom") })

        assertEquals(502, get("/tiles/14/1017/660.webp").code)
    }

    @Test
    fun `status reports that the server is alive`() {
        val response = get(LocalTileServer.STATUS_PATH)

        assertEquals(200, response.code)
        assertEquals("ok", response.body.toString(Charsets.UTF_8))
    }

    @Test
    fun `paths that are not tiles are rejected`() {
        assertEquals(404, get("/").code)
        assertEquals(404, get("/tiles/14/1017/660.png").code)
        assertEquals(404, get("/tiles/aa/1/1.webp").code)
        assertEquals(404, get("/tiles/14/1017.webp").code)
    }

    @Test
    fun `the template matches what the server serves`() {
        store.write(13, 1017, 660, byteArrayOf(7))

        assertEquals(
            "http://127.0.0.1:$port/tiles/{z}/{x}/{y}.webp",
            server.tileUrlTemplate()
        )
        val path = server.tileUrlTemplate()
            .removePrefix("http://127.0.0.1:$port")
            .replace("{z}", "13").replace("{x}", "1017").replace("{y}", "660")
        assertEquals(200, get(path).code)
    }

    @Test
    fun `tile paths parse only for well formed tile urls`() {
        assertEquals(TileRef(14, 1017, 660), LocalTileServer.parseTilePath("/tiles/14/1017/660.webp"))
        assertNull(LocalTileServer.parseTilePath("/tiles/14/1017/660.png"))
        assertNull(LocalTileServer.parseTilePath("/tiles/14/1017/660.webp/extra"))
        assertNull(LocalTileServer.parseTilePath("/status"))
        assertNull(LocalTileServer.parseTilePath(""))
    }
}
