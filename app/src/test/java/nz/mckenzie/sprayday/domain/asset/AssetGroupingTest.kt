package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a block comes to while it is collapsed.
 *
 * Two of these are decisions rather than arithmetic and are the reason the file exists: the
 * share left is counted in assets (so a block whose only due asset has no length cannot
 * report "0% left" beside a line saying something is left), and the colour is the most urgent
 * asset rather than an average (one overdue line is not made up for by four that are not due).
 */
class AssetGroupingTest {

    private fun line(status: DueStatus, lengthM: Double = 1000.0, swathWidthM: Double? = 3.0) =
        GroupableAsset(status = status, lengthM = lengthM, swathWidthM = swathWidthM)

    private fun twoPassLine(status: DueStatus, lengthM: Double = 1000.0, swathWidthM: Double = 1.5) =
        GroupableAsset(
            status = status,
            lengthM = lengthM,
            swathWidthM = swathWidthM,
            passesRequired = 2
        )

    private fun spot(status: DueStatus) =
        GroupableAsset(status = status, lengthM = 0.0, swathWidthM = null)

    /** A carpark: the metres round its boundary, and the ground it encloses. */
    private fun carpark(status: DueStatus, groundSqm: Double = 3_500.0, lengthM: Double = 260.0) =
        GroupableAsset(status = status, lengthM = lengthM, swathWidthM = null, groundSqm = groundSqm)

    @Test
    fun `a block counts a carpark's measured ground, and not an estimate of it`() {
        // A carpark's ground comes off its own corners, so it is added to the tile exactly - beside a
        // line's, which is still an estimate from a swath width. Both belong in one total, because a
        // tile is one figure; what must not happen is one being read as the other.
        val totals = AssetGrouping.totals(
            listOf(
                carpark(DueStatus.NOT_DUE, groundSqm = 3_500.0),
                line(DueStatus.NOT_DUE, lengthM = 1000.0, swathWidthM = 3.0)
            )
        )

        assertEquals("a measured 3,500 m² and an estimated 3,000 m²", 6_500.0, totals.areaSqm, 0.01)
        assertEquals("an area came from both of them", 2, totals.areaAssetCount)
    }

    @Test
    fun `a carpark that has not been measured yet adds nothing, and claims nothing`() {
        val totals = AssetGrouping.totals(listOf(carpark(DueStatus.NOT_DUE, groundSqm = 0.0)))

        assertEquals("a zero is not a measurement", 0.0, totals.areaSqm, 1e-9)
        assertEquals("so nothing says the area came from it", 0, totals.areaAssetCount)
    }

    @Test
    fun `a block counts both passes of a track that is walked twice`() {
        // A handover's treated area is the ground that got sprayed, and a track walked up one side
        // and back down the other is sprayed over twice: reporting one pass under-reports it by
        // half, which is the kind of figure that ends up in a spray diary as though it were right.
        val totals = AssetGrouping.totals(
            listOf(
                twoPassLine(DueStatus.NOT_DUE, lengthM = 1000.0),
                line(DueStatus.NOT_DUE, lengthM = 1000.0)
            )
        )

        assertEquals(
            "1.5 m twice over 1 km, plus 3 m once over 1 km",
            6000.0,
            totals.areaSqm,
            0.01
        )
    }

    @Test
    fun `a block adds up its lines, its area and what is left`() {
        val totals = AssetGrouping.totals(
            listOf(
                line(DueStatus.NOT_DUE, lengthM = 1000.0),
                line(DueStatus.NOT_DUE, lengthM = 1000.0),
                line(DueStatus.DUE_SOON, lengthM = 500.0),
                line(DueStatus.OVERDUE, lengthM = 1500.0)
            )
        )

        assertEquals(4, totals.assetCount)
        assertEquals(4000.0, totals.lengthM, 0.01)
        assertEquals("length x swath, over every line that has a width", 12000.0, totals.areaSqm, 0.01)
        assertEquals(4, totals.areaAssetCount)
        assertEquals(2, totals.leftCount)
        assertEquals(2000.0, totals.leftLengthM, 0.01)
        assertEquals(50, totals.leftPercent)
        assertEquals(DueStatus.OVERDUE, totals.status)
    }

