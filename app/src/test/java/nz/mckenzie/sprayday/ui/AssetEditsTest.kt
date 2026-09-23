package nz.mckenzie.sprayday.ui

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod
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
        kind: AssetKind = AssetKind.TRACK,
        method: SprayMethod = SprayMethod.UNSET,
        intervalDays: String = asset.intervalDays.toString(),
        swathWidthM: String = asset.swathWidthM.toString(),
        notes: String = asset.notes.orEmpty(),
        passesRequired: Int = asset.passesRequired,
        passSeparationM: String = asset.passSeparationM?.toString().orEmpty()
    ) = AssetEdits.apply(
        asset,
        AssetEditFields(
            name = name,
            groupName = groupName,
            kind = kind,
            method = method,
            intervalDays = intervalDays,
            swathWidthM = swathWidthM,
            notes = notes,
            passesRequired = passesRequired,
            passSeparationM = passSeparationM
        )
    )

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
    fun `the spray method is kept as chosen, and not recorded stays not recorded`() {
        assertEquals(
            "nothing may be assumed about an asset nobody has described",
            SprayMethod.UNSET,
            SprayMethod.fromStorage(ok(apply()).asset.method)
        )
        assertEquals(
            SprayMethod.KNAPSACK,
            SprayMethod.fromStorage(ok(apply(method = SprayMethod.KNAPSACK)).asset.method)
        )
    }

    @Test
    fun `choosing a method does not disturb the numbers beside it`() {
        val edited = ok(apply(method = SprayMethod.BOOM, intervalDays = "60", swathWidthM = "6"))

        assertEquals(SprayMethod.BOOM, SprayMethod.fromStorage(edited.asset.method))
        assertEquals(60, edited.asset.intervalDays)
        assertEquals(6.0, edited.asset.swathWidthM!!, 1e-9)
    }

    @Test
    fun `what an asset is is kept as chosen, and the shape comes with it`() {
        val place = ok(apply(kind = AssetKind.BENCH))
        val line = ok(apply(kind = AssetKind.FENCELINE))

        assertEquals(
            AssetKind.BENCH,
            AssetKind.fromStorage(place.asset.kind, AssetShape.fromStorage(place.asset.shape))
        )
        assertEquals(AssetShape.POINT, AssetShape.fromStorage(place.asset.shape))
        // The same field, written from the kind: there is no second answer that could disagree.
        assertEquals(AssetShape.LINE, AssetShape.fromStorage(line.asset.shape))
    }

    @Test
    fun `picking a spray method suggests its usual width`() {
        assertEquals("3", AssetEdits.swathAfterMethodChange(SprayMethod.UNSET, SprayMethod.BOOM, ""))
        assertEquals("1", AssetEdits.swathAfterMethodChange(SprayMethod.UNSET, SprayMethod.KNAPSACK, ""))
    }

    @Test
    fun `a swath width the operator typed is never overwritten`() {
        // The boom is theirs to widen or narrow, so changing method leaves it alone.
        assertEquals(
            "4.5",
            AssetEdits.swathAfterMethodChange(SprayMethod.BOOM, SprayMethod.KNAPSACK, "4.5")
        )
    }

    @Test
    fun `a width that is still the last method's default is not really theirs either`() {
        // Three metres came from "boom", so moving to a knapsack must not keep it.
        assertEquals(
            "1",
            AssetEdits.swathAfterMethodChange(SprayMethod.BOOM, SprayMethod.KNAPSACK, "3")
        )
    }

    @Test
    fun `saying the method is not recorded leaves a typed width alone`() {
        assertEquals(
            "4.5",
            AssetEdits.swathAfterMethodChange(SprayMethod.BOOM, SprayMethod.UNSET, "4.5")
        )
        assertEquals("", AssetEdits.swathAfterMethodChange(SprayMethod.BOOM, SprayMethod.UNSET, "3"))
    }

    @Test
    fun `empty notes are stored as nothing, not as an empty string`() {
        val edited = ok(apply(notes = ""))

        assertNull(edited.asset.notes)
    }

    @Test
    fun `a line that takes two passes keeps how far apart they run`() {
        val edited = ok(apply(passesRequired = 2, passSeparationM = "3"))

        assertEquals(2, edited.asset.passesRequired)
        assertEquals(3.0, edited.asset.passSeparationM!!, 1e-9)
    }

    @Test
    fun `a line that takes two passes is fine without knowing how far apart they run`() {
        // Blank is the operator saying they do not know, which the app reads as "too close to tell
        // apart": it goes by direction, and asks when that cannot tell either.
        val edited = ok(apply(passesRequired = 2, passSeparationM = ""))

        assertEquals(2, edited.asset.passesRequired)
        assertNull(edited.asset.passSeparationM)
    }

    @Test
    fun `setting a line back to one pass keeps no separation`() {
        val twoPass = ok(apply(passesRequired = 2, passSeparationM = "3"))
        val edited = ok(
            AssetEdits.apply(
                twoPass.asset,
                AssetEditFields(
                    name = twoPass.asset.name,
                    groupName = "Home",
                    kind = AssetKind.TRACK,
                    method = SprayMethod.UNSET,
                    intervalDays = "120",
                    swathWidthM = "4.5",
                    notes = "",
                    passesRequired = 1,
                    passSeparationM = "3"
                )
            )
        )

        assertEquals(1, edited.asset.passesRequired)
        assertNull("a number nothing reads is not kept", edited.asset.passSeparationM)
    }

    @Test
    fun `a separation that is not a distance is refused, worth reading`() {
        assertTrue(invalid(apply(passesRequired = 2, passSeparationM = "about a metre")).contains("metres"))
        assertTrue("zero is not a distance either", invalid(apply(passesRequired = 2, passSeparationM = "0")).contains("metres"))
        assertTrue(invalid(apply(passesRequired = 2, passSeparationM = "200")).contains("between"))
    }

    @Test
    fun `a choice of passes that is not one or two is read as the nearest real one`() {
        // The choice is between named states, so a number nobody offered is a field that has been
        // edited by hand, and the nearest real answer is the safe one.
        assertEquals(2, ok(apply(passesRequired = 7)).asset.passesRequired)
        assertEquals(1, ok(apply(passesRequired = 0)).asset.passesRequired)
    }

    @Test
    fun `the block field says which of the two things a typed name is about to do`() {
        val blocks = listOf("Estuary", "Braemar")

        assertEquals(
            "In the block \"Estuary\", whatever case it is typed in",
            "In the block \"Estuary\"",
            AssetEdits.blockHint("estuary", blocks)
        )
        assertEquals(
            "a name that is not there yet starts one, and says so",
            "Starts a new block called \"Estuary flatts\"",
            AssetEdits.blockHint("Estuary flatts", blocks)
        )
        assertEquals(
            "and an empty field explains the empty case",
            "Type a block, or leave it empty for an asset on its own",
            AssetEdits.blockHint("  ", blocks)
        )
    }

    @Test
    fun `the block field offers the blocks that match what has been typed`() {
        val blocks = listOf("Estuary", "Estuary flats", "Braemar")

        assertEquals(
            "a prefix offers the longer names, and not the one already typed",
            listOf("Estuary flats"),
            AssetEdits.blockSuggestions("estuary", blocks)
        )
        assertEquals(
            "nothing typed means nothing offered, so the field is not in the way",
            emptyList<String>(),
            AssetEdits.blockSuggestions("", blocks)
        )
        assertEquals(
            "a name nobody has is nothing to offer",
            emptyList<String>(),
            AssetEdits.blockSuggestions("Wairau", blocks)
        )
    }
}
