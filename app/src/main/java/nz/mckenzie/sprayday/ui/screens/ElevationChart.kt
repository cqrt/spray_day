package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.material3.MaterialTheme
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.geometry.Offset
import androidx.compose.ui.graphics.Path
import androidx.compose.ui.graphics.drawscope.Stroke
import androidx.compose.ui.unit.dp
import nz.mckenzie.sprayday.domain.geo.ElevationProfile
import nz.mckenzie.sprayday.domain.geo.ElevationSample

/**
 * A line's height along its own length: the shape of the ground it was walked or driven on.
 *
 * The arithmetic is [ElevationProfile]'s, done once in the domain; this only puts it on the screen,
 * scaling the distance along the line to the width and the height between the lowest and highest
 * points to the height. A line that is flat - the highest point as low as the lowest - is drawn down
 * the middle rather than divided by nothing.
 *
 * It is a shape to read, not a survey: the height came off a device, so the chart is one colour and
 * no numbers live on it. The range is said in words beside it.
 */
@Composable
fun ElevationChart(profile: ElevationProfile, modifier: Modifier = Modifier) {
    val lineColor = MaterialTheme.colorScheme.primary
    val fillColor = MaterialTheme.colorScheme.primary.copy(alpha = 0.18f)
    val samples = profile.samples

    Canvas(modifier = modifier) {
        val first = samples.first()
        val last = samples.last()
        val alongSpan = (last.alongM - first.alongM).takeIf { it > 0.0 } ?: 1.0
        val heightSpan = profile.rangeM.takeIf { it > 0.0 }
        val pad = 4.dp.toPx()
        val usableWidth = (size.width - pad * 2).coerceAtLeast(1f)
        val usableHeight = (size.height - pad * 2).coerceAtLeast(1f)

        fun point(sample: ElevationSample): Offset {
            val x = pad + ((sample.alongM - first.alongM) / alongSpan).toFloat() * usableWidth
            val y = if (heightSpan == null) {
                size.height / 2f
            } else {
                pad + (1f - ((sample.heightM - profile.lowestM) / heightSpan).toFloat()) * usableHeight
            }
            return Offset(x, y)
        }

        val outline = Path()
        val area = Path()
        val start = point(first)
        outline.moveTo(start.x, start.y)
        area.moveTo(start.x, size.height)
        area.lineTo(start.x, start.y)
        for (index in 1 until samples.size) {
            val at = point(samples[index])
            outline.lineTo(at.x, at.y)
            area.lineTo(at.x, at.y)
        }
        area.lineTo(point(last).x, size.height)
        area.close()

        drawPath(area, color = fillColor)
        drawPath(outline, color = lineColor, style = Stroke(width = 2.dp.toPx()))
    }
}
