package nz.mckenzie.sprayday.map

import android.content.Context

/**
 * The letter shapes the map writes track names with, and where they are handed out from.
 *
 * A map does not write text from a font installed on the phone: it draws it from little files, one per
 * range of letter codes, and one set of files per lettering. So the app ships a set - Noto Sans, which
 * is free to pass on under the Open Font Licence, with its copyright line kept beside it - and serves
 * the files from the same little server the tiles come from. That is what makes a name appear with no
 * network at all, and what makes the desk draw its names from the phone rather than from somewhere
 * else.
 *
 * Three ranges travel: the everyday letters, the accented ones, and the marks and symbols beside them -
 * which is where the macrons in Māori place names live. A name in another script draws nothing until
 * its range is added here, and adding one is a file and a line in [RANGES].
 */
object GlyphFonts {

    /** The set's own name: what a style asks for in `text-font`, and the folder its files sit in. */
    const val STACK = "Noto Sans Regular"

    /** The ranges shipped, named the way a font URL names them. */
    val RANGES = listOf("0-255", "256-511", "512-767")

    /** Every request for letters begins here: `/fonts/<set>/<range>.pbf`. */
    const val PATH_PREFIX = "/fonts/"

    /**
     * The address a style's own `glyphs` key wants, taken from the address its tiles come from.
     *
     * One address rather than two, so both maps and both of the app's own servers agree about where
     * letters come from without a second URL being threaded through everything - and so the desk asks
     * the phone for its letters for the same reason it asks the phone for its tiles.
     */
    fun urlTemplateFrom(tilesUrlTemplate: String): String =
        tilesUrlTemplate.substringBefore(TILES_MARK) + PATH_PREFIX + "{fontstack}/{range}" + RANGE_SUFFIX

    /**
     * The file a `/fonts/...` request asks for, as its path inside the app - or null when the request
     * is not for one of the ranges this app ships.
     *
     * Judged against the table above rather than by taking the request's word for it: another set, or
     * a range that is not one of the three, is a request nothing serves. That also means a crafted path
     * cannot reach anything else inside the app, which is the same rule the tiles and the marker
     * pictures are judged by.
     */
    fun assetPathFor(requested: String): String? {
        val tail = requested.removePrefix(PATH_PREFIX)
        if (tail == requested) return null
        val parts = tail.split('/')
        if (parts.size != 2) return null

        val stack = decode(parts[0])
        val range = parts[1]
        if (!range.endsWith(RANGE_SUFFIX) || range == RANGE_SUFFIX) return null
        val codes = range.removeSuffix(RANGE_SUFFIX)
        if (stack != STACK || codes !in RANGES) return null
        return "$ASSETS_DIR/$stack/$codes$RANGE_SUFFIX"
    }

    /** One of the shipped ranges, read out of the app's own files - or null when it is not there. */
    fun read(context: Context, assetPath: String): ByteArray? =
        runCatching { context.assets.open(assetPath).use { it.readBytes() } }.getOrNull()

    private fun decode(value: String): String =
        runCatching { java.net.URLDecoder.decode(value, Charsets.UTF_8.name()) }.getOrDefault(value)

    /** Where `/tiles/` sits in a tile address; everything before it is the server's own address. */
    private const val TILES_MARK = "/tiles/"

    private const val ASSETS_DIR = "fonts"

    private const val RANGE_SUFFIX = ".pbf"
}
