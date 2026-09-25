package nz.mckenzie.sprayday.map

import android.graphics.Bitmap
import android.graphics.Color
import androidx.test.ext.junit.runners.AndroidJUnit4
import nz.mckenzie.sprayday.domain.asset.AssetKind
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test
import org.junit.runner.RunWith

/**
 * The pictures the map draws a place with.
 *
 * A bitmap cannot be drawn on the JVM, so this is the one place the *drawing* is checked rather than
 * the naming: that every kind of place gives a picture with something in it, and that no two of them
 * are the same picture. `Bitmap.sameAs` compares pixels, so a bench seat and a picnic table that came
 * out identical - the mistake the shapes are most likely to make, since both are bars on legs - fails
 * here rather than being noticed on a hillside.
 */
@RunWith(AndroidJUnit4::class)
class MarkerIconsTest {

    private fun marker(kind: AssetKind, colorHex: String = AssetColors.GREEN) =
        MarkerIcons.bitmap(kind, colorHex, sizePx = 64, outlinePx = 6f)

    @Test
    fun everyKindOfPlaceIsDrawnAsSomething() {
        PlaceIcons.KINDS.forEach { kind ->
            val bitmap = marker(kind)
            val painted = (0 until bitmap.width).sumOf { x ->
                (0 until bitmap.height).count { y -> bitmap.getPixel(x, y) != 0 }
            }

            assertTrue("${kind.name} is drawn as nothing at all", painted > 0)
            assertTrue(
                "and is not a smudge: $painted pixels of ${bitmap.width * bitmap.height}",
                painted > bitmap.width
            )
        }
    }

    @Test
    fun noTwoKindsOfPlaceAreTheSamePicture() {
        val pictures = PlaceIcons.KINDS.map { it to marker(it) }

        pictures.forEachIndexed { at, (kind, bitmap) ->
            pictures.drop(at + 1).forEach { (other, otherBitmap) ->
                assertFalse(
                    "${kind.name} and ${other.name} are drawn the same, so the map cannot tell them apart",
                    bitmap.sameAs(otherBitmap)
                )
            }
        }
    }

    @Test
    fun noMarkerIsDrawnOnABackground() {
        // A marker is a picture over imagery, not a tile: whatever is behind it has to show through.
        // The corners are where a background shows first, and the first version of this drawing put
        // a white box around a sign - a 25-pixel white stroke on a 20-pixel plate.
        PlaceIcons.KINDS.forEach { kind ->
            val bitmap = marker(kind)
            val corners = listOf(0 to 0, bitmap.width - 1 to 0, 0 to bitmap.height - 1, bitmap.width - 1 to bitmap.height - 1)

            corners.forEach { (x, y) ->
                assertEquals(
                    "${kind.name} is drawn on a background: the pixel at $x,$y is not see-through",
                    0,
                    Color.alpha(bitmap.getPixel(x, y))
                )
            }
        }
    }

    @Test
    fun theWhiteEdgeIsARimAndNotABlock() {
        // The white edge is as wide as the marker's own outline says and no wider, which means every
        // white pixel has a coloured pixel within that distance of it. A white *block* - the same
        // path drawn fatter - has white pixels with the nearest colour further away than that, and
        // that is the difference between a marker that reads over imagery and a white square with a
        // red shape in it.
        PlaceIcons.KINDS.forEach { kind ->
            val bitmap = marker(kind)
            val rim = PlaceIcons.MARKER_OUTLINE_DP / PlaceIcons.MARKER_DP * bitmap.width
            val reach = (rim * 2).toInt() + 2

            var painted = 0
            var stranded = 0
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    if (!bitmap.isWhiteAt(x, y)) continue
                    painted++
                    if (!bitmap.hasColourNear(x, y, reach)) stranded++
                }
            }

            assertTrue("${kind.name} was drawn as nothing at all", painted > 0)
            assertEquals(
                "${kind.name} has $stranded white pixels further than ${reach}px from any colour, " +
                    "so its edge is a block rather than a rim",
                0,
                stranded
            )
        }
    }

    @Test
    fun theColourAskedForIsTheColourDrawn() {
        val green = marker(AssetKind.OTHER_PLACE, AssetColors.GREEN)
        val red = marker(AssetKind.OTHER_PLACE, AssetColors.RED)

        assertFalse("a ring's traffic light is not a decoration", green.sameAs(red))
    }

    @Test
    fun thePlainMarkerHasNoWhiteOnItAtAll() {
        // The operator's rule, and the reason for it: a marker is the colour of the asset's traffic
        // light drawn on imagery. A white edge around every one of them made each marker a white
        // shape with a colour inside it, which is harder to read than the colour itself.
        PlaceIcons.KINDS.forEach { kind ->
            val bitmap = MarkerIcons.bitmap(kind, AssetColors.RED, sizePx = 64, outlinePx = 0f)

            var white = 0
            for (x in 0 until bitmap.width) {
                for (y in 0 until bitmap.height) {
                    if (bitmap.isWhiteAt(x, y)) white++
                }
            }

            assertEquals(
                "${kind.name} has $white white pixels on it, and a plain marker has none",
                0,
                white
            )
        }
    }

    @Test
    fun onlyTheSelectedMarkerWearsTheWhiteEdge() {
        val plain = MarkerIcons.bitmap(AssetKind.SIGN, AssetColors.RED, sizePx = 64, outlinePx = 0f)
        val selected = MarkerIcons.bitmap(
            AssetKind.SIGN,
            AssetColors.RED,
            sizePx = 64,
            outlinePx = PlaceIcons.MARKER_OUTLINE_DP / PlaceIcons.MARKER_DP * 64
        )

        assertFalse(
            "the edge is the difference between the asset being looked at and the rest",
            plain.sameAs(selected)
        )
        assertEquals(
            "and the edged one is the only one with white on it",
            0,
            (0 until plain.width).sumOf { x -> (0 until plain.height).count { y -> plain.isWhiteAt(x, y) } }
        )
        assertTrue(
            "while the edged one has a rim of it",
            (0 until selected.width).sumOf { x -> (0 until selected.height).count { y -> selected.isWhiteAt(x, y) } } > 0
        )
    }

    private fun Bitmap.isWhiteAt(x: Int, y: Int): Boolean {
        val pixel = getPixel(x, y)
        return Color.alpha(pixel) > 0 &&
            Color.red(pixel) > 240 && Color.green(pixel) > 240 && Color.blue(pixel) > 240
    }

    /** Whether any pixel in the square of side [reach] x 2 around ([x], [y]) is a colour. */
    private fun Bitmap.hasColourNear(x: Int, y: Int, reach: Int): Boolean {
        for (dx in -reach..reach) {
            for (dy in -reach..reach) {
                val px = x + dx
                val py = y + dy
                if (px !in 0 until width || py !in 0 until height) continue
                val pixel = getPixel(px, py)
                if (Color.alpha(pixel) == 0) continue
                if (Color.red(pixel) < 220 || Color.green(pixel) < 220 || Color.blue(pixel) < 220) return true
            }
        }
        return false
    }
}
