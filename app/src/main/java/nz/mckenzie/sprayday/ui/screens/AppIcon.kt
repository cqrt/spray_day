package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.material3.LocalContentColor
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp

/**
 * The glyphs that label the app's tabs and buttons.
 *
 * Drawn rather than shipped as an icon font or a set of images, for the same reason
 * [AssetKindIcon] is drawn: the app has no icon dependency, and a dozen shapes are easier
 * to keep honest in one file than as PNGs at five densities.
 *
 * The tint defaults to the surrounding content colour, which is what lets a navigation bar
 * colour the selected tab for us: the bar supplies the colour and this reads it.
 */
enum class IconGlyph {
    BACK,
    MAP,
    ASSETS,
    RECORD,
    RECORDINGS,
    OFFLINE,
    SETTINGS,
    CHECK,
    MORE,
    EDIT,
    EXPORT,
    TRASH,
    SPRAY,
    CHEVRON,
    BLOCKS,
    LOCATE
}

/**
 * One glyph, drawn on a 24-unit grid whatever size it is asked for.
 *
 * [contentDescription] is for the places where the glyph is the *only* thing saying what a
 * control does - a back arrow, a settings icon. Where the glyph sits beside a label, or
 * inside a button whose text already says it, leave it null: a second announcement of the
 * same thing is worse than none.
 */
