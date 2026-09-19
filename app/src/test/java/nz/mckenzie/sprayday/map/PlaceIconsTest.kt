package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which picture a place is drawn with.
 *
 * What matters is not the drawing - that is a bitmap, and it is looked at - but that the map
 * can always put something on a place, in the colour the place's own traffic light is showing.
 * A place drawn in the wrong colour is a wrong answer, and a place drawn as nothing at all
 * looks like an asset somebody has deleted.
 */
class PlaceIconsTest {

    @Test
    fun `every colour a traffic light can show has a house`() {
        DueStatus.entries.map { AssetColors.forStatus(it) }.forEach { colorHex ->
            assertTrue(
                "a place wearing $colorHex would have no picture to ask for",
                PlaceIcons.IMAGE_NAMES.contains(PlaceIcons.houseImageName(colorHex))
            )
        }
    }

    @Test
    fun `a place is named after the colour it wears, so the two cannot drift apart`() {
        assertEquals("sprayday-place-2e7d32", PlaceIcons.houseImageName(AssetColors.GREEN))
        assertEquals("sprayday-place-f9a825", PlaceIcons.houseImageName(AssetColors.YELLOW))
        assertEquals("sprayday-place-c62828", PlaceIcons.houseImageName(AssetColors.RED))
        assertEquals("sprayday-place-757575", PlaceIcons.houseImageName(AssetColors.UNKNOWN))
        assertFalse(
            "a hash in an image name is a URL fragment, not part of a picture",
            PlaceIcons.houseImageName(AssetColors.GREEN).contains("#")
        )
    }

    @Test
    fun `no two colours share a house`() {
        assertTrue(
            "two colours sharing a picture would make one of them a lie: ${PlaceIcons.IMAGE_NAMES}",
            PlaceIcons.IMAGE_NAMES.toSet().size == PlaceIcons.COLORS.size
        )
    }

    @Test
    fun `a colour nothing was drawn for gets the grey house rather than nothing`() {
        val unnamed = PlaceIcons.houseImageName("#123456")

        assertEquals(PlaceIcons.FALLBACK_IMAGE_NAME, unnamed)
        assertTrue(
            "the fallback has to be a picture the style was given",
            PlaceIcons.IMAGE_NAMES.contains(unnamed)
        )
    }

    @Test
    fun `the same colour in either case asks for the same house`() {
        assertEquals(
            PlaceIcons.houseImageName(AssetColors.RED),
            PlaceIcons.houseImageName(AssetColors.RED.lowercase())
        )
    }

    @Test
    fun `a place is drawn wider than the dot it replaced`() {
        assertTrue(
            "the dot was 16dp across, and a house has a roof, two walls and an edge to read",
            PlaceIcons.HOUSE_DP > 16f
        )
    }
}