    @Test
    fun `the most urgent asset decides the colour, not the average`() {
        val oneOverdue = AssetGrouping.totals(
            listOf(
                line(DueStatus.NOT_DUE),
                line(DueStatus.NOT_DUE),
                line(DueStatus.NOT_DUE),
                line(DueStatus.NOT_DUE),
                line(DueStatus.OVERDUE)
            )
        )
        val oneDueSoon = AssetGrouping.totals(
            listOf(
                line(DueStatus.NOT_DUE),
                line(DueStatus.NOT_DUE),
                line(DueStatus.DUE_SOON)
            )
        )

        assertEquals(DueStatus.OVERDUE, oneOverdue.status)
        assertEquals(DueStatus.DUE_SOON, oneDueSoon.status)
    }

    @Test
    fun `a never-sprayed asset and an overdue one are equally urgent`() {
        assertEquals(AssetGrouping.urgency(DueStatus.NEVER_SPRAYED), AssetGrouping.urgency(DueStatus.OVERDUE))
        assertTrue(
            "both are red on the map, so neither may outrank the other here",
            AssetGrouping.urgency(DueStatus.OVERDUE) > AssetGrouping.urgency(DueStatus.DUE_SOON)
        )

        val totals = AssetGrouping.totals(listOf(line(DueStatus.NOT_DUE), line(DueStatus.NEVER_SPRAYED)))
        assertEquals(DueStatus.NEVER_SPRAYED, totals.status)
    }

    @Test
    fun `the share left is counted in assets, so a spot keeps the number honest`() {
        // A long road that is not due, and one trough that is. By length this block would
        // report "0% left" directly beside a line saying one asset is left to spray.
        val totals = AssetGrouping.totals(
            listOf(line(DueStatus.NOT_DUE, lengthM = 5000.0), spot(DueStatus.DUE_SOON))
        )

        assertEquals(1, totals.leftCount)
        assertEquals(50, totals.leftPercent)
        assertEquals("a spot contributes no distance to drive", 0.0, totals.leftLengthM, 0.01)
    }

    @Test
    fun `an area is only claimed for the assets that know their swath width`() {
        val totals = AssetGrouping.totals(
            listOf(
                line(DueStatus.NOT_DUE, lengthM = 1000.0, swathWidthM = 3.0),
                line(DueStatus.NOT_DUE, lengthM = 1000.0, swathWidthM = 3.0),
                line(DueStatus.NOT_DUE, lengthM = 1000.0, swathWidthM = null),
                spot(DueStatus.NOT_DUE)
            )
        )

        assertEquals("two of the four know how wide they are", 2, totals.areaAssetCount)
        assertEquals(4, totals.assetCount)
        assertEquals(6000.0, totals.areaSqm, 0.01)
    }

    @Test
    fun `a block of spots has no length and no area, and still knows what is left`() {
        val totals = AssetGrouping.totals(listOf(spot(DueStatus.NOT_DUE), spot(DueStatus.DUE_SOON)))

        assertEquals(2, totals.assetCount)
        assertEquals(0.0, totals.lengthM, 0.01)
        assertEquals(0.0, totals.areaSqm, 0.01)
        assertEquals(1, totals.leftCount)
        assertEquals(50, totals.leftPercent)
    }

    @Test
    fun `nothing due is nothing left to spray`() {
        val totals = AssetGrouping.totals(listOf(line(DueStatus.NOT_DUE), line(DueStatus.NOT_DUE)))

        assertTrue(totals.isAllDone)
        assertEquals(0, totals.leftPercent)
        assertEquals(DueStatus.NOT_DUE, totals.status)
    }

    @Test
    fun `rounding lands on the nearest whole per cent`() {
        val oneOfThree = AssetGrouping.totals(
            listOf(line(DueStatus.OVERDUE), line(DueStatus.NOT_DUE), line(DueStatus.NOT_DUE))
        )
        val twoOfThree = AssetGrouping.totals(
            listOf(line(DueStatus.OVERDUE), line(DueStatus.OVERDUE), line(DueStatus.NOT_DUE))
        )

        assertEquals(33, oneOfThree.leftPercent)
        assertEquals(67, twoOfThree.leftPercent)
    }

    @Test
    fun `an empty block says nothing rather than being overdue`() {
        val totals = AssetGrouping.totals(emptyList())

        assertEquals(0, totals.assetCount)
        assertTrue(totals.isAllDone)
        assertEquals(DueStatus.NOT_DUE, totals.status)
    }
}
