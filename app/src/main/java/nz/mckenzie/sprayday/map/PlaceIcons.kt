package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape

/**
 * The picture the map draws a place with: the kind's own shape, in the colour its traffic light says.
 *
 * A place - a trough, a shelter, a picnic table, what the drawing screen calls "just one spot" - used
 * to be a house whatever it was, which said where it was and, wrongly, that it had a roof. The
 * picture is now the glyph the asset's own row carries: a building is a roof over walls, a sign a
 * plate on a post, a bench seat two bars over legs, a picnic table one top on splayed legs, and an
 * other place a dot in a ring. The list and the map agree about what the operator is looking at.
 *
 * **The colour is the traffic light's, not the family's.** Beside a name the colour says what kind of
 * thing it is and a separate dot says when it is due; a marker on a map is the only thing there, so
 * it carries the due colour and the *shape* carries the kind. A place sprayed this week is green, one
 * due soon is amber and one never sprayed is red, which is the same rule the lines follow.
 *
 * One image per shape **and** colour rather than one picture tinted per feature: MapLibre can only
 * recolour a picture that has been turned into a signed distance field, and a marker drawn as a plain
 * picture keeps a crisp edge at the size it is actually drawn. Which picture a place wears is decided
 * here, in plain Kotlin, so it is covered by a JVM test rather than by looking at a map.
 */
object PlaceIcons {

    /**
     * The five kinds that are a single spot, in the order the picker offers them.
     *
     * Read off [AssetKind.shape] rather than listed again: the kind decides the shape, so this cannot
     * disagree with the picker about which kinds are places.
     */
    val KINDS: List<AssetKind> = AssetKind.entries.filter { it.shape == AssetShape.POINT }

    /**
     * How big a place is drawn, in density-independent pixels.
     *
     * Bigger than the dot it replaced, which was 16 of them across: these glyphs have legs, posts and
     * a ring to read, and at the size of a dot all of it is a smudge.
     */
    const val MARKER_DP = 22f

    /**
     * The white edge drawn around a marker, in density-independent pixels.
     *
     * It is the dot's white ring, kept: a red marker over dark winter imagery is otherwise a dark
     * shape on a dark ground, which is exactly when somebody is looking for it.
     */
    const val MARKER_OUTLINE_DP = 2f

    /** The colour of that edge. Its own constant because it is the same white everywhere. */
    const val MARKER_OUTLINE = "#FFFFFF"

    /**
     * The colours a place can wear - one per traffic light - plus the grey that anything unexpected
     * is drawn in.
     *
     * A place the app has not worked out a due date for, and a picture asked for by a colour nothing
     * was drawn for, both end up grey rather than invisible.
     */
    val COLORS: List<String> = listOf(
        AssetColors.GREEN,
        AssetColors.YELLOW,
        AssetColors.RED,
        AssetColors.UNKNOWN
    )

    /** Every image a place can ask for: one per kind, for each colour. */
    val IMAGE_NAMES: List<String> = KINDS.flatMap { kind -> COLORS.map { nameOf(kind, it) } }

    /** The grey ring: what a kind or a colour nothing was drawn for falls back to. */
    val FALLBACK_IMAGE_NAME: String = nameOf(AssetKind.OTHER_PLACE, AssetColors.UNKNOWN)

    /**
     * The image for a place of [kind] wearing [colorHex].
     *
     * Takes the colour the map layer colours lines with, so a place and a line that are both due read
     * as the same colour. A colour no picture was drawn for gets the same kind in grey - the shape
     * still says what the thing is, which is more than a grey ring would - and a kind that is not a
     * place at all gets the grey ring, because a line has no marker to draw.
     */
    fun imageName(kind: AssetKind, colorHex: String): String {
        if (kind !in KINDS) return FALLBACK_IMAGE_NAME
        val name = nameOf(kind, colorHex.lowercase())
        return if (IMAGE_NAMES.contains(name)) name else nameOf(kind, AssetColors.UNKNOWN)
    }

    /**
     * The kind and the colour an image name was made from, or null when it names no picture.
     *
     * Searched rather than un-picked from the name: [imageName] is the rule, and a second rule for
     * reading a name back would be a second place for the two to disagree - which is the mistake this
     * file exists to avoid. The desk asks for a picture by the name it was given, and the phone has to
     * know which kind and colour that name means.
     */
    fun ofImageName(name: String): Pair<AssetKind, String>? {
        KINDS.forEach { kind ->
            COLORS.forEach { colorHex ->
                if (imageName(kind, colorHex) == name) return kind to colorHex
            }
        }
        return null
    }

    /**
     * The image name for a kind and a colour, whether or not one was drawn for it.
     *
     * Named from the kind and the colour themselves so the three cannot drift apart: a picture that
     * was drawn for a kind in a colour is the picture that kind in that colour asks for, with nothing
     * in between to get wrong.
     */
    private fun nameOf(kind: AssetKind, colorHex: String): String =
        IMAGE_PREFIX + kind.name.lowercase().replace('_', '-') +
            "-" + colorHex.removePrefix("#").lowercase()

    private const val IMAGE_PREFIX = "sprayday-place-"
}
