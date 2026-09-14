package nz.mckenzie.sprayday.reminders

import android.content.Context
import androidx.test.core.app.ApplicationProvider
import androidx.test.ext.junit.runners.AndroidJUnit4
import androidx.work.WorkInfo
import androidx.work.WorkManager
import kotlinx.coroutines.runBlocking
import kotlinx.coroutines.withTimeout
import org.junit.After
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Before
import org.junit.Test
import org.junit.runner.RunWith
import java.util.concurrent.TimeUnit

/**
 * The background schedule behind the reminders.
 *
 * Worth testing on a device rather than trusting: a periodic request that never gets
 * enqueued, or one that gets recreated on every launch, is a reminder system that
 * silently does nothing.
 */
@RunWith(AndroidJUnit4::class)
class DueReminderSchedulerTest {

    private lateinit var context: Context

    @Before
    fun setUp() {
        context = ApplicationProvider.getApplicationContext()
        WorkManager.getInstance(context).cancelUniqueWork(DueReminderScheduler.WORK_NAME)
    }

    @After
    fun tearDown() {
        WorkManager.getInstance(context).cancelUniqueWork(DueReminderScheduler.WORK_NAME)
    }

    private fun currentWork(): List<WorkInfo> =
        WorkManager.getInstance(context)
            .getWorkInfosForUniqueWork(DueReminderScheduler.WORK_NAME)
            .get(10, TimeUnit.SECONDS)
            .toList()

    @Test
    fun enablingRemindersLeavesExactlyOnePeriodicCheck() = runBlocking {
        DueReminderScheduler.sync(context, enabled = true)
        DueReminderScheduler.sync(context, enabled = true)

        val work = withTimeout(WORK_TIMEOUT_MS) { currentWork() }

        assertEquals("repeated syncs must not pile up work", 1, work.size)
        assertTrue(
            "the check should be queued, but was ${work.map { it.state }}",
            work.single().state == WorkInfo.State.ENQUEUED
        )
    }

    @Test
    fun turningRemindersOffCancelsTheCheck() = runBlocking {
        DueReminderScheduler.sync(context, enabled = true)
        assertEquals(1, withTimeout(WORK_TIMEOUT_MS) { currentWork() }.size)

        DueReminderScheduler.sync(context, enabled = false)

        val work = withTimeout(WORK_TIMEOUT_MS) { currentWork() }
        assertTrue(
            "the work should be cancelled, but was ${work.map { it.state }}",
            work.isEmpty() || work.all { it.state == WorkInfo.State.CANCELLED }
        )
    }

    private companion object {
        const val WORK_TIMEOUT_MS = 10_000L
    }
}
