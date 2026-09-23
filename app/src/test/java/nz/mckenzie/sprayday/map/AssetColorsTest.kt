package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertNotEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The colours the map and the list draw with.
 *
 * Two vocabularies share this file: the traffic light says when something is due, and
 * the kind colours say what it is. They must not overlap - an icon the operator could
 * read as "overdue" would be worse than no icon at all - and every kind must have a
 * colour of its own, or two kinds would be indistinguishable wherever icons are small.
 */
class AssetColorsTest {

    @Test
    fun `no kind colour is a due colour`() {
        val dueColours = DueStatus.entries.map { AssetColors.forStatus(it) }.toSet()

        AssetKind.entries.forEach { kind ->
            assertTrue(
                "$kind shares a colour with the traffic light",
                AssetColors.forKind(kind) !in dueColours
            )
        }
    }

    @Test
    fun `the eight kinds wear the three family colours, and nothing else`() {
        val families = setOf(AssetColors.TRACK_KIND, AssetColors.ROAD_KIND, AssetColors.PLACE_KIND)

        AssetKind.entries.forEach { kind ->
            assertTrue(
                "$kind wears a colour from outside the three",
                AssetColors.forKind(kind) in families
            )
        }
        // A track and a road are their own colour, and every kind of place shares the teal: the glyph
        // is what tells one place from another, because eight colours at twenty pixels is where two
        // of them start looking alike.
        assertEquals(AssetColors.TRACK_KIND, AssetColors.forKind(AssetKind.TRACK))
        assertEquals(AssetColors.ROAD_KIND, AssetColors.forKind(AssetKind.ROAD))
        assertNotEquals("a road must not look like a track", AssetColors.TRACK_KIND, AssetColors.ROAD_KIND)
        assertEquals(AssetColors.PLACE_KIND, AssetColors.forKind(AssetKind.FENCELINE))
        assertEquals(AssetColors.PLACE_KIND, AssetColors.forKind(AssetKind.OTHER_PLACE))
    }

    @Test
    fun `a kind colour is readable on the light card the list uses`() {
        AssetKind.entries.forEach { kind ->
            val ratio = contrastRatio(AssetColors.forKind(kind), LIGHT_CARD)
            assertTrue("$kind on a light card is only ${round(ratio)}:1", ratio >= MIN_CONTRAST)
        }
    }

    @Test
    fun `and on the dark one, because the app follows the system theme`() {
        // This is the case that made a brown icon disappear: fine on white, all but
        // invisible on the dark grey a phone in dark mode puts behind it.
        AssetKind.entries.forEach { kind ->
            val ratio = contrastRatio(AssetColors.forKind(kind), DARK_CARD)
            assertTrue("$kind on a dark card is only ${round(ratio)}:1", ratio >= MIN_CONTRAST)
        }
    }

    @Test
    fun `the phone's own position is not a due colour`() {
        // The marker must not be readable as "overdue": it says where you are standing, not
        // what needs doing, and it is drawn on the same map as both.
        val dueColours = DueStatus.entries.map { AssetColors.forStatus(it) }.toSet()

        assertTrue(
            "the position marker shares a colour with the traffic light",
            AssetColors.POSITION !in dueColours
        )
    }

    @Test
    fun `and not a kind colour either, which is why it is not blue`() {
        AssetKind.entries.forEach { kind ->
            assertTrue(
                "$kind and the position marker are the same colour",
                AssetColors.forKind(kind) != AssetColors.POSITION
            )
        }
    }

    /** WCAG contrast: 1.0 is the same colour, 21.0 is black on white. */
    private fun contrastRatio(first: String, second: String): Double {
        val a = luminance(first)
        val b = luminance(second)
        return (maxOf(a, b) + 0.05) / (minOf(a, b) + 0.05)
    }

    private fun luminance(hex: String): Double {
        val value = hex.removePrefix("#").toInt(16)
        val channels = listOf((value shr 16) and 0xFF, (value shr 8) and 0xFF, value and 0xFF)
            .map { channel ->
                val fraction = channel / 255.0
                if (fraction <= 0.03928) fraction / 12.92
                else Math.pow((fraction + 0.055) / 1.055, 2.4)
            }
        return 0.2126 * channels[0] + 0.7152 * channels[1] + 0.0722 * channels[2]
    }

    private fun round(value: Double): String = String.format(java.util.Locale.US, "%.1f", value)

    private companion object {
        /** Enough for a thin outline to be read on the card behind it. */
        const val MIN_CONTRAST = 3.0

        /** A Material light card, and a dark one. */
        const val LIGHT_CARD = "#FFFFFF"
        const val DARK_CARD = "#121212"
    }

    @Test
    fun `the traffic light still says what it always said`() {
        assertEquals(AssetColors.GREEN, AssetColors.forStatus(DueStatus.NOT_DUE))
        assertEquals(AssetColors.YELLOW, AssetColors.forStatus(DueStatus.DUE_SOON))
        assertEquals(AssetColors.RED, AssetColors.forStatus(DueStatus.OVERDUE))
        assertEquals(AssetColors.RED, AssetColors.forStatus(DueStatus.NEVER_SPRAYED))
    }
}
