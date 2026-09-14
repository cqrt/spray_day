package nz.mckenzie.sprayday.domain.reminders

import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/** The words in the reminder notification. */
class ReminderMessageTest {

    private fun track(
        name: String,
        status: DueStatus,
        daysUntilDue: Long? = null
    ) = TrackDueState(
        trackId = name.hashCode().toLong(),
        name = name,
        status = status,
        daysUntilDue = daysUntilDue,
        createdAtEpochMs = 0L
    )

    @Test
    fun `one overdue track reads as one track`() {
        val tracks = listOf(track("Home block", DueStatus.OVERDUE, daysUntilDue = -12))

        assertEquals("1 track is due for spraying", ReminderMessage.title(tracks))
        assertEquals("Home block \u2014 12 days overdue", ReminderMessage.body(tracks))
    }

    @Test
    fun `several overdue tracks are counted`() {
        val tracks = listOf(
            track("Home block", DueStatus.OVERDUE, daysUntilDue = -12),
            track("River block", DueStatus.OVERDUE, daysUntilDue = -30)
        )

        assertEquals("2 tracks are due for spraying", ReminderMessage.title(tracks))
        assertTrue(ReminderMessage.body(tracks).contains("River block \u2014 30 days overdue"))
    }

    @Test
    fun `due soon alone is not called overdue`() {
        val tracks = listOf(track("Home block", DueStatus.DUE_SOON, daysUntilDue = 3))

        assertEquals("1 track is due soon", ReminderMessage.title(tracks))
    }

    @Test
    fun `a mixture says both numbers, because they mean different things`() {
        val tracks = listOf(
            track("Home block", DueStatus.OVERDUE, daysUntilDue = -1),
            track("River block", DueStatus.DUE_SOON, daysUntilDue = 6),
            track("Hill block", DueStatus.DUE_SOON, daysUntilDue = 9)
        )

        assertEquals("1 due for spraying, 2 due soon", ReminderMessage.title(tracks))
    }

    @Test
    fun `a never-sprayed track is counted as due for spraying`() {
        val tracks = listOf(track("New block", DueStatus.NEVER_SPRAYED, daysUntilDue = null))

        assertEquals("1 track is due for spraying", ReminderMessage.title(tracks))
        assertEquals("New block \u2014 Never sprayed", ReminderMessage.body(tracks))
    }

    @Test
    fun `a long list is cut off rather than running off the screen`() {
        val tracks = (1..8).map { track("Block $it", DueStatus.OVERDUE, daysUntilDue = -it.toLong()) }

        val body = ReminderMessage.body(tracks)

        assertEquals(ReminderMessage.MAX_LINES + 1, body.lines().size)
        assertTrue(body.endsWith("and 3 more"))
        assertTrue("the worst one still leads", body.startsWith("Block 1 \u2014 1 days overdue"))
    }

    @Test
    fun `nothing due says so rather than counting zero tracks`() {
        assertEquals("No tracks due", ReminderMessage.title(emptyList()))
    }
}
