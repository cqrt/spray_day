package nz.mckenzie.sprayday.ui

import nz.mckenzie.sprayday.data.db.AssetEntity
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What the edit form accepts, refuses, and explains.
 *
 * These are the decisions that would otherwise be made silently: blank means "not
 * known" for a swath width but "fix this" for a name, an interval of 0 would make
 * every asset due forever, and a blank group name takes the asset out of its group
 * rather than creating a group called "".
 */
class AssetEditsTest {

    private val asset = AssetEntity(
        id = 7L,
        name = "Home block",
        groupId = 3L,
        notes = "north end wet",
        intervalDays = 120,
        swathWidthM = 4.5,
        createdAtEpochMs = 1_700_000_000_000L,
        lastSprayedAtEpochMs = 1_710_000_000_000L,
        lengthM = 1234.5
    )

    private fun apply(
        name: String = asset.name,
        groupName: String = "Home",
        intervalDays: String = asset.intervalDays.toString(),
        swathWidthM: String = asset.swathWidthM.toString(),
        notes: String = asset.notes.orEmpty()
    ) = AssetEdits.apply(asset, name, groupName, intervalDays, swathWidthM, notes)

    private fun ok(result: AssetEditResult): AssetEditResult.Ok {
        assertTrue("expected a valid edit, got $result", result is AssetEditResult.Ok)
        return result as AssetEditResult.Ok
    }

    private fun invalid(result: AssetEditResult): String {
        assertTrue("expected a refused edit, got $result", result is AssetEditResult.Invalid)
        return (result as AssetEditResult.Invalid).message
    }

    @Test
    fun `everything kept the same changes nothing`() {
        val edited = ok(apply())

        assertEquals(asset, edited.asset)
    }

    @Test
    fun `the fields a spray round cares about can all be changed`() {
        val edited = ok(
            apply(
                name = "  Back paddock  ",
                groupName = "  Back  ",
                intervalDays = "90",
                swathWidthM = "6",
                notes = "  spray the fenceline twice  "
            )
        )

        assertEquals("Back paddock", edited.asset.name)
        assertEquals("Back", edited.groupName)
        assertEquals(90, edited.asset.intervalDays)
        assertEquals(6.0, edited.asset.swathWidthM!!, 1e-9)
        assertEquals("spray the fenceline twice", edited.asset.notes)
        // The fields the form does not show must survive the edit. The group id is one
        // of them: the form carries the name, and the repository resolves that to an id.
        assertEquals(7L, edited.asset.id)
        assertEquals(3L, edited.asset.groupId)
        assertEquals(1_700_000_000_000L, edited.asset.createdAtEpochMs)
        assertEquals(1_710_000_000_000L, edited.asset.lastSprayedAtEpochMs)
        assertEquals(1234.5, edited.asset.lengthM, 1e-9)
    }

    @Test
    fun `an asset must keep a name`() {
        assertTrue(invalid(apply(name = "   ")).contains("name"))
    }

    @Test
    fun `an interval that is not a whole number is refused with the range`() {
        assertTrue(invalid(apply(intervalDays = "every 3 months")).contains("whole number"))
        assertTrue(invalid(apply(intervalDays = "0")).contains("between 1 and"))
        assertTrue(invalid(apply(intervalDays = "-30")).contains("between 1 and"))
        assertTrue(invalid(apply(intervalDays = "3651")).contains("between 1 and"))
    }

    @Test
    fun `an interval of one day and of ten years are both allowed`() {
        assertEquals(1, ok(apply(intervalDays = "1")).asset.intervalDays)
        assertEquals(3650, ok(apply(intervalDays = "3650")).asset.intervalDays)
    }

    @Test
    fun `a blank swath width means not known rather than zero`() {
        val edited = ok(apply(swathWidthM = "   "))

        assertNull("a blank width must not become 0.0", edited.asset.swathWidthM)
    }

    @Test
    fun `a swath width typed with a comma is understood`() {
        assertEquals(4.5, ok(apply(swathWidthM = "4,5")).asset.swathWidthM!!, 1e-9)
    }

    @Test
    fun `a swath width beyond a real boom is refused`() {
        assertTrue(invalid(apply(swathWidthM = "250")).contains("between"))
        assertTrue(invalid(apply(swathWidthM = "0")).contains("number of metres"))
        assertTrue(invalid(apply(swathWidthM = "wide")).contains("number of metres"))
    }

    @Test
    fun `a blank group name means no group rather than a group called nothing`() {
        assertNull(ok(apply(groupName = "   ")).groupName)
    }

    @Test
    fun `empty notes are stored as nothing, not as an empty string`() {
        val edited = ok(apply(notes = ""))

        assertNull(edited.asset.notes)
    }
}
