package nz.mckenzie.sprayday.data

import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.reminders.ReminderState
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The reminder state as it is written to disk.
 *
 * The important property is the one about damage: anything unreadable must read as
 * "nothing has been said yet", which at worst repeats one notification - whereas the
 * other direction would silence reminders for a week.
 */
class ReminderStateCodecTest {

    @Test
    fun `a state survives the round trip`() {
        val state = ReminderState(
            notifiedAtEpochMs = 1_789_344_000_000L,
            notified = mapOf(7L to DueStatus.OVERDUE, 9L to DueStatus.DUE_SOON)
        )

        assertEquals(state, ReminderStateCodec.decode(ReminderStateCodec.encode(state)))
    }

    @Test
    fun `nothing said yet survives the round trip`() {
        assertEquals(ReminderState(), ReminderStateCodec.decode(ReminderStateCodec.encode(ReminderState())))
    }

    @Test
    fun `a missing or empty value reads as nothing said`() {
        assertEquals(ReminderState(), ReminderStateCodec.decode(null))
        assertEquals(ReminderState(), ReminderStateCodec.decode(""))
        assertEquals(ReminderState(), ReminderStateCodec.decode("   "))
    }

    @Test
    fun `unreadable text reads as nothing said rather than throwing`() {
        val state = ReminderStateCodec.decode("nonsense|rubbish,also-rubbish")

        assertEquals(ReminderState(), state)
        assertTrue(state.notified.isEmpty())
    }

    @Test
    fun `one bad entry does not lose the good ones`() {
        val state = ReminderStateCodec.decode("1789344000000|7=OVERDUE,8=NOT_A_STATUS,x=1,9=")

        assertEquals(1_789_344_000_000L, state.notifiedAtEpochMs)
        assertEquals(mapOf(7L to DueStatus.OVERDUE), state.notified)
    }
}
