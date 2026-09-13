package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.runBlocking
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder

/**
 * How LINZ's responses are interpreted.
 *
 * Driven against the real local tile server so the HTTP plumbing is exercised,
 * but without depending on the live service being reachable or having imagery
 * for a particular tile.
 */
class LinzTileFetcherTest {

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

    @After
    fun tearDown() {
        // Some tests in this class need no server at all.
        if (::server.isInitialized) server.stop()
    }

    /** A fetcher that talks to the local server instead of LINZ. */
    private fun fetcher() = LinzTileFetcher(
        apiKey = "test-key",
        urlFor = { zoom, x, y -> "http://127.0.0.1:$port/tiles/$zoom/$x/$y.webp" }
    )

    @Test
    fun `a served tile comes back as a tile`() = runBlocking {
        startServer()
        store.write(14, 1017, 660, byteArrayOf(4, 5, 6))

        val result = fetcher().fetch(14, 1017, 660)

        assertTrue(result is TileFetcher.Result.Tile)
        assertEquals(listOf<Byte>(4, 5, 6), (result as TileFetcher.Result.Tile).bytes.toList())
    }

    @Test
    fun `a 404 means the service has no imagery, not a failure`() = runBlocking {
        startServer(upstream = TileFetcher { _, _, _ -> TileFetcher.Result.NotFound })

        assertEquals(TileFetcher.Result.NotFound, fetcher().fetch(14, 1017, 660))
    }

    @Test
    fun `a bad gateway is reported as a failure with the code`() = runBlocking {
        startServer(upstream = TileFetcher { _, _, _ -> TileFetcher.Result.Failed("upstream down") })

        val result = fetcher().fetch(14, 1017, 660)

        assertTrue("expected a failure, got $result", result is TileFetcher.Result.Failed)
        assertTrue((result as TileFetcher.Result.Failed).message.contains("502"))
    }

    @Test
    fun `an unreachable host is a failure rather than an exception`() = runBlocking {
        // Port 1 on loopback: nothing is listening, so the connection is refused.
        val offline = LinzTileFetcher(
            apiKey = "test-key",
            urlFor = { _, _, _ -> "http://127.0.0.1:1/tiles/14/1017/660.webp" },
            connectTimeoutMs = 1_000,
            readTimeoutMs = 1_000
        )

        val result = offline.fetch(14, 1017, 660)

        assertTrue("expected a failure, got $result", result is TileFetcher.Result.Failed)
    }

    @Test
    fun `the default url is a keyed linz aerial tile`() {
        val url = nz.mckenzie.sprayday.map.LinzBasemap.aerialTileUrl("abc123", 14, 1017, 660)

        assertEquals(
            "https://basemaps.linz.govt.nz/v1/tiles/aerial/WebMercatorQuad/14/1017/660.webp?api=abc123",
            url
        )
    }
}
