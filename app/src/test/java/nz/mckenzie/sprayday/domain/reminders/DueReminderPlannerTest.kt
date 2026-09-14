package nz.mckenzie.sprayday.domain.reminders

import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * When the operator gets told that something is due.
 *
 * Both failure modes are covered here: a reminder that repeats the same news every
 * day until it is muted, and one that nags about a track that was drawn this morning.
 */
class DueReminderPlannerTest {

    private val zone = ZoneId.of("UTC")
    private val day = 86_400_000L

    /** 2026-09-14T00:00Z, a Monday. */
    private val now = 1_789_344_000_000L

    private fun track(
        id: Long,
        status: DueStatus,
        daysUntilDue: Long? = null,
        name: String = "Home block",
        createdDaysAgo: Long = 200
    ) = AssetDueState(
        assetId = id,
        name = name,
        status = status,
        daysUntilDue = daysUntilDue,
        createdAtEpochMs = now - createdDaysAgo * day
    )

    private fun plan(vararg assets: AssetDueState, previous: ReminderState = ReminderState()) =
        DueReminderPlanner.plan(
            assets = assets.toList(),
            previous = previous,
            nowEpochMs = now,
            leadDays = 14,
            zoneId = zone
        )

    @Test
    fun `nothing due means nothing to say`() {
        val result = plan(track(1, DueStatus.NOT_DUE, daysUntilDue = 90))

        assertTrue(result.notify.isEmpty())
        assertEquals("state must not be touched when there is nothing to say", ReminderState(), result.state)
    }

    @Test
    fun `a due track is mentioned once`() {
        val first = plan(track(1, DueStatus.DUE_SOON, daysUntilDue = 5))

        assertEquals(listOf(1L), first.notify.map { it.assetId })
        assertEquals(now, first.state.notifiedAtEpochMs)
        assertEquals(mapOf(1L to DueStatus.DUE_SOON), first.state.notified)

        val second = plan(track(1, DueStatus.DUE_SOON, daysUntilDue = 5), previous = first.state)
        assertTrue("the same news must not be repeated the next day", second.notify.isEmpty())
    }

    @Test
    fun `a still-due track is mentioned again after a week`() {
        val first = plan(track(1, DueStatus.OVERDUE, daysUntilDue = -30))

        val sixDaysLater = DueReminderPlanner.plan(
            assets = listOf(track(1, DueStatus.OVERDUE, daysUntilDue = -36)),
            previous = first.state,
            nowEpochMs = now + 6 * day,
            leadDays = 14,
            zoneId = zone
        )
        assertTrue("six days is too soon to say it again", sixDaysLater.notify.isEmpty())

        val aWeekLater = DueReminderPlanner.plan(
            assets = listOf(track(1, DueStatus.OVERDUE, daysUntilDue = -37)),
            previous = first.state,
            nowEpochMs = now + 7 * day,
            leadDays = 14,
            zoneId = zone
        )
        assertEquals("a week of being overdue is worth repeating", listOf(1L), aWeekLater.notify.map { it.assetId })
    }

    @Test
    fun `a second track becoming due is news even a day later`() {
        val first = plan(track(1, DueStatus.OVERDUE, daysUntilDue = -2))

        val nextDay = DueReminderPlanner.plan(
            assets = listOf(
                track(1, DueStatus.OVERDUE, daysUntilDue = -3),
                track(2, DueStatus.DUE_SOON, daysUntilDue = 4, name = "River block")
            ),
            previous = first.state,
            nowEpochMs = now + day,
            leadDays = 14,
            zoneId = zone
        )

        assertEquals(
            "both are mentioned, because the second one has not been heard about",
            listOf(1L, 2L),
            nextDay.notify.map { it.assetId }
        )
    }

    @Test
    fun `going from due soon to overdue is worth saying again`() {
        val first = plan(track(1, DueStatus.DUE_SOON, daysUntilDue = 3))

        val later = DueReminderPlanner.plan(
            assets = listOf(track(1, DueStatus.OVERDUE, daysUntilDue = -4)),
            previous = first.state,
            nowEpochMs = now + 7 * day,
            leadDays = 14,
            zoneId = zone
        )

        assertEquals("the escalation is the news", listOf(1L), later.notify.map { it.assetId })
    }

    @Test
    fun `overdue is mentioned before due soon`() {
        val result = plan(
            track(1, DueStatus.DUE_SOON, daysUntilDue = 9, name = "Soon one"),
            track(2, DueStatus.OVERDUE, daysUntilDue = -20, name = "Late one"),
            track(3, DueStatus.OVERDUE, daysUntilDue = -3, name = "Recently late")
        )

        assertEquals(listOf("Late one", "Recently late", "Soon one"), result.notify.map { it.name })
    }

    @Test
    fun `a track drawn this morning is not nagged about`() {
        val fresh = track(1, DueStatus.NEVER_SPRAYED, createdDaysAgo = 0)

        assertTrue("nothing has been missed on a line drawn today", plan(fresh).notify.isEmpty())
        assertTrue(
            "nor while it is younger than the lead time",
            plan(track(2, DueStatus.NEVER_SPRAYED, createdDaysAgo = 13)).notify.isEmpty()
        )
    }

    @Test
    fun `a never-sprayed track from a previous season is worth mentioning`() {
        val old = track(1, DueStatus.NEVER_SPRAYED, createdDaysAgo = 14, name = "Old block")

        val result = plan(old)

        assertEquals(listOf(1L), result.notify.map { it.assetId })
    }

    @Test
    fun `spraying a track takes it off the list, and the state stays quiet`() {
        val first = plan(track(1, DueStatus.OVERDUE, daysUntilDue = -1))
        assertEquals(listOf(1L), first.notify.map { it.assetId })

        val afterwards = DueReminderPlanner.plan(
            assets = listOf(track(1, DueStatus.NOT_DUE, daysUntilDue = 119)),
            previous = first.state,
            nowEpochMs = now + day,
            leadDays = 14,
            zoneId = zone
        )

        assertTrue(afterwards.notify.isEmpty())
        assertEquals("a quiet check must not rewrite what was already said", first.state, afterwards.state)
    }

    @Test
    fun `an empty list of tracks says nothing`() {
        val result = plan()

        assertTrue(result.notify.isEmpty())
        assertEquals(ReminderState(), result.state)
    }
}
