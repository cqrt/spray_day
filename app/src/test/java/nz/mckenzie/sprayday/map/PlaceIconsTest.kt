package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.due.DueStatus
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
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
    fun `every colour a traffic light can show has a picture for every kind of place`() {
        DueStatus.entries.map { AssetColors.forStatus(it) }.forEach { colorHex ->
            PlaceIcons.KINDS.forEach { kind ->
                assertTrue(
                    "a ${kind.name} wearing $colorHex would have no picture to ask for",
                    PlaceIcons.IMAGE_NAMES.contains(PlaceIcons.imageName(kind, colorHex))
                )
            }
        }
    }

    @Test
    fun `the kinds that are a place are the kinds the app says are places`() {
        assertEquals(
            "a place is a single spot, and the kinds say which those are",
            AssetKind.entries.filter { it.shape == AssetShape.POINT },
            PlaceIcons.KINDS
        )
        assertEquals(5, PlaceIcons.KINDS.size)
    }

    @Test
    fun `a picture is named after the kind and the colour, so the three cannot drift apart`() {
        assertEquals(
            "sprayday-place-building-2e7d32",
            PlaceIcons.imageName(AssetKind.BUILDING, AssetColors.GREEN)
        )
        assertEquals(
            "sprayday-place-sign-f9a825",
            PlaceIcons.imageName(AssetKind.SIGN, AssetColors.YELLOW)
        )
        assertEquals(
            "sprayday-place-bench-c62828",
            PlaceIcons.imageName(AssetKind.BENCH, AssetColors.RED)
        )
        assertEquals(
            "a kind with two words in it is one word in a name",
            "sprayday-place-other-place-757575",
            PlaceIcons.imageName(AssetKind.OTHER_PLACE, AssetColors.UNKNOWN)
        )
        assertFalse(
            "a hash in an image name is a URL fragment, not part of a picture",
            PlaceIcons.imageName(AssetKind.BUILDING, AssetColors.GREEN).contains("#")
        )
    }

    @Test
    fun `no two pictures share a name`() {
        assertEquals("a picture per kind, per colour", 20, PlaceIcons.IMAGE_NAMES.size)
        assertTrue(
            "two of them sharing a name would make one of them a lie: ${PlaceIcons.IMAGE_NAMES}",
            PlaceIcons.IMAGE_NAMES.toSet().size == PlaceIcons.KINDS.size * PlaceIcons.COLORS.size
        )
    }

    @Test
    fun `a colour nothing was drawn for is grey, and a kind that is not a place is the grey ring`() {
        val oddColour = PlaceIcons.imageName(AssetKind.TABLE, "#123456")
        val lineKind = PlaceIcons.imageName(AssetKind.TRACK, AssetColors.RED)

        assertEquals(
            "a colour the app does not draw still says what the thing is: a grey table, not a grey blob",
            PlaceIcons.imageName(AssetKind.TABLE, AssetColors.UNKNOWN),
            oddColour
        )
        assertEquals("a line kind has no marker at all", PlaceIcons.FALLBACK_IMAGE_NAME, lineKind)
        assertTrue(
            "and the fallback has to be a picture the style was given",
            PlaceIcons.IMAGE_NAMES.contains(PlaceIcons.FALLBACK_IMAGE_NAME)
        )
    }

    @Test
    fun `the same colour in either case asks for the same picture`() {
        assertEquals(
            PlaceIcons.imageName(AssetKind.BUILDING, AssetColors.RED),
            PlaceIcons.imageName(AssetKind.BUILDING, AssetColors.RED.lowercase())
        )
    }

    @Test
    fun `the marker's white edge is thin`() {
        val share = PlaceIcons.MARKER_OUTLINE_DP / PlaceIcons.MARKER_DP

        assertTrue(
            "an edge of ${PlaceIcons.MARKER_OUTLINE_DP}dp on a ${PlaceIcons.MARKER_DP}dp marker is " +
                "${(share * 100).toInt()}% of its width, which is not thin",
            share <= 0.05f
        )
        assertTrue(
            "and less than one dp would not be drawn at all",
            PlaceIcons.MARKER_OUTLINE_DP >= 1f
        )
    }

    @Test
    fun `a name reads back as the kind and the colour it was made from`() {
        PlaceIcons.KINDS.forEach { kind ->
            PlaceIcons.COLORS.forEach { colour ->
                assertEquals(
                    "the desk asks for a picture by name, so a name has to mean one kind in one colour",
                    kind to colour,
                    PlaceIcons.ofImageName(PlaceIcons.imageName(kind, colour))
                )
            }
        }
        assertNull(
            "a name that is no picture reads back as nothing",
            PlaceIcons.ofImageName("sprayday-place-nonsense-c62828")
        )
    }

    @Test
    fun `a place is drawn wider than the dot it replaced`() {
        assertTrue(
            "the dot was 16dp across, and these glyphs have legs, posts and a ring to read",
            PlaceIcons.MARKER_DP > 16f
        )
    }
}
