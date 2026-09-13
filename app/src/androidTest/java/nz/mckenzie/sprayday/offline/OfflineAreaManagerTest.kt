package nz.mckenzie.sprayday.offline

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicInteger

/**
 * The offline download path.
 *
 * Most tests drive [OfflineAreaManager] with a fake fetcher against an in-memory
 * database and a scratch tile directory, so they are exact rather than
 * best-effort - in particular the headline property, that a download fetches the
 * planned pyramid and nothing more. One test goes to LINZ for real, because the
 * only way to prove real imagery lands is to fetch some.
 *
 * The scratch directory is deliberately under `cacheDir`, never the app's real
 * tile store: a test must not wipe offline imagery the operator downloaded.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAreaManagerTest {

    private lateinit var context: Context
    private lateinit var database: SprayDayDatabase
    private lateinit var storeDir: File
    private lateinit var store: OfflineTileStore

    /** Counts tile requests, so "no wasted requests" can be asserted directly. */
    private val fetches = AtomicInteger()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        database = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java)
            .allowMainThreadQueries()
            .build()
        storeDir = File(context.cacheDir, "offline-area-test-tiles")
        storeDir.deleteRecursively()
        store = OfflineTileStore(storeDir)
    }

    @After
    fun tearDown() {
        database.close()
        storeDir.deleteRecursively()
    }

    /** A handful of tiles at one zoom, so the tests stay quick. */
    private fun smallPlan(name: String) = OfflineAreaPlan(
        name = name,
        bounds = LatLngBounds(
            minLat = -41.52,
            minLng = 173.95,
            maxLat = -41.50,
            maxLng = 173.97
        ),
        minZoom = 14,
        maxZoom = 14
    )

    /** Manager backed by a fake service, counting every request it makes. */
    private fun manager(
        fetch: (zoom: Int, x: Int, y: Int) -> TileFetcher.Result
    ) = OfflineAreaManager(
        store = store,
        dao = database.offlineAreaDao(),
        fetcherFor = {
            TileFetcher { zoom, x, y ->
                fetches.incrementAndGet()
                fetch(zoom, x, y)
            }
        }
    )

    @Test
    fun downloadsExactlyThePlannedTilesAndNothingMore() = runBlocking {
        val plan = smallPlan("Test block")
        val manager = manager { _, _, _ -> TileFetcher.Result.Tile(ByteArray(64)) }

        val created = manager.createArea(plan)
        val finished = manager.download(created.id, apiKey = "test-key")

        assertTrue("the plan should need some tiles", plan.tileCount > 0)
        assertEquals(plan.tileCount.toInt(), finished.plannedTiles)
        assertEquals("every planned tile should be stored", plan.tileCount.toInt(), finished.storedTiles)
        assertEquals("exactly one request per tile, no waste", plan.tileCount.toInt(), fetches.get())
        assertEquals("the store should hold the plan and nothing else", plan.tileCount, store.storedTileCount())
        assertEquals(plan.tileCount, manager.summary().tiles)
        assertEquals(plan.tileCount * 64L, finished.bytes)
        assertEquals(0, finished.missingTiles)
        assertTrue(finished.isComplete)
        assertEquals(100, finished.percent)
    }

    @Test
    fun tilesLinzHasNoImageryForAreReportedRatherThanRetriedForever() = runBlocking {
        val plan = smallPlan("Edge of coverage")
        val manager = manager { _, _, _ -> TileFetcher.Result.NotFound }

        val created = manager.createArea(plan)
        val finished = manager.download(created.id, "test-key")

        // Nothing arrived, but nothing failed either: those tiles do not exist,
        // so the download is finished and says what is missing.
        assertTrue(finished.isComplete)
        assertTrue(finished.isShortButComplete)
        assertEquals(plan.tileCount.toInt(), finished.missingTiles)
        assertEquals("nothing can be stored for those tiles", 0, finished.storedTiles)
        assertEquals("a missing tile must not be retried", plan.tileCount.toInt(), fetches.get())
        assertNull("no imagery is not an error", finished.lastError)
        assertEquals(
            "the store should be empty, not holding something else",
            0L,
            store.storedTileCount()
        )
    }

    @Test
    fun aFailedDownloadRecordsWhyAndResumesWithoutRefetching() = runBlocking {
        val plan = smallPlan("Half a block")
        var serviceUp = false
        val manager = manager { _, _, _ ->
            if (serviceUp) TileFetcher.Result.Tile(ByteArray(16)) else TileFetcher.Result.Failed("no network")
        }

        val created = manager.createArea(plan)
        val stopped = manager.download(created.id, "test-key")

        assertFalse("a failed download must not look complete", stopped.isComplete)
        assertTrue("the reason should be recorded", stopped.lastError != null)
        assertEquals(0, stopped.storedTiles)

        serviceUp = true
        val resumed = manager.download(created.id, "test-key")

        assertTrue(resumed.isComplete)
        assertEquals(plan.tileCount.toInt(), resumed.storedTiles)
        assertEquals(plan.tileCount, store.storedTileCount())
        assertNull("completing clears the error", resumed.lastError)
    }

    @Test
    fun aSecondDownloadSkipsTheTilesAlreadyOnDisk() = runBlocking {
        val plan = smallPlan("Already held")
        val manager = manager { _, _, _ -> TileFetcher.Result.Tile(ByteArray(32)) }

        val created = manager.createArea(plan)
        manager.download(created.id, "test-key")
        val afterFirst = fetches.get()

        val again = manager.download(created.id, "test-key")

        assertEquals("nothing should be refetched", afterFirst, fetches.get())
        assertEquals(plan.tileCount.toInt(), again.storedTiles)
        assertEquals(plan.tileCount * 32L, again.bytes)
    }

    @Test
    fun deletingAnAreaForgetsItButKeepsTheSharedTiles() = runBlocking {
        val plan = smallPlan("Deletable")
        val manager = manager { _, _, _ -> TileFetcher.Result.Tile(ByteArray(8)) }

        val created = manager.createArea(plan)
        manager.download(created.id, "test-key")
        manager.deleteArea(created.id)

        assertTrue("the record should be gone", manager.listAreas().isEmpty())
        // Tiles are shared between areas and with ordinary map browsing, so
        // deleting a record cannot safely free them. Pricing that is clearTiles.
        assertEquals(plan.tileCount, store.storedTileCount())
    }

    @Test
    fun clearingImageryRemovesBothTheTilesAndTheRecords() = runBlocking {
        val plan = smallPlan("Clearable")
        val manager = manager { _, _, _ -> TileFetcher.Result.Tile(ByteArray(8)) }

        val created = manager.createArea(plan)
        manager.download(created.id, "test-key")

        val removed = manager.clearTiles()

        assertEquals(plan.tileCount.toInt(), removed)
        assertEquals(0, store.storedTileCount())
        assertEquals(0, manager.summary().tiles)
        assertTrue(manager.listAreas().isEmpty())
    }

    @Test
    fun anOversizedPlanIsRefusedBeforeItIsRecorded() = runBlocking {
        val manager = manager { _, _, _ -> TileFetcher.Result.Tile(ByteArray(8)) }
        // A degree of latitude and longitude across at zoom 10-18: hundreds of
        // thousands of tiles, far past what should ever be downloaded in one go.
        val everything = OfflineAreaPlan(
            name = "Too big",
            bounds = LatLngBounds(-42.0, 173.0, -41.0, 174.0),
            minZoom = 10,
            maxZoom = 18
        )
        assertTrue(
            "the test plan should exceed the limit",
            everything.tileCount > OfflineAreaManager.MAX_TILES_PER_AREA
        )

        val failure = runCatching { manager.createArea(everything) }

        assertTrue(
            "expected a refusal, got ${failure.exceptionOrNull()}",
            failure.exceptionOrNull() is IllegalArgumentException
        )
        assertTrue(
            "the refusal should say why: ${failure.exceptionOrNull()?.message}",
            failure.exceptionOrNull()?.message?.contains("limit") == true
        )
        assertTrue("a refused plan should leave no record", manager.listAreas().isEmpty())
        assertEquals(0, fetches.get())
    }

    @Test
    fun aPartlyDownloadedAreaResumesOnlyTheTilesItIsMissing() = runBlocking {
        val plan = smallPlan("Partly there")
        var serviceHealthy = false
        // Half the tiles succeed at first, so the download stops short with real
        // progress on disk rather than starting from nothing.
        val manager = manager { _, x, _ ->
            if (serviceHealthy || x % 2 == 0) {
                TileFetcher.Result.Tile(ByteArray(16))
            } else {
                TileFetcher.Result.Failed("flaky")
            }
        }

        val created = manager.createArea(plan)
        val stopped = manager.download(created.id, "test-key")

        assertFalse(stopped.isComplete)
        assertTrue("the tiles that did arrive should be kept", stopped.storedTiles > 0)
        assertEquals(
            "progress should match what is on disk, nothing more",
            stopped.storedTiles.toLong(),
            store.storedTileCount()
        )
        val fetchedSoFar = fetches.get()

        serviceHealthy = true
        val resumed = manager.download(created.id, "test-key")

        assertTrue(resumed.isComplete)
        assertEquals(plan.tileCount.toInt(), resumed.storedTiles)
        assertEquals(
            "only the missing tiles should have been fetched " +
                "(planned ${plan.tileCount}, held ${stopped.storedTiles}, " +
                "refetched ${fetches.get() - fetchedSoFar})",
            plan.tileCount.toInt() - stopped.storedTiles,
            fetches.get() - fetchedSoFar
        )
    }

    @Test
    fun aRealAreaFromLinzLandsOnDiskWithinItsOwnEstimate() = runBlocking {
        val key = BuildConfig.LINZ_API_KEY
        assumeTrue("needs a LINZ Basemaps key", key.isNotBlank())

        // ~2 km across at zoom 13-14: a couple of dozen tiles, quick to fetch.
        val plan = OfflineAreaPlan.aroundCentre(
            name = "LINZ check",
            centre = GeoPoint(-41.51, 173.96),
            radiusKm = 1.0,
            minZoom = 13,
            maxZoom = 14
        )
        val manager = OfflineAreaManager(store = store, dao = database.offlineAreaDao())

        val created = manager.createArea(plan)
        val finished = manager.download(created.id, key)

        assertEquals("the estimate should be what was recorded", plan.tileCount.toInt(), finished.plannedTiles)
        assertTrue(
            "fetched ${finished.storedTiles} tiles for a ${plan.tileCount}-tile plan",
            finished.storedTiles <= finished.plannedTiles
        )
        assertTrue(
            "only ${finished.storedTiles} of ${plan.tileCount} tiles arrived",
            finished.storedTiles >= (plan.tileCount * 9 / 10).toInt()
        )
        assertEquals(
            "the store should hold exactly what was reported",
            finished.storedTiles.toLong(),
            store.storedTileCount()
        )
        assertTrue("expected real bytes on disk", finished.bytes > 0L)

        // Left in the log on purpose: this is the check that the download fetched
        // the planned pyramid rather than several times more, which is what the
        // MapLibre downloader this replaced used to do.
        println(
            "LINZ area: planned ${plan.tileCount} tiles, stored ${finished.storedTiles}, " +
                "missing ${finished.missingTiles}, ${finished.sizeLabel} on disk " +
                "(estimate ${plan.estimatedSizeLabel})"
        )

        // The payloads are real imagery, not an error page stored under a .webp
        // name: every WebP file is a RIFF container.
        val firstTile = storeDir.walkTopDown().first { it.isFile && it.name.endsWith(OfflineTileStore.TILE_SUFFIX) }
        val bytes = firstTile.readBytes()
        assertEquals("RIFF", String(bytes, 0, 4, Charsets.US_ASCII))
        assertEquals("WEBP", String(bytes, 8, 4, Charsets.US_ASCII))
    }
}
