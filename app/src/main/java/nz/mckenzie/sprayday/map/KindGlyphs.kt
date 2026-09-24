package nz.mckenzie.sprayday.map

import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Color
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.DrawScope
import androidx.compose.ui.graphics.drawscope.Fill
import androidx.compose.ui.graphics.drawscope.Stroke
import nz.mckenzie.sprayday.domain.asset.AssetKind

/**
 * The eight kinds of asset, drawn: one shape each, in whatever colour the job needs.
 *
 * **One geometry, two weights.** The list draws a glyph 20 dp wide beside a name, where a thin
 * outline is enough and anything heavier is a blob; the map draws the same glyph as a marker over
 * aerial imagery, where a thin outline disappears into the ground and a white edge is what makes it
 * findable. Those are the same shapes at different weights, not two sets of shapes - so they live
 * here together, and a bench cannot be a bench in the list and something else on the map.
 *
 * The colour is the caller's, because the two jobs colour them differently on purpose: beside a name
 * the colour says the *family* (a line to travel along, a place to stop at), and on the map it says
 * *when the thing is next due*, which is what a map of the work is read for. Neither is the kind's,
 * and none of the three may be confusable with another.
 *
 * What each shape leans on to be told apart at twenty pixels is commented beside it, because that is
 * the thing reading the code cannot recover: two glyphs that are the same shape at this size is a
 * list - or a map - where the operator cannot tell a sign from a seat.
 */
internal data class GlyphStyle(
    /** Stroke width, as a fraction of the box. */
    val weight: Float,
    /**
     * The white edge drawn behind the glyph, as a fraction of the box. Zero for a glyph on paper; a
     * marker is over imagery, where red on dark winter ground is otherwise a dark shape on dark
     * ground.
     */
    val haloFraction: Float = 0f,
    /** Whether a closed box - a house's walls, a sign's plate - is filled rather than outlined. */
    val filledBoxes: Boolean = false
) {
    companion object {

        /** Beside a name: a thin outline on paper, which is all a 20 dp glyph needs. */
        val LIST = GlyphStyle(weight = 0.13f)

        /**
         * Over imagery: fatter, filled where a shape is a solid thing, and stroked white behind so
         * it reads on dark ground.
         */
        val MARKER = GlyphStyle(weight = 0.20f, haloFraction = 0.10f, filledBoxes = true)
    }
}

/**
 * Draws [kind]'s glyph to fill the canvas it is given.
 *
 * The halo is the same shapes stroked wider and drawn first, which is why a glyph is built inside the
 * square rather than on its edge: half of that wider stroke has to fall outside the shape, and a
 * shape drawn to the very edge would have its edge cut off.
 */
internal fun DrawScope.drawKindGlyph(kind: AssetKind, colorHex: String, style: GlyphStyle) {
    if (style.haloFraction > 0f) {
        drawKindShapes(
            kind = kind,
            color = parseHexColor(PlaceIcons.MARKER_OUTLINE),
            // Never filled, even for a shape whose colour pass is: a wider stroke of the same path
            // is what puts a rim around it, and filling it here would swallow the glyph.
            style = style.copy(weight = style.weight + style.haloFraction * 2f, filledBoxes = false)
        )
    }
    drawKindShapes(kind, parseHexColor(colorHex), style)
}

