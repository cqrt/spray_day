package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The asset list's kind chips: which rows they leave on the screen.
 *
 * `AssetKindFilterTest` pins the rule. This pins the wiring - a filter asked the wrong question, or
 * one that hides a row the operator can still see on the map, is the kind of bug that is taken for a
 * lost track.
 */
@RunWith(AndroidJUnit4::class)
class AssetListViewModelTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var repository: AssetRepository
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        repository = AssetRepository(db)
    }

    @After
    fun tearDown() = db.close()

    private fun viewModel() = AssetListViewModel(assetRepository = repository, context = context)

    /** The names the list would draw, waited for rather than slept on. */
    private suspend fun namesShown(viewModel: AssetListViewModel, count: Int): List<String> =
        withTimeout(5_000) { viewModel.shown.first { it.size == count } }.map { it.asset.name }

    @Test
    fun everythingIsShownUntilAChipIsTapped() = runBlocking {
        givenABuilding("Woolshed")
        givenARoad("Estuary road")

        assertEquals(listOf("Estuary road", "Woolshed"), namesShown(viewModel(), 2))
    }

    @Test
    fun aChipShowsThatKindAloneAndItsOwnChipAgainShowsEverything() = runBlocking {
        givenABuilding("Woolshed")
        givenARoad("Estuary road")
        val viewModel = viewModel()
        namesShown(viewModel, 2)

        viewModel.showOnly(AssetKind.BUILDING)
        assertEquals(listOf("Woolshed"), namesShown(viewModel, 1))

        viewModel.showOnly(AssetKind.BUILDING)
        assertEquals("the chip already showing is the way back out", 2, namesShown(viewModel, 2).size)

        viewModel.showOnly(AssetKind.SIGN)
        assertEquals("and a kind with nothing in it shows nothing", 0, namesShown(viewModel, 0).size)
    }

    @Test
    fun theAllChipClearsWhateverWasShowing() = runBlocking {
        givenABuilding("Woolshed")
        givenARoad("Estuary road")
        val viewModel = viewModel()
        viewModel.showOnly(AssetKind.BUILDING)
        namesShown(viewModel, 1)

        viewModel.showOnly(null)

        assertEquals(2, namesShown(viewModel, 2).size)
    }

    @Test
    fun aTrackDrawnBeforeTheKindsExistedIsFoundUnderWhatItIs() = runBlocking {
        val id = repository.createAsset(
            name = "Home fenceline",
            geometry = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.5, 173.81)),
            kind = AssetKind.FENCELINE,
            shape = AssetShape.LINE
        )
        // Stored the way every build before v0.6.40 stored a fenceline, which is the only way a row
        // like this can come to exist - so this is the real thing, not a trick.
        val stored = db.assetDao().getAsset(id)!!
        db.assetDao().update(stored.copy(kind = "INFRASTRUCTURE"))
        givenARoad("Estuary road")

        val viewModel = viewModel()
        namesShown(viewModel, 2)

        viewModel.showOnly(AssetKind.FENCELINE)
        assertEquals("found under what it is", listOf("Home fenceline"), namesShown(viewModel, 1))

        viewModel.showOnly(AssetKind.OTHER_PLACE)
        assertEquals("and not under a word it never had", 0, namesShown(viewModel, 0).size)
    }

    private suspend fun givenABuilding(name: String) {
        repository.createAsset(
            name = name,
            geometry = listOf(GeoPoint(-41.5, 173.8)),
            kind = AssetKind.BUILDING,
            shape = AssetShape.POINT
        )
    }

    private suspend fun givenARoad(name: String) {
        repository.createAsset(
            name = name,
            geometry = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.5, 173.82)),
            kind = AssetKind.ROAD,
            shape = AssetShape.LINE
        )
    }
}
