package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an asset's row says under its name.
 *
 * A spot is a place rather than a length, and a line that has not been drawn on yet has
 * no length either - so neither should be described as "0 m", which reads like a
 * measurement of something instead of the absence of one.
 */
class AssetListRowTest {

    private fun row(
        shape: AssetShape = AssetShape.LINE,
        lengthM: Double = 2350.0,
        status: DueStatus = DueStatus.DUE_SOON,
        daysUntilDue: Long? = 3L
    ) = AssetWithDue(
        asset = AssetEntity(
            id = 1L,
            name = "Home block",
            shape = shape.name,
            createdAtEpochMs = 0L,
            lengthM = lengthM
        ),
        due = DueInfo(status = status, dueDateEpochMs = null, daysUntilDue = daysUntilDue),
        sprayCount = 0
    )

    @Test
    fun `a drawn line says how long it is, and when it is due`() {
        val detail = assetRowDetail(row())

        assertTrue("the length comes first: $detail", detail.contains("\u00b7"))
        assertTrue("and the due wording after it: $detail", detail.endsWith("due in 3 days"))
    }

    @Test
    fun `a spot says when it is due, and nothing about a length it does not have`() {
        val detail = assetRowDetail(row(shape = AssetShape.POINT, lengthM = 0.0))

        assertEquals("due in 3 days", detail)
    }

    @Test
    fun `a line with nothing drawn on it yet is not described as zero metres`() {
        val detail = assetRowDetail(row(lengthM = 0.0))

        assertEquals("due in 3 days", detail)
    }

    @Test
    fun `a never-sprayed spot still says so`() {
        val detail = assetRowDetail(
            row(shape = AssetShape.POINT, lengthM = 0.0, status = DueStatus.NEVER_SPRAYED, daysUntilDue = null)
        )

        assertEquals("never sprayed", detail)
    }
}