private fun DrawScope.drawKindShapes(kind: AssetKind, color: Color, style: GlyphStyle) {
    val stroke = size.minDimension * style.weight
    val width = size.width
    val height = size.height
    val x = { fraction: Float -> width * fraction }
    val y = { fraction: Float -> height * fraction }

    fun line(x1: Float, y1: Float, x2: Float, y2: Float, widthOf: Float = stroke) {
        drawLine(
            color = color,
            start = Offset(x(x1), y(y1)),
            end = Offset(x(x2), y(y2)),
            strokeWidth = widthOf,
            cap = StrokeCap.Round
        )
    }

    /** A box, filled or outlined according to the style. */
    fun box(left: Float, top: Float, boxWidth: Float, boxHeight: Float) {
        drawRect(
            color = color,
            topLeft = Offset(x(left), y(top)),
            size = Size(width * boxWidth, height * boxHeight),
            style = if (style.filledBoxes) Fill else Stroke(width = stroke)
        )
    }

    when (kind) {
        // A track wanders, so it is one line with a bend in it.
        AssetKind.TRACK -> {
            val path = Path().apply {
                moveTo(x(0.14f), y(0.86f))
                cubicTo(x(0.32f), y(0.30f), x(0.68f), y(0.70f), x(0.86f), y(0.14f))
            }
            drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
        }

        // A road has two edges that go somewhere, so it is two straight lines.
        AssetKind.ROAD -> {
            line(0.22f, 0.90f, 0.52f, 0.10f)
            line(0.64f, 0.90f, 0.92f, 0.10f)
        }

        // A fence: posts with rails between them. Dotted on the map, because a fenceline is a
        // series of short things, and this is the same statement drawn small.
        AssetKind.FENCELINE -> {
            listOf(0.20f, 0.50f, 0.80f).forEach { post -> line(post, 0.16f, post, 0.90f) }
            listOf(0.38f, 0.66f).forEach { rail ->
                line(0.14f, rail, 0.86f, rail, stroke * 0.8f)
            }
        }

        // A roof over walls: what a shed looks like from the air, and from the ground.
        AssetKind.BUILDING -> {
            val roof = Path().apply {
                moveTo(x(0.10f), y(0.46f))
                lineTo(x(0.50f), y(0.12f))
                lineTo(x(0.90f), y(0.46f))
            }
            drawPath(roof, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            box(left = 0.26f, top = 0.50f, boxWidth = 0.48f, boxHeight = 0.38f)
        }

        // A plate on one post - and the plate is blank on purpose: anything written on it is
        // mush at this size, and a sign nobody can read is worse than a plain one.
        AssetKind.SIGN -> {
            box(left = 0.16f, top = 0.10f, boxWidth = 0.68f, boxHeight = 0.30f)
            line(0.50f, 0.40f, 0.50f, 0.90f)
        }

        // A backrest and a seat over straight legs. The two bars are 0.42 of the box apart, which is
        // eight pixels at twenty with a 2.6 pixel stroke - the least that reads as two bars rather
        // than one smear. A first draft had them 0.28 apart, and did not.
        AssetKind.BENCH -> {
            line(0.18f, 0.14f, 0.82f, 0.14f)
            line(0.14f, 0.56f, 0.86f, 0.56f)
            line(0.28f, 0.14f, 0.28f, 0.90f)
            line(0.72f, 0.14f, 0.72f, 0.90f)
        }

        // One top on splayed legs. Straight legs say bench and splayed legs say table, and at this
        // size that is the whole difference between the two.
        AssetKind.TABLE -> {
            line(0.14f, 0.34f, 0.86f, 0.34f)
            line(0.42f, 0.34f, 0.20f, 0.88f)
            line(0.58f, 0.34f, 0.80f, 0.88f)
        }

        // A dot in a ring: something is here, and nothing beyond that. The house this used to wear
        // belongs to the building now: a trough wearing a house was the app claiming a roof nobody
        // had recorded.
        AssetKind.OTHER_PLACE -> {
            drawCircle(
                color = color,
                radius = size.minDimension * 0.36f,
                style = Stroke(width = stroke)
            )
            drawCircle(color = color, radius = size.minDimension * 0.10f)
        }
    }
}

/**
 * "#RRGGBB" to a Compose colour, used to keep map and UI colours identical.
 *
 * Here rather than in the drawing code of a screen because both need it and one of them is this
 * package: a glyph is coloured from [AssetColors]'s strings on the phone and from the map's own
 * traffic-light strings on the map, and those two have to come out as the same colour.
 */
internal fun parseHexColor(hex: String): Color = runCatching {
    Color(hex.removePrefix("#").toLong(16) or 0xFF000000L)
}.getOrDefault(Color.Gray)
