package nz.mckenzie.sprayday.offline

import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.IOException
import java.util.concurrent.atomic.AtomicInteger

class TileDownloaderTest {

    @get:Rule
    val temp = TemporaryFolder()

    /** Small area at a single zoom: a handful of tiles, so tests stay fast. */
    private val bounds = LatLngBounds(minLat = -41.52, minLng = 173.95, maxLat = -41.50, maxLng = 173.97)

    private fun store() = OfflineTileStore(temp.root)

    /** Pacing is irrelevant to behaviour here, so the rate is set very high. */
    private fun downloader(
        store: OfflineTileStore,
        fetcher: TileFetcher,
        concurrency: Int = 4,
        retries: Int = 2,
        maxTiles: Int = 12_000
    ) = TileDownloader(
        store = store,
        fetcher = fetcher,
        concurrency = concurrency,
        maxRequestsPerMinute = 60_000,
        retries = retries,
        maxTiles = maxTiles
    )

    @Test
    fun `downloads exactly the planned pyramid and nothing more`() = runBlocking {
        val store = store()
        // Atomic because the fake fetcher is called from several worker threads:
        // a plain counter loses updates, which is a bug in the test, not the code.
        val fetches = AtomicInteger()
        val downloader = downloader(store, TileFetcher { _, _, _ ->
            fetches.incrementAndGet()
            TileFetcher.Result.Tile(ByteArray(50))
        })

        val progress = downloader.download(bounds, 14, 14)
        val planned = downloader.plannedTiles(bounds, 14, 14)

        assertTrue("the area should need some tiles", planned.isNotEmpty())
        assertEquals(planned.size, progress.total)
        assertEquals(planned.size, progress.stored)
        assertEquals(planned.size, fetches.get())
        assertEquals(100, progress.percent)
        assertEquals((planned.size * 50).toLong(), progress.bytes)
        planned.forEach { assertTrue("missing ${it.zoom}/${it.x}/${it.y}", store.contains(it.zoom, it.x, it.y)) }
    }

    @Test
    fun `an interrupted download resumes instead of refetching`() = runBlocking {
        val store = store()
        val fetches = AtomicInteger()
        val downloader = downloader(store, TileFetcher { _, _, _ ->
            fetches.incrementAndGet()
            TileFetcher.Result.Tile(ByteArray(20))
        })

        val first = downloader.download(bounds, 14, 14)
        val afterFirst = fetches.get()
        val second = downloader.download(bounds, 14, 14)

        assertEquals(afterFirst, fetches.get())
        assertEquals(first.stored, second.stored)
        assertEquals(second.total, second.stored)
        assertEquals(100, second.percent)
    }

    @Test
    fun `tiles the service has no imagery for are counted separately from failures`() = runBlocking {
        val store = store()
        val downloader = downloader(store, TileFetcher { zoom, _, _ ->
            if (zoom == 15) TileFetcher.Result.NotFound else TileFetcher.Result.Tile(ByteArray(10))
        })

        val progress = downloader.download(bounds, 14, 15)

        assertEquals(0, progress.failed)
        assertTrue("zoom 15 should be reported unavailable", progress.unavailable > 0)
        assertTrue(progress.stored > 0)
        assertTrue(progress.percent < 100)
    }

    @Test
    fun `a transient failure is retried and succeeds`() = runBlocking {
        val store = store()
        var attempts = 0
        val downloader = downloader(
            store = store,
            fetcher = TileFetcher { _, _, _ ->
                attempts++
                if (attempts == 1) throw IOException("temporary")
                TileFetcher.Result.Tile(ByteArray(10))
            },
            concurrency = 1,
            retries = 2
        )

        val progress = downloader.download(bounds, 14, 14)

        assertEquals(0, progress.failed)
        assertTrue("expected a retry", attempts > 1)
        assertEquals(progress.total, progress.stored)
    }

    @Test
    fun `a permanent failure is retried the configured number of times then counted`() = runBlocking {
        val store = store()
        var attempts = 0
        val downloader = downloader(
            store = store,
            fetcher = TileFetcher { _, _, _ ->
                attempts++
                throw IOException("nope")
            },
            concurrency = 1,
            retries = 1
        )

        val progress = downloader.download(bounds, 14, 14)

        assertEquals(progress.total, progress.failed)
        assertEquals(progress.total * 2, attempts)
        assertEquals(0, progress.stored)
    }

    @Test
    fun `an oversized plan is refused rather than run away`() = runBlocking {
        val downloader = downloader(store(), TileFetcher { _, _, _ -> TileFetcher.Result.NotFound }, maxTiles = 1)

        val failure = runCatching { downloader.download(bounds, 18, 18) }

        assertTrue(failure.exceptionOrNull() is IllegalArgumentException)
        assertTrue(failure.exceptionOrNull()?.message?.contains("limit") == true)
    }

    @Test
    fun `progress is reported as the download proceeds and ends complete`() = runBlocking {
        val store = store()
        val downloader = downloader(store, TileFetcher { _, _, _ -> TileFetcher.Result.Tile(ByteArray(5)) })

        val reports = mutableListOf<TileDownloader.Progress>()
        val final = downloader.download(bounds, 14, 14) { reports += it }

        assertTrue("expected progress reports", reports.isNotEmpty())
        assertEquals(final, reports.last())
        assertEquals(100, reports.last().percent)
    }

    @Test
    fun `an inverted zoom range plans nothing`() {
        val downloader = downloader(store(), TileFetcher { _, _, _ -> TileFetcher.Result.NotFound })

        assertTrue(downloader.plannedTiles(bounds, 16, 12).isEmpty())
    }
}
