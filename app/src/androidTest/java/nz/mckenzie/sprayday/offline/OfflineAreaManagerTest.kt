package nz.mckenzie.sprayday.offline

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Exercises MapLibre's offline downloader against the live LINZ service.
 *
 * These tests need a LINZ key (they skip without one) and a network, because the
 * whole point is to prove that a real area lands on the device - the mechanism
 * cannot be verified offline by definition.
 */
@RunWith(AndroidJUnit4::class)
class OfflineAreaManagerTest {

    private lateinit var manager: OfflineAreaManager
    private val apiKey: String get() = BuildConfig.LINZ_API_KEY

    /** Roughly 2 km around Blenheim, zoom 13-14 only: a few dozen tiles. */
    private fun tinyPlan(name: String) = OfflineAreaPlan.aroundCentre(
        name = name,
        centre = GeoPoint(-41.51, 173.96),
        radiusKm = 1.0,
        minZoom = 13,
        maxZoom = 14
    )

    @Before
    fun setUp() {
        val context: Context = ApplicationProvider.getApplicationContext()
        manager = OfflineAreaManager(context)
    }

    @Test
    fun downloadsAnAreaAndReportsItAsComplete() = runBlocking {
        assumeTrue("needs a LINZ Basemaps key", apiKey.isNotBlank())

        val started = manager.startArea(tinyPlan("Test block"), apiKey)
        val finished = try {
            manager.awaitComplete(started.id, timeoutMs = 240_000)
        } finally {
            runCatching { manager.deleteArea(started.id) }
        }

        assertTrue("no resources were downloaded", finished.completedResources > 0)
        assertTrue("area never reported complete", finished.isComplete)
        assertEquals(100, finished.percent)
        assertEquals("Test block", finished.name)
        assertTrue("expected some bytes on disk", finished.completedBytes > 0)
    }

    @Test
    fun aDownloadedAreaCoversAtLeastTheAerialTiles() = runBlocking {
        assumeTrue("needs a LINZ Basemaps key", apiKey.isNotBlank())

        // ~4 x 4 km at zoom 13-15: small enough to be quick, big enough that the
        // aerial pyramid is real rather than a rounding error.
        val plan = OfflineAreaPlan.aroundCentre(
            name = "Coverage check",
            centre = GeoPoint(-41.51, 173.96),
            radiusKm = 2.0,
            minZoom = 13,
            maxZoom = 15
        )

        val started = manager.startArea(plan, apiKey)
        val finished = try {
            manager.awaitComplete(started.id, timeoutMs = 240_000)
        } finally {
            runCatching { manager.deleteArea(started.id) }
        }

        // The pack must at least cover the aerial pyramid we estimated. It will
        // actually be larger: LINZ's hosted style (the only style form MapLibre's
        // downloader accepts) also declares terrain sources, so ~3x more
        // resources arrive than the aerial tiles alone. Asserting ">= aerial
        // count" pins the property that matters - the imagery is covered.
        assertTrue(
            "pack held only ${finished.completedResources} resources for " +
                "${plan.tileCount} aerial tiles",
            finished.completedResources >= plan.tileCount
        )
        assertTrue("expected bytes on disk", finished.completedBytes > 0)
    }

    @Test
    fun aStartedAreaIsListedAsStoredOnTheDevice() = runBlocking {
        assumeTrue("needs a LINZ Basemaps key", apiKey.isNotBlank())

        val started = manager.startArea(tinyPlan("Listed block"), apiKey)
        try {
            val listed = manager.listAreas()
            val match = listed.firstOrNull { it.id == started.id }
            assertTrue("started area was not listed", match != null)
            assertEquals("Listed block", match!!.name)
        } finally {
            runCatching { manager.deleteArea(started.id) }
        }
    }

    @Test
    fun deletingAnAreaRemovesItFromTheDevice() = runBlocking {
        assumeTrue("needs a LINZ Basemaps key", apiKey.isNotBlank())

        val started = manager.startArea(tinyPlan("Doomed block"), apiKey)
        manager.deleteArea(started.id)

        val stillThere = manager.listAreas().any { it.id == started.id }
        assertTrue("area survived deletion", !stillThere)
    }
}
