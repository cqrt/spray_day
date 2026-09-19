package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.domain.tiles.Basemap
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
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

    private lateinit var imagery: OfflineTileStore
    private lateinit var drawn: OfflineTileStore
    private lateinit var server: LocalTileServer
    private var port: Int = 0

    /**
     * Images are served out of `imagery/` and the drawn map out of `drawn/`, which is the point of
     * the source being in the path: two sources, two stores, two licences, and no way for a tile to
     * end up in the wrong one.
     */
    private fun startServer(upstream: TileFetcher? = null, osmUpstream: TileFetcher? = null) {
        imagery = OfflineTileStore(temp.root.resolve("imagery"), ".webp")
        drawn = OfflineTileStore(temp.root.resolve("drawn"), ".png")
        server = LocalTileServer(
            listOf(
                TileSource(
                    id = Basemap.LINZ_AERIAL.id,
                    store = imagery,
                    suffix = Basemap.LINZ_AERIAL.tileSuffix,
                    contentType = Basemap.LINZ_AERIAL.contentType,
                    upstream = { upstream }
                ),
                TileSource(
                    id = Basemap.OPENSTREETMAP.id,
                    store = drawn,
                    suffix = Basemap.OPENSTREETMAP.tileSuffix,
                    contentType = Basemap.OPENSTREETMAP.contentType,
                    upstream = { osmUpstream }
                )
            )
        )
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
        imagery.write(14, 1017, 660, bytes)

        val response = get("/tiles/linz-aerial/14/1017/660.webp")

        assertEquals(200, response.code)
        assertEquals(listOf<Byte>(1, 2, 3, 4, 5), response.body.toList())
        assertEquals("image/webp", response.contentType)
    }

    @Test
    fun `each source is served in its own form`() {
        drawn.write(14, 1017, 660, byteArrayOf(9))
        imagery.write(14, 1017, 660, byteArrayOf(1))

        val osm = get("/tiles/osm/14/1017/660.png")
        val aerial = get("/tiles/linz-aerial/14/1017/660.webp")

        assertEquals("image/png", osm.contentType)
        assertEquals("image/webp", aerial.contentType)
        assertEquals(listOf<Byte>(9), osm.body.toList())
        assertEquals(listOf<Byte>(1), aerial.body.toList())
    }

    @Test
    fun `a missing tile with no upstream is a 404 rather than an error`() {
        assertEquals(404, get("/tiles/linz-aerial/14/1017/660.webp").code)
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

        val first = get("/tiles/linz-aerial/15/2034/1321.webp")
        val second = get("/tiles/linz-aerial/15/2034/1321.webp")

        assertEquals(200, first.code)
        assertEquals(listOf<Byte>(9, 9, 9), first.body.toList())
        assertEquals("browsing should leave the tile on disk", 1, fetches)
        assertEquals(200, second.code)
        assertTrue(imagery.contains(15, 2034, 1321))
    }

    @Test
    fun `a tile of the drawn map is cached by the drawn map's store, not the imagery's`() = runBlocking {
        val bytes = byteArrayOf(5, 5)
        var fetches = 0
        server.stop()
        startServer(osmUpstream = TileFetcher { _, _, _ ->
            fetches++
            TileFetcher.Result.Tile(bytes)
        })

        val response = get("/tiles/osm/15/2034/1321.png")

        assertEquals(200, response.code)
        assertEquals(1, fetches)
        assertTrue("browsing leaves it in the cache", drawn.contains(15, 2034, 1321))
        assertFalse(
            "nothing but a look at it may ever put a tile in the imagery store",
            imagery.contains(15, 2034, 1321)
        )
    }

    @Test
    fun `a tile the service has no imagery for is a 404`() {
        server.stop()
        startServer(upstream = TileFetcher { _, _, _ -> TileFetcher.Result.NotFound })

        assertEquals(404, get("/tiles/linz-aerial/14/1017/660.webp").code)
    }

    @Test
    fun `an upstream failure is reported as a bad gateway`() {
        server.stop()
        startServer(upstream = TileFetcher { _, _, _ -> TileFetcher.Result.Failed("boom") })

        assertEquals(502, get("/tiles/linz-aerial/14/1017/660.webp").code)
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
        // The wrong suffix for that source: imagery is webp, the drawn map is png.
        assertEquals(404, get("/tiles/linz-aerial/14/1017/660.png").code)
        assertEquals(404, get("/tiles/osm/14/1017/660.webp").code)
        // A source nobody serves.
        assertEquals(404, get("/tiles/imagery/1/1.webp").code)
        // No tile at all.
        assertEquals(404, get("/tiles/linz-aerial/14/1017.webp").code)
    }

    @Test
    fun `the template matches what the server serves, per source`() {
        imagery.write(13, 1017, 660, byteArrayOf(7))
        drawn.write(13, 1017, 660, byteArrayOf(8))

        assertEquals(
            "http://127.0.0.1:$port/tiles/linz-aerial/{z}/{x}/{y}.webp",
            server.tileUrlTemplate(Basemap.LINZ_AERIAL.id)
        )
        assertEquals(
            "http://127.0.0.1:$port/tiles/osm/{z}/{x}/{y}.png",
            server.tileUrlTemplate(Basemap.OPENSTREETMAP.id)
        )

        // What the style is given is what the server answers, for both.
        listOf(Basemap.LINZ_AERIAL, Basemap.OPENSTREETMAP).forEach { basemap ->
            val path = server.tileUrlTemplate(basemap.id)
                .removePrefix("http://127.0.0.1:$port")
                .replace("{z}", "13").replace("{x}", "1017").replace("{y}", "660")
            assertEquals(200, get(path).code)
        }
    }

    @Test
    fun `tile paths parse only for well formed tile urls`() {
        assertEquals(
            TileRequest("linz-aerial", TileRef(14, 1017, 660), ".webp"),
            LocalTileServer.parseTilePath("/tiles/linz-aerial/14/1017/660.webp")
        )
        assertEquals(
            TileRequest("osm", TileRef(14, 1017, 660), ".png"),
            LocalTileServer.parseTilePath("/tiles/osm/14/1017/660.png")
        )
        // A source or a suffix the server does not serve is a 404 rather than a parse failure;
        // that judgement belongs to the server, which knows what it has.
        assertEquals(
            TileRequest("imagery", TileRef(14, 1017, 660), ".webp"),
            LocalTileServer.parseTilePath("/tiles/imagery/14/1017/660.webp")
        )
        assertNull(LocalTileServer.parseTilePath("/tiles/linz-aerial/14/1017/660.webp/extra"))
        assertNull(LocalTileServer.parseTilePath("/tiles/14/1017/660.webp"))
        assertNull(LocalTileServer.parseTilePath("/tiles/linz aerial/14/1017/660.webp"))
        assertNull(LocalTileServer.parseTilePath("/status"))
        assertNull(LocalTileServer.parseTilePath(""))
    }
}
