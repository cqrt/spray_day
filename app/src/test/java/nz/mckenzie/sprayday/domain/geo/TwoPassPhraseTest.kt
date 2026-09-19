package nz.mckenzie.sprayday.domain.geo

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * What the recorder says about a line that takes two passes.
 *
 * A pass that was walked but not recorded is the one thing the operator has to be told about, or
 * the screen looks as though the work was ignored: the traffic light does not move, and nothing
 * else on the card says why. These are the sentences that do, and the cases where saying nothing
 * is the right answer.
 */
class TwoPassPhraseTest {

    private fun result(
        doneM: Double = 0.0,
        onePassM: Double = 0.0,
        ambiguousM: Double = 0.0,
        missingM: Double = 0.0
    ) = TwoPasses.Result(
        stretches = emptyList(),
        doneM = doneM,
        onePassM = onePassM,
        ambiguousM = ambiguousM,
        missingM = missingM,
        totalM = doneM + onePassM + ambiguousM + missingM
    )

    @Test
    fun `a line nothing has been along says nothing`() {
        assertNull("the traffic light and the line already say it", TwoPassPhrase.state(result(missingM = 1_000.0)))
        assertNull(TwoPassPhrase.state(null))
    }

    @Test
    fun `a line walked once is waiting for its other pass`() {
        assertEquals(
            "One pass still to go",
            TwoPassPhrase.state(result(onePassM = 1_000.0))
        )
    }

    @Test
    fun `a line that has had both passes says so`() {
        assertEquals("Both passes done", TwoPassPhrase.state(result(doneM = 1_000.0)))
    }

    @Test
    fun `a pass that left the line wanting says so, and that nothing was recorded`() {
        assertEquals(
            "one pass still to go, so no spray was recorded yet",
            TwoPassPhrase.notRecorded(result(onePassM = 1_000.0))
        )
    }

    @Test
    fun `two passes that cannot be told apart are the same sentence`() {
        // The question is asked at the same moment, so by the time this is read the operator has
        // already answered "one side to go" - which is a pass still to go.
        assertEquals(
            "one pass still to go, so no spray was recorded yet",
            TwoPassPhrase.notRecorded(result(ambiguousM = 1_000.0))
        )
    }

    @Test
    fun `a pass that never came near the line says something else again`() {
        assertEquals(
            "the pass did not come near the line, so no spray was recorded",
            TwoPassPhrase.notRecorded(result(missingM = 1_000.0))
        )
    }
}
