package nz.mckenzie.sprayday.offline

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The rules the offline screen reads off an area's numbers. Percent in
 * particular is worth pinning down: reporting 100% for an area that is short
 * would be the kind of lie that only shows up in the field.
 */
class OfflineAreaTest {

    private fun area(
        planned: Int = 100,
        stored: Int = 0,
        complete: Boolean = false
    ) = OfflineArea(
        id = 1L,
        name = "Block",
        plannedTiles = planned,
        storedTiles = stored,
        bytes = 0L,
        isComplete = complete
    )

    @Test
    fun `a partial download reports its share`() {
        assertEquals(0, area(planned = 100, stored = 0).percent)
        assertEquals(25, area(planned = 100, stored = 25).percent)
        assertEquals(99, area(planned = 100, stored = 99).percent)
    }

    @Test
    fun `an incomplete area never reports 100 percent`() {
        // 304 of 304 tiles but the download was interrupted: not done yet.
        assertEquals(99, area(planned = 304, stored = 304, complete = false).percent)
        assertEquals(100, area(planned = 304, stored = 304, complete = true).percent)
    }

    @Test
    fun `missing tiles are what the plan needs that is not on disk`() {
        assertEquals(0, area(planned = 304, stored = 304, complete = true).missingTiles)
        assertEquals(12, area(planned = 304, stored = 292, complete = true).missingTiles)
        // Never negative, even if the count somehow overshoots the plan.
        assertEquals(0, area(planned = 10, stored = 12).missingTiles)
    }

    @Test
    fun `a complete area with a shortfall is flagged but not treated as broken`() {
        assertFalse(area(planned = 304, stored = 304, complete = true).isShortButComplete)
        assertTrue(area(planned = 304, stored = 292, complete = true).isShortButComplete)
        assertFalse(area(planned = 304, stored = 292, complete = false).isShortButComplete)
    }

    @Test
    fun `an empty plan does not divide by zero`() {
        assertEquals(0, area(planned = 0, stored = 0).percent)
        assertEquals(0, area(planned = 0, stored = 0).missingTiles)
    }

    @Test
    fun `sizes are formatted for the screen`() {
        assertEquals("68.4 MB", area().copy(bytes = 71_720_000L).sizeLabel)
        assertEquals("512 B", area().copy(bytes = 512L).sizeLabel)
    }
}
