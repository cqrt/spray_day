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
import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.tracking.LocationSource
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The switches that leave a layer of the work off the map.
 *
 * What matters is not the switch on the screen but what it leaves behind: the choice is stored, so
 * it is still there when the tab is opened tomorrow, and it is stored as what is **hidden** - which
 * is what makes the map an install has never touched the map it always was.
 *
 * The settings DataStore is one per device rather than one per test, so this clears the choice
 * before and after itself: a run that left a layer hidden would change the next run, and would
 * change the emulator somebody then looks at.
 */
@RunWith(AndroidJUnit4::class)
class MapLayersTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var settings: SettingsRepository

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        settings = SettingsRepository(context)
        settings.setHiddenMapLayers(emptySet())
    }

    @After
    fun tearDown() = runBlocking {
        settings.setHiddenMapLayers(emptySet())
        // And the one switch in that sheet that starts off goes back to off: a run that left the
        // place names on would change the map the next run looks at, and the emulator somebody then
        // looks at.
        settings.setPlaceNames(false)
    }

    /** A phone that will not say where it is: these tests are not about the fix. */
    private object NoLocation : LocationSource {
        override fun updates(): Flow<GeoPoint> = emptyFlow()
        override suspend fun currentLocation(): GeoPoint? = null
    }

    private fun viewModel() = MapViewModel(
        assetRepository = AssetRepository(db),
        settingsRepository = settings,
        locationSource = NoLocation,
        dueNow = flowOf(0L),
        loadGeometry = { AssetGeometry.NONE }
    )

    @Test
    fun nothingIsHiddenUntilSomebodySaysSo() = runBlocking {
        assertTrue("a stored nothing hides nothing", settings.hiddenMapLayers.first().isEmpty())

        val viewModel = viewModel()

        assertEquals(emptySet<AssetLayer>(), viewModel.hiddenLayers.first())
    }

    @Test
    fun hidingOneLayerLeavesTheOthersWhereTheyAre() = runBlocking {
        val viewModel = viewModel()

        viewModel.setLayerHidden(AssetLayer.ROADS, hidden = true)
        val one = withTimeout(5_000) { viewModel.hiddenLayers.first { it.isNotEmpty() } }
        assertEquals(setOf(AssetLayer.ROADS), one)

        viewModel.setLayerHidden(AssetLayer.OTHER_PLACES, hidden = true)
        val two = withTimeout(5_000) { viewModel.hiddenLayers.first { it.size == 2 } }
        assertEquals(setOf(AssetLayer.ROADS, AssetLayer.OTHER_PLACES), two)

        viewModel.setLayerHidden(AssetLayer.OTHER_PLACES, hidden = false)
        val back = withTimeout(5_000) { viewModel.hiddenLayers.first { it.size == 1 } }
        assertEquals("showing one layer again is not a way to show the rest", setOf(AssetLayer.ROADS), back)
    }

    @Test
    fun twoSwitchesInARowBothStaySwitched() = runBlocking {
        val viewModel = viewModel()

        // One after the other without waiting in between, which is how a screen of switches is
        // used: reading the choice out of a value the view model was holding made the second tap
        // undo the first, and this is the test that says so.
        viewModel.setLayerHidden(AssetLayer.TRACKS, hidden = true)
        viewModel.setLayerHidden(AssetLayer.FENCELINES, hidden = true)

        val both = withTimeout(5_000) { viewModel.hiddenLayers.first { it.size == 2 } }

        assertEquals(setOf(AssetLayer.TRACKS, AssetLayer.FENCELINES), both)
    }

    @Test
    fun theChoiceIsWaitingForTheNextMapThatIsDrawn() = runBlocking {
        viewModel().setLayerHidden(AssetLayer.TRACKS, hidden = true)
        withTimeout(5_000) { settings.hiddenMapLayers.first { it.isNotEmpty() } }

        // A second view model, as a second visit to the tab is. Read through the flow rather than
        // taken as it stands: a StateFlow answers with its seed until the store has been read.
        val next = viewModel()

        assertEquals(
            setOf(AssetLayer.TRACKS),
            withTimeout(5_000) { next.hiddenLayers.first { it.isNotEmpty() } }
        )
    }

    @Test
    fun showEverythingPutsBackWhatTheSwitchesTookAway() = runBlocking {
        val viewModel = viewModel()
        viewModel.setLayerHidden(AssetLayer.TRACKS, hidden = true)
        viewModel.setLayerHidden(AssetLayer.FENCELINES, hidden = true)
        withTimeout(5_000) { viewModel.hiddenLayers.first { it.size == 2 } }

        viewModel.showEveryLayer()

        withTimeout(5_000) { viewModel.hiddenLayers.first { it.isEmpty() } }
        assertTrue(settings.hiddenMapLayers.first().isEmpty())
    }

    /**
     * The one switch in that sheet that starts **off**, and the one that fetches something new.
     *
     * Everything else there is a layer of the work, which is why the app was opened; the names are
     * furniture over the operator's own ground, and their tiles come from the internet on the way
     * past - so the map draws what it already holds until somebody asks for more.
     */
    @Test
    fun thePlaceNamesAreOffUntilSomebodyAsksAndThenTheyStayOn() = runBlocking {
        val viewModel = viewModel()

        assertEquals(
            "nothing is fetched for a switch nobody has thrown",
            false,
            withTimeout(5_000) { viewModel.placeNames.first() }
        )

        viewModel.setPlaceNames(true)

        assertTrue(
            "the switch wrote it, and the map follows",
            withTimeout(5_000) { viewModel.placeNames.first { it } }
        )
        assertEquals("the choice is the phone's rather than the screen's", true, settings.placeNames.first())

        // A second view model, as a second visit to the tab is: the answer is read back rather than
        // remembered from the tap.
        assertTrue(withTimeout(5_000) { viewModel().placeNames.first { it } })
    }
}
