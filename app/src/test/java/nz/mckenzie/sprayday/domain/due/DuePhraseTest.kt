package nz.mckenzie.sprayday.domain.due

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * The wording for a due state, shared by the track screen and the reminder
 * notification. It lives here so the two cannot drift apart.
 */
class DuePhraseTest {

    @Test
    fun `overdue counts up from the due date`() {
        assertEquals("1 days overdue", DuePhrase.of(DueStatus.OVERDUE, -1))
        assertEquals("12 days overdue", DuePhrase.of(DueStatus.OVERDUE, -12))
    }

    @Test
    fun `today and tomorrow read as words`() {
        assertEquals("Due today", DuePhrase.of(DueStatus.DUE_SOON, 0))
        assertEquals("Due tomorrow", DuePhrase.of(DueStatus.DUE_SOON, 1))
        assertEquals("Due in 9 days", DuePhrase.of(DueStatus.NOT_DUE, 9))
    }

    @Test
    fun `never sprayed is not given a date`() {
        assertEquals("Never sprayed", DuePhrase.of(DueStatus.NEVER_SPRAYED, null))
    }

    @Test
    fun `a missing day count does not crash the wording`() {
        assertEquals("Due today", DuePhrase.of(DueStatus.DUE_SOON, null))
    }
}
