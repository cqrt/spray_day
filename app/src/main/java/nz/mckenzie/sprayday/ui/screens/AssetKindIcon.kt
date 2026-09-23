package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.geometry.Size
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.StrokeCap
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.map.AssetColors

/**
 * A small coloured glyph saying what an asset is: eight types, one shape each.
 *
 * Drawn rather than shipped as an icon font or a set of images: the app has no icon dependency and
 * does not need one for eight shapes, and these only mean anything as a set - a curve, a pair of
 * edges, a fence, a roofline, a plate on a post, a bench, a table, a ring - which is easier to keep
 * honest in one place than across eight PNGs at three densities.
 *
 * What each one leans on to be told apart at twenty pixels is commented beside it, because that is
 * the thing reading the code cannot recover: two glyphs that are the same shape at this size is a
 * list where the operator cannot tell a sign from a seat.
 *
 * The colour is the family's, never the kind's and never the traffic light's. Colour says whether a
 * thing is a line to travel along or a place to stop at; the glyph says which kind it is. The dot
 * beside it says when it is due, and none of the three may be confusable with another.
 */
@Composable
fun AssetKindIcon(
    kind: AssetKind,
    modifier: Modifier = Modifier,
    dimension: Dp = 20.dp
) {
    val color = parseHexColor(AssetColors.forKind(kind))
    Canvas(
        modifier = modifier
            .size(dimension)
            .semantics { contentDescription = AssetPhrase.kind(kind) }
    ) {
        val stroke = size.minDimension * 0.13f
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
                drawRect(
                    color = color,
                    topLeft = Offset(x(0.26f), y(0.50f)),
                    size = Size(width * 0.48f, height * 0.38f),
                    style = Stroke(width = stroke)
                )
            }

            // A plate on one post - and the plate is blank on purpose: anything written on it is
            // mush at this size, and a sign nobody can read is worse than a plain one.
            AssetKind.SIGN -> {
                drawRect(
                    color = color,
                    topLeft = Offset(x(0.16f), y(0.10f)),
                    size = Size(width * 0.68f, height * 0.30f),
                    style = Stroke(width = stroke)
                )
                line(0.50f, 0.40f, 0.50f, 0.90f)
            }

            // A backrest and a seat over straight legs. The two bars are 0.42 of the box apart, which
            // is eight pixels at twenty with a 2.6 pixel stroke - the least that reads as two bars
            // rather than one smear. A first draft had them 0.28 apart, and did not.
            AssetKind.BENCH -> {
                line(0.18f, 0.14f, 0.82f, 0.14f)
                line(0.14f, 0.56f, 0.86f, 0.56f)
                line(0.28f, 0.14f, 0.28f, 0.90f)
                line(0.72f, 0.14f, 0.72f, 0.90f)
            }

            // One top on splayed legs. Straight legs say bench and splayed legs say table, and at
            // this size that is the whole difference between the two.
            AssetKind.TABLE -> {
                line(0.14f, 0.34f, 0.86f, 0.34f)
                line(0.42f, 0.34f, 0.20f, 0.88f)
                line(0.58f, 0.34f, 0.80f, 0.88f)
            }

            // A dot in a ring: something is here, and nothing beyond that. The house this used to
            // wear belongs to the building now: a trough wearing a house was the app claiming a roof
            // nobody had recorded.
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
}
