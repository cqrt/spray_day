package nz.mckenzie.sprayday.viewmodel

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import kotlinx.coroutines.delay
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.launch
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
 *
 * Every wait here is on a *specific* value rather than on "something non-null", and
 * the timeouts are generous, because DataStore round trips on a CI emulator are
 * slower than on a desk - an earlier version of these tests read a stale message and
 * called it a failure.
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
    fun theKeyInForceIsTheBuildsUntilSomethingElseIsStored() {
        val viewModel = viewModel()

        // Known synchronously, so a slow device shows the right key immediately rather
        // than flashing "no key set yet" and correcting itself.
        assertEquals(
            if (BuildConfig.LINZ_API_KEY.isNotBlank()) KeySource.BUILT_IN else KeySource.NONE,
            viewModel.keySource.value
        )
    }

    /**
     * Polls [read] until it gives [expected], then returns it.
     *
     * Polling rather than `first { }` so a failure can report what was actually seen:
     * a bare timeout says nothing about whether the write never happened, the wrong
     * value was written, or a flow simply stayed on its initial value.
     */
    private suspend fun <T> awaitValue(what: String, expected: T, read: suspend () -> T): T {
        var seen: T = read()
        val deadline = System.currentTimeMillis() + TIMEOUT_MS
        while (seen != expected && System.currentTimeMillis() < deadline) {
            delay(100)
            seen = read()
        }
        assertEquals(what, expected, seen)
        return seen
    }

    // `: Unit` throughout: JUnit4 only runs methods that return void, and a
    // `= runBlocking { ... }` whose last statement has a value does not. Two of these
    // were not void when first written, and the run failed as "invalid test class"
    // rather than as a test failure.

    @Test
    fun aKeyBeingTypedIsNotWipedByTheStoredOneArrivingLate(): Unit = runBlocking {
        SettingsRepository(context).setLinzApiKey("stored-earlier")
        // A tile in the store, so the summary the same coroutine publishes tells us when
        // the seeding read has finished - without sleeping and hoping.
        store.write(12, 1, 1, ByteArray(10))

        val viewModel = viewModel()
        // Typed before the read lands, as happens on a slow device.
        viewModel.setKeyText("half-typed")

        withTimeout(TIMEOUT_MS) { viewModel.storedTiles.first { it.tiles == 1L } }
        assertEquals(
            "the setting being read must not overwrite what is being typed",
            "half-typed",
            viewModel.keyText.value
        )
    }

    @Test
    fun anEnteredKeyIsSavedAndWinsOverTheOneInTheBuild(): Unit = runBlocking {
        val settings = SettingsRepository(context)
        val viewModel = SettingsViewModel(settings, store, checkKey = { KeyCheck.Works })
        // Subscribe, so the WhileSubscribed upstream that feeds the label is running
        // rather than starting on demand mid-assertion.
        val label = launch { viewModel.activeKeyLabel.collect {} }

        try {
            viewModel.setKeyText("entered-key-9876")
            viewModel.save()

            awaitValue("the entered key should be the one in force", "entered-key-9876") {
                settings.linzApiKey.first()
            }
            awaitValue("the entered key should be on the device", "entered-key-9876") {
                settings.storedLinzApiKey.first()
            }
            awaitValue("the screen should show the entered key's tail", "\u20269876") {
                viewModel.activeKeyLabel.value
            }
        } finally {
            label.cancel()
        }
    }

    @Test
    fun clearingFallsBackToTheKeyInTheBuild(): Unit = runBlocking {
        val settings = SettingsRepository(context)
        val viewModel = SettingsViewModel(settings, store, checkKey = { KeyCheck.Works })
        viewModel.setKeyText("temporary-key")
        viewModel.save()
        withTimeout(TIMEOUT_MS) { settings.linzApiKey.first { it == "temporary-key" } }

        viewModel.clearEnteredKey()

        awaitValue("clearing should empty the stored key", "") {
            settings.storedLinzApiKey.first()
        }
        assertEquals(
            "with nothing stored, the build's key is in use again",
            BuildConfig.LINZ_API_KEY,
            settings.linzApiKey.first()
        )
        // Waited for by content: "a message exists" would have matched the "Saved"
        // message the previous step left behind.
        awaitValue("the screen should say what happened", true) {
            viewModel.message.value?.contains("built into this build") == true
        }
    }

    @Test
    fun checkingAsksTheServiceAboutTheKeyInForce(): Unit = runBlocking {
        val settings = SettingsRepository(context)
        // Written from the view model's coroutines, read here: an AtomicReference keeps
        // that a real hand-off rather than a hopeful one.
        val askedWith = AtomicReference<String?>(null)
        val viewModel = SettingsViewModel(settings, store, checkKey = { key ->
            askedWith.set(key)
            KeyCheck.Works
        })

        viewModel.check()

        val state = withTimeout(TIMEOUT_MS) {
            viewModel.check.first { it != KeyCheckState.Idle && it != KeyCheckState.Checking }
        }
        assertEquals(KeyCheckState.Worked, state)
        assertEquals("the check must use the key in force", settings.linzApiKey.first(), askedWith.get())
    }

    @Test
    fun aRejectedKeyIsReportedInTheWordsTheServiceUsed(): Unit = runBlocking {
        val expired = "LINZ rejected the key (HTTP 400): it may have expired"
        val viewModel = viewModel { KeyCheck.Failed(expired) }

        viewModel.check()

        val state = withTimeout(TIMEOUT_MS) {
            viewModel.check.first { it is KeyCheckState.Failed }
        } as KeyCheckState.Failed
        assertTrue(
            "the operator should read why, not just \"failed\": ${state.message}",
            state.message.contains("expired")
        )
    }

    @Test
    fun aRealKeyIsAcceptedAndABogusOneIsNot(): Unit = runBlocking {
        val key = SettingsRepository(context).linzApiKey.first()
        assumeTrue("needs a LINZ Basemaps key", key.isNotBlank())

        // The real probe, so this is the same request the Check button makes.
        val probe = LinzKeyProbe()

        val good = withTimeout(NETWORK_TIMEOUT_MS) { probe.check(key) }
        // A key that is being probed repeatedly gets rate limited, and 429 says nothing
        // about whether the key is valid, so wait and ask once more rather than calling
        // a good key bad.
        val confirmed = if (good is KeyCheck.Failed && good.message.contains("429")) {
            delay(10_000)
            withTimeout(NETWORK_TIMEOUT_MS) { probe.check(key) }
        } else {
            good
        }
        assertEquals("the key in this build should be accepted by LINZ", KeyCheck.Works, confirmed)

        // The regression this probe design exists for: a *tile* probe called a bogus key
        // good, because LINZ's CDN serves popular tile paths regardless of the key.
        val bogus = withTimeout(NETWORK_TIMEOUT_MS) { probe.check("bogus-key-0000") }
        assertTrue("a bad key must not be called good: $bogus", bogus is KeyCheck.Failed)
        if (bogus is KeyCheck.Failed && bogus.message.contains("429")) {
            // Rate limited: that is about us, not about the key, so there is nothing to
            // assert here beyond the service being reachable at all.
            return@runBlocking
        }
        assertTrue(
            "and should be named as rejected: ${(bogus as KeyCheck.Failed).message}",
            bogus.message.contains("400")
        )
    }

    private companion object {
        /** DataStore round trips on a CI emulator are not instant. */
        const val TIMEOUT_MS = 20_000L

        /** The live service, which is slower still. */
        const val NETWORK_TIMEOUT_MS = 30_000L
    }
}
