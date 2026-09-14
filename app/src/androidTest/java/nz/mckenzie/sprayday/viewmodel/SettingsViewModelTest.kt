package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.BuildConfig
import nz.mckenzie.sprayday.data.SettingsRepository
import nz.mckenzie.sprayday.offline.KeyCheck
import nz.mckenzie.sprayday.offline.LinzKeyProbe
import nz.mckenzie.sprayday.offline.OfflineTileStore
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Assume.assumeTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.io.File
import java.util.concurrent.atomic.AtomicReference

/**
 * Entering a LINZ key in the app.
 *
 * This is the piece that was missing: standard-access keys expire every 90 days, the
 * failure is silent, and before this screen the only fix was a new build.
 */
@RunWith(AndroidJUnit4::class)
class SettingsViewModelTest {

    private lateinit var context: Context
    private lateinit var store: OfflineTileStore

    @Before
    fun setUp() = runBlocking {
        context = ApplicationProvider.getApplicationContext()
        store = OfflineTileStore(File(context.cacheDir, "settings-test-tiles"))
        store.deleteAll()
        SettingsRepository(context).setLinzApiKey("")
    }

    @After
    fun tearDown(): Unit = runBlocking {
        SettingsRepository(context).setLinzApiKey("")
        store.deleteAll()
    }

    private fun viewModel(check: suspend (String) -> KeyCheck = { KeyCheck.Works }) = SettingsViewModel(
        settings = SettingsRepository(context),
        store = store,
        checkKey = check
    )

    @Test
    fun anEnteredKeyIsSavedAndWinsOverTheOneInTheBuild() = runBlocking {
        val settings = SettingsRepository(context)
        val viewModel = SettingsViewModel(settings, store, checkKey = { KeyCheck.Works })

        viewModel.setKeyText("entered-key-9876")
        viewModel.save()

        withTimeout(5_000) { settings.linzApiKey.first { it == "entered-key-9876" } }
        assertEquals("the entered key is the one in force", "entered-key-9876", settings.linzApiKey.first())
        assertEquals("and it is on the device", "entered-key-9876", settings.storedLinzApiKey.first())
        val label = withTimeout(5_000) { viewModel.activeKeyLabel.first { it.isNotEmpty() } }
        assertEquals("the screen shows the tail of the key, not the key", "\u20269876", label)
    }

    @Test
    fun clearingFallsBackToTheKeyInTheBuild() = runBlocking {
        val settings = SettingsRepository(context)
        val viewModel = SettingsViewModel(settings, store, checkKey = { KeyCheck.Works })
        viewModel.setKeyText("temporary-key")
        viewModel.save()
        withTimeout(5_000) { settings.linzApiKey.first { it == "temporary-key" } }

        viewModel.clearEnteredKey()

        withTimeout(5_000) { settings.storedLinzApiKey.first { it.isEmpty() } }
        assertEquals(
            "with nothing stored, the build's key is in use again",
            BuildConfig.LINZ_API_KEY,
            settings.linzApiKey.first()
        )
        val notice = withTimeout(5_000) { viewModel.message.first { it != null } }.orEmpty()
        assertTrue("the screen should say what happened: $notice", notice.contains("built into this build"))
    }

    @Test
    fun checkingAsksTheServiceAboutTheKeyInForce() = runBlocking {
        val settings = SettingsRepository(context)
        // Written from the view model's coroutines, read here: an AtomicReference keeps
        // that a real hand-off rather than a hopeful one.
        val askedWith = AtomicReference<String?>(null)
        val viewModel = SettingsViewModel(settings, store, checkKey = { key ->
            askedWith.set(key)
            KeyCheck.Works
        })

        viewModel.check()

        val state = withTimeout(5_000) {
            viewModel.check.first { it != KeyCheckState.Idle && it != KeyCheckState.Checking }
        }
        assertEquals(KeyCheckState.Worked, state)
        assertEquals("the check must use the key in force", settings.linzApiKey.first(), askedWith.get())
    }

    @Test
    fun aRejectedKeyIsReportedInTheWordsTheServiceUsed() = runBlocking {
        val expired = "LINZ rejected the key (HTTP 400): it may have expired"
        val viewModel = viewModel { KeyCheck.Failed(expired) }

        viewModel.check()

        val state = withTimeout(5_000) {
            viewModel.check.first { it is KeyCheckState.Failed }
        } as KeyCheckState.Failed
        assertTrue(
            "the operator should read why, not just \"failed\": ${state.message}",
            state.message.contains("expired")
        )
    }

    @Test
    fun aRealKeyIsAcceptedAndABogusOneIsNot() = runBlocking {
        val key = SettingsRepository(context).linzApiKey.first()
        assumeTrue("needs a LINZ Basemaps key", key.isNotBlank())

        // The real probe, so this is the same request the Check button makes.
        val probe = LinzKeyProbe()

        val good = withTimeout(30_000) { probe.check(key) }
        // A key that is being probed repeatedly gets rate limited, and 429 says nothing
        // about whether the key is valid, so wait and ask once more rather than calling
        // a good key bad.
        val confirmed = if (good is KeyCheck.Failed && good.message.contains("429")) {
            delay(10_000)
            withTimeout(30_000) { probe.check(key) }
        } else {
            good
        }
        assertEquals("the key in this build should be accepted by LINZ", KeyCheck.Works, confirmed)

        // The regression this probe design exists for: a *tile* probe called a bogus key
        // good, because LINZ's CDN serves popular tile paths regardless of the key.
        val bogus = withTimeout(30_000) { probe.check("bogus-key-0000") }
        assertTrue("a bad key must not be called good: $bogus", bogus is KeyCheck.Failed)
        assertTrue(
            "and should be named as rejected: ${(bogus as KeyCheck.Failed).message}",
            bogus.message.contains("400")
        )
    }
}
