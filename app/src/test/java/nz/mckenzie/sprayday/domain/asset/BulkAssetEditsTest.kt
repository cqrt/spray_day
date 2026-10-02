package nz.mckenzie.sprayday.domain.asset

import kotlinx.coroutines.runBlocking
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The same edit on several assets at once.
 *
 * One claim is worth more than all the others and most of these are about it: **nothing is written
 * until every row has been judged**. A loop of ordinary saves refuses on the fourth of six and leaves
 * three rows changed with nothing on the screen saying which, and that is the whole reason this exists.
 * So the tests that matter are the ones where something is wrong on a row that is not the first, and
 * where the answer has to be "nothing happened" rather than "three of them happened".
 */
class BulkAssetEditsTest {

    private val fields = BulkAssetEdits.Fields(
        name = "Estuary road",
        kind = "ROAD",
        method = "BOOM",
        blockName = "Estuary",
        intervalDays = "120",
        swathWidthM = "3",
        passesRequired = 1
    )

    private val line = listOf(GeoPoint(-41.5, 173.8), GeoPoint(-41.6, 173.9))

    /** Three assets as the phone holds them, and the versions a desk would have read. */
    private val rows = listOf(
        AssetEntity(id = 1L, name = "One", kind = "TRACK", intervalDays = 120, createdAtEpochMs = 1L),
        AssetEntity(id = 2L, name = "Two", kind = "TRACK", intervalDays = 120, createdAtEpochMs = 1L),
        AssetEntity(id = 3L, name = "Three", kind = "TRACK", intervalDays = 120, createdAtEpochMs = 1L)
    )

    private val versions = rows.associate { it.id to "version-${it.id}" }

    /** What was written, which is the thing every refusal above has to have left empty. */
    private val written = mutableListOf<BulkAssetEdits.AssetEdit>()

    private suspend fun apply(
        ids: List<Long> = rows.map { it.id },
        quoted: Map<Long, String> = versions,
        values: BulkAssetEdits.Fields = fields,
        present: List<AssetEntity> = rows
    ): BulkAssetEdits.Outcome {
        written.clear()
        return BulkAssetEdits.apply(
            entries = ids.map { BulkAssetEdits.Entry(id = it, version = quoted[it] ?: "") },
            fields = values,
            find = { id -> present.firstOrNull { it.id == id }?.let { BulkAssetEdits.AssetRow(it, null) } },
            paths = { listOf(line) },
            isCurrent = { entry, row, _ -> entry.version == "version-${row.asset.id}" },
            save = { written += it }
        )
    }

    @Test
    fun `every named asset is changed, and the answer counts them`() = runBlocking {
        val outcome = apply()

        assertEquals(BulkAssetEdits.Outcome.Edited(3), outcome)
        assertEquals(listOf(1L, 2L, 3L), written.map { it.after.id })
        assertTrue("and each one is the row it was, with the new fields on it",
            written.all { it.after.name == "Estuary road" && it.after.kind == "ROAD" && it.after.intervalDays == 120 })
        assertEquals("the row as it was read travels with it, so the write updates the right copy",
            "One", written.first().before.name)
        assertTrue("and the block is resolved by the write, not here", written.all { it.blockName == "Estuary" })
    }

    @Test
    fun `one asset is a bulk edit of one, not an error`() = runBlocking {
        val outcome = apply(ids = listOf(2L))

        assertEquals(BulkAssetEdits.Outcome.Edited(1), outcome)
        assertEquals(listOf(2L), written.map { it.after.id })
    }

    @Test
    fun `a field the rules will not take is refused with the row it was about`() = runBlocking {
        // A refusal has to name **which** asset stopped it: "a field is wrong" is no help at all about
        // six rows, and the operator is looking at a list rather than at one card.
        val outcome = apply(values = fields.copy(kind = "PADDOCK"))
        val refused = outcome as BulkAssetEdits.Outcome.Refused

        assertEquals(1L, refused.id)
        assertEquals("One", refused.name)
        assertEquals("The phone does not know what kind of thing \"PADDOCK\" is.", refused.message)
        assertTrue("nothing was written", written.isEmpty())
    }

    @Test
    fun `a row that is fine is still not written when a later row is not`() = runBlocking {
        // Every row but the last is perfectly good, and the answer is still that nothing happened: the
        // whole request is judged before any of it is written, which is the promise a loop cannot make.
        // A loop of ordinary saves would have written the first two and then refused the third.
        written.clear()
        val outcome = BulkAssetEdits.apply(
            entries = rows.map { BulkAssetEdits.Entry(it.id, "version-${it.id}") },
            fields = BulkAssetEdits.Fields(
                name = "Estuary road",
                kind = "ROAD",
                method = "BOOM",
                blockName = "Estuary",
                intervalDays = "soon",
                swathWidthM = "3",
                passesRequired = 1
            ),
            find = { id -> rows.firstOrNull { it.id == id }?.let { BulkAssetEdits.AssetRow(it, null) } },
            paths = { listOf(line) },
            isCurrent = { entry, row, _ -> entry.version == "version-${row.asset.id}" },
            save = { written += it }
        )

        assertEquals(
            BulkAssetEdits.Outcome.Refused(1L, "One", "Days between sprays must be a whole number"),
            outcome
        )
        assertTrue("and not one of the three was written", written.isEmpty())
    }

