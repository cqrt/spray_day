package nz.mckenzie.sprayday.ui.screens

import androidx.compose.foundation.Canvas
import androidx.compose.foundation.layout.size
import androidx.compose.runtime.Composable
import androidx.compose.ui.Modifier
import androidx.compose.ui.semantics.contentDescription
import androidx.compose.ui.semantics.semantics
import androidx.compose.ui.unit.Dp
import androidx.compose.ui.unit.dp
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.map.AssetColors
import nz.mckenzie.sprayday.map.GlyphStyle
import nz.mckenzie.sprayday.map.drawKindGlyph

/**
 * A small coloured glyph saying what an asset is: eight types, one shape each.
 *
 * The shapes live in [drawKindGlyph] (`map/KindGlyphs.kt`) rather than here, because the map's
 * markers are the same shapes at a marker's weight - fatter, filled, with a white edge - and a bench
 * drawn twice from two pieces of code is a bench that will one day look like something else in one
 * of the two places.
 *
 * Drawn rather than shipped as an icon font or a set of images: the app has no icon dependency and
 * does not need one for eight shapes, and these only mean anything as a set.
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
    Canvas(
        modifier = modifier
            .size(dimension)
            .semantics { contentDescription = AssetPhrase.kind(kind) }
    ) {
        drawKindGlyph(kind, AssetColors.forKind(kind), GlyphStyle.LIST)
    }
}

