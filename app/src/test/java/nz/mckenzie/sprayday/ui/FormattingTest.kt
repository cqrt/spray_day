package nz.mckenzie.sprayday.ui

import org.junit.Assert.assertEquals
import org.junit.Test

class FormattingTest {

    @Test
    fun `distances switch to kilometres at a thousand metres`() {
        assertEquals("0 m", formatDistance(0.0))
        assertEquals("850 m", formatDistance(850.4))
        assertEquals("999 m", formatDistance(999.4))
        assertEquals("1.00 km", formatDistance(1000.0))
        assertEquals("2.35 km", formatDistance(2345.0))
        assertEquals("-", formatDistance(-1.0))
    }

    @Test
    fun `areas switch to hectares at ten thousand square metres`() {
        assertEquals("-", formatArea(0.0))
        assertEquals("420 m\u00b2", formatArea(420.0))
        assertEquals("9999 m\u00b2", formatArea(9999.0))
        assertEquals("1.0 ha", formatArea(10_000.0))
        assertEquals("2.5 ha", formatArea(25_000.0))
    }

    @Test
    fun `durations are shown in the most useful unit`() {
        assertEquals("0s", formatDuration(0))
        assertEquals("45s", formatDuration(45_000))
        assertEquals("12m 30s", formatDuration(750_000))
        assertEquals("1h 05m", formatDuration(3_900_000))
        assertEquals("2h 00m", formatDuration(7_200_000))
    }
}