    @Test
    fun `a row that moved on the phone changes nothing at all`() = runBlocking {
        val outcome = apply(quoted = versions + (2L to "somebody-else's-version"))

        assertEquals(BulkAssetEdits.Outcome.Stale(2L), outcome)
        assertTrue("nothing was written", written.isEmpty())
    }

    @Test
    fun `a row with no version quoted is as stale as one that has moved`() = runBlocking {
        // A page that did not say which version it read has nothing to be held to, exactly as one
        // asset's own edit refuses a blank version - and it is the same answer.
        assertEquals(BulkAssetEdits.Outcome.Stale(2L), apply(quoted = versions - 2L))
        assertTrue(written.isEmpty())
    }

    @Test
    fun `a row the phone does not have changes nothing at all`() = runBlocking {
        val outcome = apply(present = rows.filter { it.id != 3L })

        assertEquals(BulkAssetEdits.Outcome.Missing(3L), outcome)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `the same asset named twice is refused rather than changed once`() = runBlocking {
        // Not quietly deduplicated: a body naming the same row twice is a page that has got its own
        // state wrong, and the honest answer to that is to say so.
        val outcome = apply(ids = listOf(1L, 2L, 1L))

        assertTrue("refused: $outcome", outcome is BulkAssetEdits.Outcome.Unreadable)
        assertTrue(written.isEmpty())
    }

    @Test
    fun `an edit that names no assets is refused`() = runBlocking {
        val outcome = apply(ids = emptyList())

        assertTrue("refused: $outcome", outcome is BulkAssetEdits.Outcome.Unreadable)
        assertTrue("and nothing was written", written.isEmpty())
    }

    @Test
    fun `more rows than the phone will take is refused rather than truncated`() = runBlocking {
        val many = (1..BulkAssetEdits.MAX_ASSETS + 1).map { it.toLong() }
        val outcome = apply(ids = many, present = many.map {
            AssetEntity(id = it, name = "Asset $it", createdAtEpochMs = 1L)
        })

        assertTrue("refused: $outcome", outcome is BulkAssetEdits.Outcome.Unreadable)
        assertTrue("and nothing was written", written.isEmpty())
    }

    @Test
    fun `a value the operator did not tick travels as the row's own, so the row comes back unchanged`() =
        runBlocking {
            // This is how one form is about six different assets: the page fills in, for every field it
            // did not tick, the value that row already has - and the rules, which read every field,
            // write the same row back. Nothing about the row moves but the name that was ticked.
            val rows = listOf(
                AssetEntity(id = 1L, name = "One", kind = "FENCELINE", method = "KNAPSACK", intervalDays = 90,
                    swathWidthM = 1.5, passesRequired = 2, passSeparationM = 2.0,
                    createdAtEpochMs = 1L),
                AssetEntity(id = 2L, name = "Two", kind = "FENCELINE", method = "KNAPSACK", intervalDays = 90,
                    swathWidthM = 1.5, passesRequired = 2, passSeparationM = 2.0,
                    createdAtEpochMs = 1L)
            )
            written.clear()
            val outcome = BulkAssetEdits.apply(
                entries = rows.map { BulkAssetEdits.Entry(it.id, "version-${it.id}") },
                fields = BulkAssetEdits.Fields(
                    name = "Fenceline north",
                    kind = "FENCELINE",
                    method = "KNAPSACK",
                    blockName = "",
                    intervalDays = "90",
                    swathWidthM = "1.5",
                    passesRequired = 2,
                    passSeparationM = "2.0"
                ),
                find = { id -> rows.firstOrNull { it.id == id }?.let { BulkAssetEdits.AssetRow(it, null) } },
                paths = { listOf(line) },
                isCurrent = { entry, row, _ -> entry.version == "version-${row.asset.id}" },
                save = { written += it }
            )

            assertEquals(BulkAssetEdits.Outcome.Edited(2), outcome)
            written.forEach { edit ->
                assertEquals("the ticked field moved", "Fenceline north", edit.after.name)
                assertEquals("and every other field is the one it had", edit.before.copy(name = "Fenceline north"), edit.after)
            }
        }

    @Test
    fun `the sentence about what happened counts them`() {
        assertEquals("Changed 1 asset.", BulkAssetEdits.editedMessage(1))
        assertEquals("Changed all 6 assets together.", BulkAssetEdits.editedMessage(6))
    }
}
