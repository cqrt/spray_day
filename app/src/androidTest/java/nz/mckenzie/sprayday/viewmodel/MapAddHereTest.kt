package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.Flow
import kotlinx.coroutines.flow.emptyFlow
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.flow.flowOf
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.LocationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * "Add here": a piece of infrastructure at wherever the phone is.
 *
 * The case worth testing is the one with no fix - a phone just switched on, or an
 * operator under a shelter roof. That has to say so and add nothing, rather than
 * dropping a dot somewhere invented that later looks like a record of where a trough
 * actually is.
 */
@RunWith(AndroidJUnit4::class)
class MapAddHereTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase

    /** Where the operator is standing: Wellington, for a test. */
    private val here = GeoPoint(lat = -41.2865, lng = 174.7762)

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
    }

    @After
    fun tearDown() {
        // No db.close(): the view model observes the assets table while it lives, and
        // closing a database under a live observer takes the process down with it.
    }

    private fun viewModelAt(fix: GeoPoint?) = MapViewModel(
        assetRepository = AssetRepository(db),
        settingsRepository = SettingsRepository(context),
        locationSource = FixedLocation(fix),
        dueNow = flowOf(System.currentTimeMillis())
    )

    private suspend fun assets() =
        AssetRepository(db).observeAssetsWithDue(nowProvider = flowOf(System.currentTimeMillis()))
            .first()

    @Test
    fun addingHerePutsItWhereThePhoneIs(): Unit = runBlocking {
        val viewModel = viewModelAt(here)

        viewModel.addInfrastructureHere("  Water trough  ")
        val message = withTimeout(5_000) { viewModel.message.first { it != null } }!!

        val added = assets().single().asset
        assertEquals("the name is trimmed, not stored with the typing", "Water trough", added.name)
        assertEquals(AssetKind.INFRASTRUCTURE, AssetKind.fromStorage(added.kind))
        assertEquals(
            "a shortcut for a place should make a place, not a line",
            AssetShape.POINT,
            AssetShape.fromStorage(added.shape)
        )
        assertEquals("a place has no length", 0.0, added.lengthM, 1e-9)

        val stored = AssetRepository(db).getAssetGeometry(added.id).single()
        assertEquals(here.lat, stored.lat, 1e-9)
        assertEquals(here.lng, stored.lng, 1e-9)
        assertTrue("it should say what it did: $message", message.contains("Water trough"))
    }

    @Test
    fun noFixSaysSoAndAddsNothing(): Unit = runBlocking {
        val viewModel = viewModelAt(null)

        viewModel.addInfrastructureHere("Shelter")
        val message = withTimeout(5_000) { viewModel.message.first { it != null } }!!

        assertTrue("it should say what is wrong: $message", message.contains("No location"))
        assertTrue("and nothing may be added", assets().isEmpty())
    }

    /** A phone that either knows where it is, or does not. */
    private class FixedLocation(private val fix: GeoPoint?) : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()

        override suspend fun currentLocation(): GeoPoint =
            fix ?: error("the phone has no fix")
    }
}