@Composable
internal fun AppIcon(
    glyph: IconGlyph,
    modifier: Modifier = Modifier,
    dimension: Dp = 24.dp,
    tint: Color = LocalContentColor.current,
    contentDescription: String? = null
) {
    val description = contentDescription
    Canvas(
        modifier = modifier
            .size(dimension)
            .semantics { if (description != null) this.contentDescription = description }
    ) {
        // Material draws its icons with a 2dp stroke on a 24dp grid, so the stroke scales
        // with the grid rather than staying fixed: a 48dp icon is not a hairline.
        val stroke = size.minDimension * (2f / 24f)
        val width = size.width
        val height = size.height
        val dot = size.minDimension * 0.07f

        fun at(x: Float, y: Float) = Offset(width * x, height * y)

        fun line(x1: Float, y1: Float, x2: Float, y2: Float) = drawLine(
            color = tint,
            start = at(x1, y1),
            end = at(x2, y2),
            strokeWidth = stroke,
            cap = StrokeCap.Round
        )

        fun bobble(x: Float, y: Float, radius: Float = dot) =
            drawCircle(color = tint, radius = radius, center = at(x, y))

        fun ring(x: Float, y: Float, radius: Float) = drawCircle(
            color = tint,
            radius = size.minDimension * radius,
            center = at(x, y),
            style = Stroke(width = stroke)
        )

        when (glyph) {
            IconGlyph.BACK -> {
                line(0.22f, 0.50f, 0.80f, 0.50f)
                line(0.44f, 0.26f, 0.20f, 0.50f)
                line(0.20f, 0.50f, 0.44f, 0.74f)
            }

            // A place on the map, which is the thing this app is about.
            IconGlyph.MAP -> {
                ring(0.50f, 0.38f, 0.22f)
                line(0.36f, 0.55f, 0.50f, 0.86f)
                line(0.64f, 0.55f, 0.50f, 0.86f)
            }

            // The asset library: rows, the way they will read on the screen.
            IconGlyph.ASSETS -> {
                listOf(0.28f, 0.50f, 0.72f).forEach { y ->
                    line(0.40f, y, 0.82f, y)
                    bobble(0.24f, y)
                }
            }

            IconGlyph.RECORD -> {
                ring(0.50f, 0.50f, 0.34f)
                bobble(0.50f, 0.50f, radius = size.minDimension * 0.15f)
            }

            // What was actually driven: a line with the fixes on it.
            IconGlyph.RECORDINGS -> {
                line(0.20f, 0.76f, 0.48f, 0.44f)
                line(0.48f, 0.44f, 0.80f, 0.62f)
                bobble(0.20f, 0.76f, radius = dot * 1.2f)
                bobble(0.48f, 0.44f, radius = dot * 1.2f)
                bobble(0.80f, 0.62f, radius = dot * 1.2f)
            }

            // Saved imagery: down into the device.
            IconGlyph.OFFLINE -> {
                line(0.50f, 0.20f, 0.50f, 0.60f)
                line(0.32f, 0.44f, 0.50f, 0.62f)
                line(0.68f, 0.44f, 0.50f, 0.62f)
                line(0.24f, 0.80f, 0.76f, 0.80f)
            }

            // Sliders rather than a cog: a cog at this size is a smudge, and these are the
            // same three rows the Settings screen is made of.
            IconGlyph.SETTINGS -> {
                line(0.22f, 0.28f, 0.78f, 0.28f)
                bobble(0.38f, 0.28f, radius = dot * 1.2f)
                line(0.22f, 0.50f, 0.78f, 0.50f)
                bobble(0.64f, 0.50f, radius = dot * 1.2f)
                line(0.22f, 0.72f, 0.78f, 0.72f)
                bobble(0.44f, 0.72f, radius = dot * 1.2f)
            }

            IconGlyph.CHECK -> {
                line(0.24f, 0.52f, 0.44f, 0.72f)
                line(0.44f, 0.72f, 0.78f, 0.28f)
            }

            // A chevron pointing right, which the list turns to point down when a block is
            // open. One glyph rather than two, so there is no way for the pair to disagree.
            IconGlyph.CHEVRON -> {
                line(0.40f, 0.24f, 0.64f, 0.50f)
                line(0.64f, 0.50f, 0.40f, 0.76f)
            }

            // Blocks: one rectangle of assets gathered behind another, which is what a block
            // is - assets worked as one thing rather than a container with sides.
            IconGlyph.BLOCKS -> {
                val front = Path().apply {
                    moveTo(width * 0.14f, height * 0.56f)
                    lineTo(width * 0.14f, height * 0.86f)
                    lineTo(width * 0.62f, height * 0.86f)
                    lineTo(width * 0.62f, height * 0.56f)
                    close()
                }
                drawPath(front, tint, style = Stroke(width = stroke))
                val behind = Path().apply {
                    moveTo(width * 0.38f, height * 0.42f)
                    lineTo(width * 0.38f, height * 0.14f)
                    lineTo(width * 0.86f, height * 0.14f)
                    lineTo(width * 0.86f, height * 0.42f)
                    close()
                }
                drawPath(behind, tint, style = Stroke(width = stroke))
            }

            // Where you are: a ring with a dot in it, and the four ticks that stop it reading
            // as one more small circle on a map that is made of them.
            IconGlyph.LOCATE -> {
                ring(0.50f, 0.50f, 0.20f)
                bobble(0.50f, 0.50f, radius = dot * 1.7f)
                line(0.50f, 0.06f, 0.50f, 0.20f)
                line(0.50f, 0.80f, 0.50f, 0.94f)
                line(0.06f, 0.50f, 0.20f, 0.50f)
                line(0.80f, 0.50f, 0.94f, 0.50f)
            }

            IconGlyph.MORE -> {
                bobble(0.50f, 0.24f)
                bobble(0.50f, 0.50f)
                bobble(0.50f, 0.76f)
            }

            // A pencil: the shaft, and the nib it comes to a point with.
            IconGlyph.EDIT -> {
                line(0.24f, 0.76f, 0.70f, 0.30f)
                line(0.60f, 0.20f, 0.80f, 0.40f)
            }

            // The mirror of [IconGlyph.OFFLINE]: out of the device, which is an export.
            IconGlyph.EXPORT -> {
                line(0.50f, 0.62f, 0.50f, 0.22f)
                line(0.32f, 0.40f, 0.50f, 0.22f)
                line(0.68f, 0.40f, 0.50f, 0.22f)
                line(0.24f, 0.80f, 0.76f, 0.80f)
            }

            IconGlyph.TRASH -> {
                val body = Path().apply {
                    moveTo(width * 0.30f, height * 0.32f)
                    lineTo(width * 0.70f, height * 0.32f)
                    lineTo(width * 0.65f, height * 0.82f)
                    lineTo(width * 0.35f, height * 0.82f)
                    close()
                }
                drawPath(body, tint, style = Stroke(width = stroke))
                line(0.20f, 0.32f, 0.80f, 0.32f)
                line(0.42f, 0.32f, 0.42f, 0.20f)
                line(0.42f, 0.20f, 0.58f, 0.20f)
                line(0.58f, 0.20f, 0.58f, 0.32f)
            }

            // The drop that comes out of the tank: the mark a spray leaves on the record.
            IconGlyph.SPRAY -> {
                val drop = Path().apply {
                    moveTo(width * 0.50f, height * 0.14f)
                    cubicTo(
                        width * 0.50f, height * 0.36f,
                        width * 0.80f, height * 0.46f,
                        width * 0.80f, height * 0.64f
                    )
                    cubicTo(
                        width * 0.80f, height * 0.84f,
                        width * 0.66f, height * 0.88f,
                        width * 0.50f, height * 0.88f
                    )
                    cubicTo(
                        width * 0.34f, height * 0.88f,
                        width * 0.20f, height * 0.84f,
                        width * 0.20f, height * 0.64f
                    )
                    cubicTo(
                        width * 0.20f, height * 0.46f,
                        width * 0.50f, height * 0.36f,
                        width * 0.50f, height * 0.14f
                    )
                }
                drawPath(drop, tint, style = Stroke(width = stroke))
            }
        }
    }
}
