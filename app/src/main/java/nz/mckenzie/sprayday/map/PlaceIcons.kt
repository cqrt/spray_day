package nz.mckenzie.sprayday.map

/**
 * The picture the map draws a place with: a house, in the colour its traffic light says.
 *
 * A place - a trough, a shelter, a picnic table, what the drawing screen calls "just one
 * spot" - used to be a dot, which said where it was and nothing about what it was. A house
 * says both, and it is the same picture the asset's own row already carries beside its name,
 * so the list and the map agree about what the operator is looking at.
 *
 * The colour is still the traffic light's, not the kind's: a house sprayed this week is
 * green, one due soon is amber, and one that has never been sprayed is red - the same rule
 * the lines follow, because it is the same question.
 *
 * One image per colour rather than one image tinted per feature: MapLibre can only recolour
 * a picture that has been turned into a signed distance field, and a house drawn as a plain
 * picture keeps a crisp roof, walls and white edge at the size it is actually drawn. Which
 * picture a place wears is decided here, in plain Kotlin, so it is covered by a JVM test
 * rather than by looking at a map.
 */
object PlaceIcons {

    /**
     * How big a place is drawn, in density-independent pixels.
     *
     * Bigger than the dot it replaced, which was 16 of them across: a house has a roof, two
     * walls and a white edge to read, and at the size of a dot all three are a smudge.
     */
    const val HOUSE_DP = 22f

    /**
     * The white edge drawn around a house, in density-independent pixels.
     *
     * It is the dot's white ring, kept: a red house over dark winter imagery is otherwise a
     * dark shape on a dark ground, which is exactly when somebody is looking for it.
     */
    const val HOUSE_OUTLINE_DP = 2f

    /** The colour of that edge. Its own constant because it is the same white everywhere. */
    const val HOUSE_OUTLINE = "#FFFFFF"

    /**
     * The colours a place can wear - one per traffic light - plus the grey that anything
     * unexpected is drawn in.
     *
     * A place the app has not worked out a due date for, and a picture asked for by a colour
     * nothing was drawn for, both end up grey rather than invisible.
     */
    val COLORS: List<String> = listOf(
        AssetColors.GREEN,
        AssetColors.YELLOW,
        AssetColors.RED,
        AssetColors.UNKNOWN
    )

    /** Every image a place can ask for, in the order [COLORS] lists them. */
    val IMAGE_NAMES: List<String> = COLORS.map { nameOf(it) }

    /** The grey house: what a colour nothing was drawn for falls back to. */
    val FALLBACK_IMAGE_NAME: String = nameOf(AssetColors.UNKNOWN)

    /**
     * The image for a place wearing [colorHex].
     *
     * Takes the colour the map layer colours lines with, so a place and a line that are both
     * due read as the same colour. A colour no picture was drawn for gets the grey house: a
     * place drawn in the wrong colour is a wrong answer, and a place not drawn at all is a
     * missing one, which is worse.
     */
    fun houseImageName(colorHex: String): String {
        val name = nameOf(colorHex)
        return if (IMAGE_NAMES.contains(name)) name else FALLBACK_IMAGE_NAME
    }

    /**
     * The image name for a colour, whether or not one was drawn for it.
     *
     * Named from the colour itself so the two cannot drift apart: a picture that was drawn
     * for a colour is the picture that colour asks for, with nothing in between to get wrong.
     */
    private fun nameOf(colorHex: String): String =
        IMAGE_PREFIX + colorHex.removePrefix("#").lowercase()

    private const val IMAGE_PREFIX = "sprayday-place-"
}
