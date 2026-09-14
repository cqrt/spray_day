package nz.mckenzie.sprayday.reminders

import android.Manifest
import android.app.Notification
import android.app.NotificationManager
import android.content.Context
import android.os.Build
import androidx.room.Room
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.test.platform.app.InstrumentationRegistry
import kotlinx.coroutines.flow.first
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import nz.mckenzie.sprayday.data.ReminderStateStore
import nz.mckenzie.sprayday.data.SprayRepository
import nz.mckenzie.sprayday.data.TrackRepository
import nz.mckenzie.sprayday.data.db.SprayDayDatabase
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.reminders.ReminderState
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.time.ZoneId

/**
 * One pass of the due reminder, against a real database and the real notification
 * manager: the point of these tests is what the operator would actually see.
 */
@RunWith(AndroidJUnit4::class)
class DueReminderCheckTest {

    private lateinit var context: Context
    private lateinit var db: SprayDayDatabase
    private lateinit var tracks: TrackRepository
    private lateinit var store: ReminderStateStore

    /** Tracks how many notifications were asked for, and what they said. */
    private val posted = mutableListOf<Pair<String, String>>()

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        db = Room.inMemoryDatabaseBuilder(context, SprayDayDatabase::class.java).build()
        tracks = TrackRepository(db)
        store = ReminderStateStore(context)
        posted.clear()
        cancelPostedNotifications()
        grantNotificationPermission()
    }

    @After
    fun tearDown(): Unit = runBlocking {
        // Deliberately no db.close(): the view-model tests learned that closing a
        // database under a live observer takes the process down with it.
        cancelPostedNotifications()
        store.save(ReminderState())
    }

    private fun check(
        trackRepository: TrackRepository = tracks,
        post: (String, String) -> Boolean = { title, body -> posted += title to body; true }
    ) = DueReminderCheck(
        tracks = trackRepository,
        store = store,
        post = post,
        zoneId = ZoneId.systemDefault()
    )

    /** A track sprayed [sprayedDaysAgo] days ago, so its status is decided by that. */
    private suspend fun sprayedTrack(
        name: String,
        spawnedDaysAgo: Long,
        sprayedDaysAgo: Long?,
        intervalDays: Int = 120
    ): Long {
        val now = System.currentTimeMillis()
        val id = tracks.createTrack(
            name = name,
            geometry = listOf(GeoPoint(-41.5, 173.95), GeoPoint(-41.51, 173.96)),
            intervalDays = intervalDays,
            createdAtEpochMs = now - spawnedDaysAgo * DAY
        )
        sprayedDaysAgo?.let { days ->
            SprayRepository(db).recordSpray(trackId = id, sprayedAtEpochMs = now - days * DAY)
        }
        return id
    }

    private fun activeReminderNotifications() =
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .activeNotifications
            .filter { it.id == ReminderNotifier.NOTIFICATION_ID }

    private fun cancelPostedNotifications() {
        (context.getSystemService(Context.NOTIFICATION_SERVICE) as NotificationManager)
            .cancel(ReminderNotifier.NOTIFICATION_ID)
    }

    private fun grantNotificationPermission() {
        if (Build.VERSION.SDK_INT < Build.VERSION_CODES.TIRAMISU) return
        InstrumentationRegistry.getInstrumentation().uiAutomation
            .grantRuntimePermission(context.packageName, Manifest.permission.POST_NOTIFICATIONS)
    }

    @Test
    fun aDueTrackIsMentionedOnceAndThenLeftAlone() = runBlocking {
        sprayedTrack(name = "Home block", spawnedDaysAgo = 200, sprayedDaysAgo = 150)

        // The real notifier here, because the point is that a notification actually
        // reaches the shade rather than that one was asked for.
        val first = check(post = ReminderNotifier(context)::notify).run()

        assertEquals("the overdue track should be mentioned", 1, first.notifiedCount)
        assertTrue(first.posted)
        val shown = activeReminderNotifications()
        assertEquals(
            "the notification should be in the shade: ${first.message}",
            1,
            shown.size
        )
        assertEquals(
            "1 track is due for spraying",
            shown.single().notification.extras.getString(Notification.EXTRA_TITLE)
        )

        val second = check(post = ReminderNotifier(context)::notify).run()

        assertEquals("the same news must not be posted again", 0, second.notifiedCount)
        assertFalse(second.posted)
        assertEquals("and no second notification was left behind", 1, activeReminderNotifications().size)
    }

    @Test
    fun aTrackThatJoinsTheDueListIsMentionedAtOnce() = runBlocking {
        sprayedTrack(name = "Home block", spawnedDaysAgo = 200, sprayedDaysAgo = 150)
        check().run()

        sprayedTrack(name = "River block", spawnedDaysAgo = 200, sprayedDaysAgo = 160)
        val second = check().run()

        assertEquals("the second track is news", 2, second.notifiedCount)
        assertTrue(second.posted)
    }

    @Test
    fun nothingDuePostsNothing() = runBlocking {
        sprayedTrack(name = "Home block", spawnedDaysAgo = 200, sprayedDaysAgo = 1)

        val outcome = check().run()

        assertEquals(0, outcome.notifiedCount)
        assertFalse(outcome.posted)
        assertEquals("Nothing is due.", outcome.message)
        assertTrue("nothing should be asked for", posted.isEmpty())
    }

    @Test
    fun aTrackDrawnTodayIsNotNaggedAbout() = runBlocking {
        tracks.createTrack(
            name = "Drawn this morning",
            geometry = listOf(GeoPoint(-41.5, 173.95), GeoPoint(-41.51, 173.96)),
            createdAtEpochMs = System.currentTimeMillis()
        )

        val outcome = check().run()

        assertEquals("never sprayed, but nothing has been missed yet", 0, outcome.notifiedCount)
        assertTrue(posted.isEmpty())
    }

    @Test
    fun withNotificationsOffNothingIsPostedOrRemembered() = runBlocking {
        sprayedTrack(name = "Home block", spawnedDaysAgo = 200, sprayedDaysAgo = 150)

        // The notifier reports that the post did not appear, as it does when the
        // operator has turned notifications off for the app.
        val outcome = check(post = { _, _ -> false }).run()

        assertFalse(outcome.posted)
        assertEquals(1, outcome.notifiedCount)
        assertTrue(
            "the message should say why nothing appeared: ${outcome.message}",
            outcome.message.contains("turned off")
        )
        assertEquals(
            "nothing may be remembered, or enabling notifications later would find it already said",
            ReminderState(),
            withTimeout(5_000) { store.state.first() }
        )
    }

    private companion object {
        const val DAY = 86_400_000L
    }
}
