package nz.mckenzie.sprayday.map

import androidx.test.ext.junit.runners.AndroidJUnit4
import nz.mckenzie.sprayday.domain.asset.AssetKind
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
    fun theColourAskedForIsTheColourDrawn() {
        val green = marker(AssetKind.OTHER_PLACE, AssetColors.GREEN)
        val red = marker(AssetKind.OTHER_PLACE, AssetColors.RED)

        assertFalse("a ring's traffic light is not a decoration", green.sameAs(red))
    }
}
