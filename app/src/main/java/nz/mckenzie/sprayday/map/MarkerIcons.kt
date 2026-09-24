package nz.mckenzie.sprayday.map

import android.graphics.Bitmap
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Canvas
import androidx.compose.ui.graphics.asImageBitmap
import androidx.compose.ui.graphics.drawscope.CanvasDrawScope
import androidx.compose.ui.unit.Density
import androidx.compose.ui.unit.LayoutDirection
import nz.mckenzie.sprayday.domain.asset.AssetKind

/**
 * Draws a kind's glyph as the bitmap the map's style wants.
 *
 * The **same** `DrawScope` code the asset list's glyphs come from, at a marker's weight. Android's
 * own graphics would be a second set of shapes to keep in step, and the whole point of the map's
 * markers is that they are the list's glyphs: a bench seat that is a bench seat in a row has to be
 * one over the paddock too.
 *
 * A MapLibre image is a bitmap handed over once, so the glyph is rendered into one here - one image
 * pixel per screen pixel, at the device's own density, because there is no icon-size in the style to
 * keep in step with the screen.
 *
 * The white edge comes from the same place as the shape: [drawKindGlyph] strokes every glyph white
 * and wider first, so half of that stroke falls outside the shape - which is why a glyph is built
 * inside the square rather than on its edge.
 */
internal object MarkerIcons {

    /**
     * [kind]'s glyph, [sizePx] across in a transparent square, painted [colorHex].
     *
     * @param sizePx the width and height of the picture, in pixels of the device's own density.
     * @param outlinePx the width of the white edge, in pixels of the same density.
     */
    fun bitmap(kind: AssetKind, colorHex: String, sizePx: Int, outlinePx: Float): Bitmap {
        val size = sizePx.coerceAtLeast(1)
        val bitmap = Bitmap.createBitmap(size, size, Bitmap.Config.ARGB_8888)

        // The halo is asked for as a fraction of the box because that is what the drawing works in,
        // and clamped so that a silly outline cannot swallow the glyph it is meant to make readable.
        val style = GlyphStyle.MARKER.copy(
            haloFraction = (outlinePx.coerceAtLeast(0f) / size).coerceAtMost(0.4f)
        )

        CanvasDrawScope().draw(
            // No scaling: the bitmap is already the size the marker is drawn at, on this device.
            density = Density(1f),
            layoutDirection = LayoutDirection.Ltr,
            canvas = Canvas(bitmap.asImageBitmap()),
            size = Size(size.toFloat(), size.toFloat())
        ) {
            drawKindGlyph(kind, colorHex, style)
        }

        return bitmap
    }
}
