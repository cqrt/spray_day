package nz.mckenzie.sprayday.tracking

import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the service does with a second START for a session it is already recording.
 *
 * A re-created Record screen sends one. Treating it as a fresh recording is what put
 * "0 m" and "1 point" under a coverage measured from every fix of the pass, so the
 * operator was shown two numbers about one run that disagreed - and the coverage was
 * the one telling the truth.
 */
class TrackingServiceStartTest {

    @Test
    fun `start for the session already being recorded is not a fresh recording`() {
        assertTrue(stillRecording(requestedSessionId = 7L, currentSessionId = 7L, collecting = true))
    }

    @Test
    fun `start for a different session is a fresh recording`() {
        assertFalse(stillRecording(requestedSessionId = 8L, currentSessionId = 7L, collecting = true))
    }

    @Test
    fun `start after collection has stopped resumes the session`() {
        assertFalse(
            "nothing is collecting, so the session has to be picked up again",
            stillRecording(requestedSessionId = 7L, currentSessionId = 7L, collecting = false)
        )
    }

    @Test
    fun `start after a stop is not mistaken for carrying on`() {
        assertFalse(stillRecording(requestedSessionId = 7L, currentSessionId = -1L, collecting = false))
    }
}
