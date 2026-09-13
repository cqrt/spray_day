package nz.mckenzie.sprayday.domain.due

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test
import java.time.LocalDate
import java.time.LocalTime
import java.time.ZoneId

class DueCalculatorTest {

    private val zone = ZoneId.of("Pacific/Auckland")
    private val leadDays = 14
    private val intervalDays = 120

    private fun epochOf(date: LocalDate, time: LocalTime = LocalTime.of(9, 0)): Long =
        date.atTime(time).atZone(zone).toInstant().toEpochMilli()

    @Test
    fun `never sprayed tracks have no due date`() {
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = null,
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(LocalDate.of(2026, 9, 13)),
            zoneId = zone
        )
        assertEquals(DueStatus.NEVER_SPRAYED, info.status)
        assertNull(info.dueDateEpochMs)
        assertNull(info.daysUntilDue)
    }

    @Test
    fun `recently sprayed track is not due`() {
        val today = LocalDate.of(2026, 9, 13)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays(100)),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.NOT_DUE, info.status)
        assertEquals(20L, info.daysUntilDue)
    }

    @Test
    fun `inside the lead window is due soon`() {
        val today = LocalDate.of(2026, 9, 13)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays(110)),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.DUE_SOON, info.status)
        assertEquals(10L, info.daysUntilDue)
    }

    @Test
    fun `exactly on the due date is due soon not overdue`() {
        val today = LocalDate.of(2026, 9, 13)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays(intervalDays.toLong())),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.DUE_SOON, info.status)
        assertEquals(0L, info.daysUntilDue)
    }

    @Test
    fun `one day past the due date is overdue`() {
        val today = LocalDate.of(2026, 9, 13)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays(intervalDays.toLong() + 1)),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.OVERDUE, info.status)
        assertEquals(-1L, info.daysUntilDue)
    }

    @Test
    fun `lead window boundary is inclusive`() {
        val today = LocalDate.of(2026, 9, 13)
        val onTheEdge = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays((intervalDays - leadDays).toLong())),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.DUE_SOON, onTheEdge.status)

        val justOutside = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays((intervalDays - leadDays - 1).toLong())),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.NOT_DUE, justOutside.status)
    }

    @Test
    fun `interval arithmetic counts calendar days across a daylight saving change`() {
        // NZ daylight saving ended on 5 April 2026.
        val sprayed = LocalDate.of(2026, 4, 1)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(sprayed),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(LocalDate.of(2026, 7, 30)),
            zoneId = zone
        )
        val dueDate = info.dueDateEpochMs?.let {
            java.time.Instant.ofEpochMilli(it).atZone(zone).toLocalDate()
        }
        assertEquals(LocalDate.of(2026, 7, 30), dueDate)
        assertEquals(0L, info.daysUntilDue)
    }

    @Test
    fun `due date is the start of the due day in the calculation zone`() {
        val today = LocalDate.of(2026, 9, 13)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today.minusDays(100)),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        val dueMoment = java.time.Instant.ofEpochMilli(info.dueDateEpochMs!!).atZone(zone)
        assertEquals(LocalTime.MIDNIGHT, dueMoment.toLocalTime())
        assertEquals(today.plusDays(20), dueMoment.toLocalDate())
    }

    @Test
    fun `spraying today resets the countdown`() {
        val today = LocalDate.of(2026, 9, 13)
        val info = DueCalculator.calculate(
            lastSprayedAtEpochMs = epochOf(today, LocalTime.of(16, 30)),
            intervalDays = intervalDays,
            leadDays = leadDays,
            nowEpochMs = epochOf(today),
            zoneId = zone
        )
        assertEquals(DueStatus.NOT_DUE, info.status)
        assertEquals(intervalDays.toLong(), info.daysUntilDue)
    }
}
