package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.domain.recording.RecordingStatus
import nz.mckenzie.sprayday.viewmodel.RecordingRow
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a recording's row says about it.
 *
 * A recording is the evidence behind a spray, so the row has to be honest about two things
 * in particular: a session that is still running, and a session whose asset has since been
 * deleted. Saying nothing in either case makes the list read as a set of finished jobs,
 * which is exactly what it is not.
 */
class RecordingsRowTest {

    private fun row(
        status: RecordingStatus = RecordingStatus.FINISHED,
        assetId: Long? = 7L,
        assetName: String? = "Home block"
    ) = RecordingRow(
        id = 1L,
        name = "Home block 14 Sep",
        startedAtEpochMs = 0L,
        distanceM = 2350.0,
        durationMs = 750_000L,
        pointCount = 412,
        status = status,
        assetId = assetId,
        assetName = assetName
    )

    @Test
    fun `a finished recording says how far it went, for how long, and on how many fixes`() {
        val detail = recordingDetail(row())

        assertTrue("the distance, in kilometres: $detail", detail.contains("2.35 km"))
        assertTrue("the elapsed time: $detail", detail.contains("12m 30s"))
        assertTrue("and the fixes kept: $detail", detail.contains("412 points"))
    }

    @Test
    fun `a recording is described as running until it is finished`() {
        assertEquals("still recording", recordingStatus(row(status = RecordingStatus.RECORDING)))
        assertEquals("paused", recordingStatus(row(status = RecordingStatus.PAUSED)))
    }

    @Test
    fun `a finished recording has nothing left to say about its state`() {
        assertNull(recordingStatus(row()))
    }

    @Test
    fun `a recording whose asset has gone says so, rather than saying nothing`() {
        assertEquals(
            "Its asset has since been deleted",
            recordingAsset(row(assetId = 7L, assetName = null))
        )
    }

    @Test
    fun `a recording made without an asset does not claim its asset was deleted`() {
        assertEquals(
            "Not linked to an asset",
            recordingAsset(row(assetId = null, assetName = null))
        )
    }
}
