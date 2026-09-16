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
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.map.AssetColors

/**
 * A small coloured glyph saying what an asset is: a walking track, a road, or a piece
 * of infrastructure - and for infrastructure, which of the two things it is, a
 * fenceline to follow or a place to stop at.
 *
 * Drawn rather than shipped as an icon font or a set of images: the app has no icon
 * dependency and does not need one for four shapes, and these only mean anything as a
 * set - a curve, a pair of edges, a fence, a roofline - which is easier to keep honest
 * in one place than across four PNGs at three densities.
 *
 * The colour is the kind's, never the traffic light's. This says what something is;
 * the dot beside it says when it is due, and the two must not be confusable.
 */
@Composable
fun AssetKindIcon(
    kind: AssetKind,
    shape: AssetShape = AssetShape.LINE,
    modifier: Modifier = Modifier,
    dimension: Dp = 20.dp
) {
    // Infrastructure is spelled out with its shape, because the shape is what the icon
    // is showing: a fence to follow, or a place to stop at.
    val description = if (kind == AssetKind.INFRASTRUCTURE) {
        "${AssetPhrase.kind(kind)}, ${AssetPhrase.shapeChoice(shape).lowercase()}"
    } else {
        AssetPhrase.kind(kind)
    }
    val color = parseHexColor(AssetColors.forKind(kind))
    Canvas(
        modifier = modifier
            .size(dimension)
            .semantics { contentDescription = description }
    ) {
        val stroke = size.minDimension * 0.13f
        val width = size.width
        val height = size.height
        when (kind) {
            // A track wanders, so it is one line with a bend in it.
            AssetKind.TRACK -> {
                val path = Path().apply {
                    moveTo(width * 0.14f, height * 0.86f)
                    cubicTo(
                        width * 0.32f, height * 0.30f,
                        width * 0.68f, height * 0.70f,
                        width * 0.86f, height * 0.14f
                    )
                }
                drawPath(path, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
            }

            // A road has two edges that go somewhere, so it is two straight lines.
            AssetKind.ROAD -> {
                drawLine(
                    color = color,
                    start = Offset(width * 0.22f, height * 0.90f),
                    end = Offset(width * 0.52f, height * 0.10f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
                drawLine(
                    color = color,
                    start = Offset(width * 0.64f, height * 0.90f),
                    end = Offset(width * 0.92f, height * 0.10f),
                    strokeWidth = stroke,
                    cap = StrokeCap.Round
                )
            }

            // Infrastructure is whichever of the two it is: a fenceline you travel along,
            // or a place you stop at. The shape decides, because the operator chose it -
            // which is why a fenceline is infrastructure that follows a path.
            AssetKind.INFRASTRUCTURE -> {
                if (shape == AssetShape.POINT) {
                    val roof = Path().apply {
                        moveTo(width * 0.10f, height * 0.46f)
                        lineTo(width * 0.50f, height * 0.12f)
                        lineTo(width * 0.90f, height * 0.46f)
                    }
                    drawPath(roof, color, style = Stroke(width = stroke, cap = StrokeCap.Round))
                    drawRect(
                        color = color,
                        topLeft = Offset(width * 0.26f, height * 0.50f),
                        size = Size(width * 0.48f, height * 0.38f),
                        style = Stroke(width = stroke)
                    )
                } else {
                    // A fence: posts with rails between them.
                    listOf(0.20f, 0.50f, 0.80f).forEach { x ->
                        drawLine(
                            color = color,
                            start = Offset(width * x, height * 0.16f),
                            end = Offset(width * x, height * 0.90f),
                            strokeWidth = stroke,
                            cap = StrokeCap.Round
                        )
                    }
                    listOf(0.38f, 0.66f).forEach { y ->
                        drawLine(
                            color = color,
                            start = Offset(width * 0.14f, height * y),
                            end = Offset(width * 0.86f, height * y),
                            strokeWidth = stroke * 0.8f,
                            cap = StrokeCap.Round
                        )
                    }
                }
            }
        }
    }
}
