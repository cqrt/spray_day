package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.data.AssetRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotNull
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith

/**
 * Regression cover for the "draw screen flashes and closes" bug.
 *
 * A view model outlives its screen, so a "we saved it" flag left set made the
 * screen navigate away the instant it was opened a second time. The signal has
 * to be one-shot: raised on save, consumed once acted on.
 */
@RunWith(AndroidJUnit4::class)
class DrawAssetViewModelTest {

    private lateinit var db: SprayDayDatabase
    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
    }

    @After
    fun tearDown() = db.close()

    private fun viewModel() = DrawAssetViewModel(
        assetRepository = AssetRepository(db),
        settingsRepository = SettingsRepository(context)
    )

    @Test
    fun savingRaisesTheNavigationSignalAndConsumingClearsIt() = runBlocking {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)

        viewModel.save("First track")
        val saved = withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        assertNotNull("saving should raise the signal", saved)
        assertTrue("the signal should carry the new track id", saved!! > 0L)

        viewModel.consumeSaveResult()

        assertNull("after consuming, the screen must not navigate again", viewModel.savedAssetId.value)
    }

    @Test
    fun theSignalCanBeRaisedAgainByASubsequentSave() = runBlocking {
        val viewModel = viewModel()

        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        viewModel.save("First")
        withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        viewModel.consumeSaveResult()
        viewModel.clear()

        viewModel.addPoint(-41.5200, 173.9800)
        viewModel.addPoint(-41.5250, 173.9900)
        viewModel.save("Second")

        assertNotNull(
            "consuming one save must not disable the signal for the next",
            withTimeout(5_000) { viewModel.savedAssetId.first { it != null } }
        )
    }

    @Test
    fun theDraftStartsEmptyAndUndoAndClearWork() {
        val viewModel = viewModel()

        assertTrue("a new drawing session starts with no draft", viewModel.points.value.isEmpty())

        viewModel.addPoint(-41.5100, 173.9600)
        viewModel.addPoint(-41.5150, 173.9700)
        assertEquals(2, viewModel.points.value.size)

        viewModel.undo()
        assertEquals(1, viewModel.points.value.size)

        viewModel.clear()
        assertTrue(viewModel.points.value.isEmpty())
    }

    @Test
    fun aSinglePointIsNotSaveableAndSaysWhy() {
        val viewModel = viewModel()
        viewModel.addPoint(-41.5100, 173.9600)

        viewModel.save("Too short")

        assertNull("a track needs at least two points", viewModel.savedAssetId.value)
        assertNotNull("the operator should be told why", viewModel.message.value)
    }
}
