package nz.mckenzie.sprayday.map

import android.graphics.Bitmap
import android.graphics.Canvas
import android.graphics.Color
import android.graphics.Paint
import android.graphics.Path

/**
 * Draws the house a place is shown as, as the bitmap the map's style wants.
 *
 * Android's own graphics rather than the Compose glyph the asset's row draws, because that
 * is what a MapLibre image is: a bitmap, handed over once. The two are the same house - a
 * roof to a ridge and two walls - drawn for the two jobs they have: an outline on a card
 * beside a name, and a filled marker the width of a fingertip over aerial imagery, where a
 * house drawn as an outline alone is a smudge.
 *
 * The white edge is the dot's white ring, kept. It is drawn as the same shape stroked fat
 * and then filled over, so half the stroke has to fall outside the walls - which is why the
 * house is built inside the square it is given rather than on its edge.
 */
internal object HouseMarker {

    /**
     * The house, [sizePx] across, in a transparent square.
     *
     * @param colorHex the traffic-light colour the house is painted in.
     * @param sizePx the width and height of the picture, in pixels of the device's own density.
     * @param outlinePx the width of the white edge, in pixels of the same density.
     */
    fun bitmap(colorHex: String, sizePx: Int, outlinePx: Float): Bitmap {
        val size = sizePx.coerceAtLeast(1).toFloat()
        val bitmap = Bitmap.createBitmap(size.toInt(), size.toInt(), Bitmap.Config.ARGB_8888)
        val canvas = Canvas(bitmap)
        val house = housePath(inset = outlinePx.coerceAtLeast(0f), size = size)

        val edge = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.STROKE
            strokeWidth = outlinePx.coerceAtLeast(0f) * 2f
            color = Color.WHITE
        }
        canvas.drawPath(house, edge)

        val fill = Paint(Paint.ANTI_ALIAS_FLAG).apply {
            style = Paint.Style.FILL
            color = parse(colorHex)
        }
        canvas.drawPath(house, fill)

        return bitmap
    }

    /**
     * The house itself: a roof to a ridge at the middle, then walls down to the ground.
     *
     * Five corners, which is all a house needs to be read as one at 22dp: sharper than that
     * is detail nobody can see, and a plain rectangle would read as a box.
     */
    private fun housePath(inset: Float, size: Float): Path = Path().apply {
        val left = inset
        val right = size - inset
        val top = inset
        val bottom = size - inset
        val eave = top + (bottom - top) * EAVE_FRACTION

        moveTo((left + right) / 2f, top)
        lineTo(right, eave)
        lineTo(right, bottom)
        lineTo(left, bottom)
        lineTo(left, eave)
        close()
    }

    /** How far down the house the eaves sit: a roof a little under half of it. */
    private const val EAVE_FRACTION = 0.44f

    /**
     * The colour as Android wants it.
     *
     * An unreadable colour is grey rather than an exception: this is drawing, and a place
     * that cannot be drawn in its colour still has to be drawn.
     */
    private fun parse(colorHex: String): Int = runCatching { Color.parseColor(colorHex) }
        .getOrElse { Color.parseColor(AssetColors.UNKNOWN) }
}
