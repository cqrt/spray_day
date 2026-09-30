package nz.mckenzie.sprayday.ui.screens

import nz.mckenzie.sprayday.data.AssetWithDue
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetGrouping
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What the map's corner box says about the whole farm.
 *
 * The arithmetic itself is a block's, already tested in its own right; what this pins is the
 * wording - what is said, what is left out when it is not known, and when an area admits it covers
 * only some of the assets. The assets are built whole and put through the same mapping the screen
 * uses, so a field the mapping forgets shows up here as a figure the box gets wrong.
 */
class FarmStatsTest {

    private var nextId = 1L

    private fun asset(
        status: DueStatus,
        shape: String,
        lengthM: Double,
        swathWidthM: Double?,
        areaM2: Double = 0.0
    ) = AssetWithDue(
        asset = AssetEntity(
            id = nextId++,
            name = "Asset",
            shape = shape,
            createdAtEpochMs = 0L,
            lengthM = lengthM,
            swathWidthM = swathWidthM,
            areaM2 = areaM2
        ),
        due = DueInfo(status = status, dueDateEpochMs = null, daysUntilDue = null),
        sprayCount = 0,
        groupName = null
    )

    /** A track: so many metres of line, sprayed over a swath width somebody has said. */
    private fun line(status: DueStatus, lengthM: Double, swathWidthM: Double? = 3.0) =
        asset(status, shape = "LINE", lengthM = lengthM, swathWidthM = swathWidthM)

    /** A carpark: the metres round its boundary, and the ground its corners enclose. */
    private fun carpark(status: DueStatus, groundSqm: Double = 3_500.0) =
        asset(status, shape = "AREA", lengthM = 260.0, swathWidthM = null, areaM2 = groundSqm)

    /** A trough: a point, with no length and no ground to measure. */
    private fun spot(status: DueStatus) =
        asset(status, shape = "POINT", lengthM = 0.0, swathWidthM = null)

    private fun stats(vararg assets: AssetWithDue) =
        farmStatsLines(AssetGrouping.totals(assets.map { it.asGroupable() }))

    @Test
    fun `a farm of lines says its total length and its area as an estimate`() {
        assertEquals(
            listOf("Total length 2.35 km", "Total area about 7050 m²"),
            stats(
                line(DueStatus.OVERDUE, lengthM = 1500.0),
                line(DueStatus.NOT_DUE, lengthM = 850.0)
            )
        )
    }

    @Test
    fun `an area covering only some of the assets says so`() {
        assertEquals(
            listOf("Total length 1.00 km", "Total area about 3000 m² from 1 of them"),
            stats(
                line(DueStatus.NOT_DUE, lengthM = 1000.0),
                spot(DueStatus.NOT_DUE)
            )
        )
    }

    @Test
    fun `a farm of places says no figures at all rather than zeros`() {
        // A zero beside a real figure is the kind of number that gets added up and believed, and a
        // farm of troughs has neither a length nor an area - nothing is invented for it.
        assertEquals(emptyList<String>(), stats(spot(DueStatus.NOT_DUE)))
    }

    @Test
    fun `a farm with nothing left says the same two figures as one with everything left`() {
        assertEquals(
            listOf("Total length 1.20 km", "Total area about 3600 m²"),
            stats(line(DueStatus.NOT_DUE, lengthM = 1200.0))
        )
    }

    @Test
    fun `a ring's own ground is counted beside the estimates, as ground`() {
        assertEquals(
            listOf("Total length 1.26 km", "Total area about 6500 m²"),
            stats(
                carpark(DueStatus.NOT_DUE, groundSqm = 3_500.0),
                line(DueStatus.NOT_DUE, lengthM = 1000.0, swathWidthM = 3.0)
            )
        )
    }

    @Test
    fun `how much is left is not a figure the box says, however much of the farm is left`() {
        // Three assets' worth of driving outstanding, and the box says the farm's size and nothing
        // about what is left of it: a third line here is a fifth of the map spent repeating the
        // counts above the rule.
        assertEquals(
            listOf("Total length 4.30 km", "Total area about 1.3 ha"),
            stats(
                line(DueStatus.OVERDUE, lengthM = 2500.0),
                line(DueStatus.DUE_SOON, lengthM = 800.0),
                line(DueStatus.NEVER_SPRAYED, lengthM = 1000.0)
            )
        )
    }
}
