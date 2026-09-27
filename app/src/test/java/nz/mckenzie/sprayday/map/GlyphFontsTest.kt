package nz.mckenzie.sprayday.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * Where the letters for a track's name come from.
 *
 * Two claims matter here. The address a style asks for letters on is built from the address it gets its
 * tiles on, so both maps and both of the app's own servers agree without a second address being passed
 * around. And which files may be asked for is a table rather than whatever a request feels like naming:
 * another lettering, another range, or a path that tries to climb out of the folder is a path nothing
 * serves - which also means the app's own files are not reachable through this door.
 */
class GlyphFontsTest {

    @Test
    fun `letters come from the same address as the tiles`() {
        assertEquals(
            "http://127.0.0.1:41234/fonts/{fontstack}/{range}.pbf",
            GlyphFonts.urlTemplateFrom("http://127.0.0.1:41234/tiles/linz-aerial/{z}/{x}/{y}.webp")
        )
        assertEquals(
            "http://192.168.1.23:8799/fonts/{fontstack}/{range}.pbf",
            GlyphFonts.urlTemplateFrom("http://192.168.1.23:8799/tiles/osm/{z}/{x}/{y}.png")
        )
    }

    @Test
    fun `every range the app ships is served out of its own files`() {
        assertEquals(3, GlyphFonts.RANGES.size)
        GlyphFonts.RANGES.forEach { range ->
            assertEquals(
                "fonts/${GlyphFonts.STACK}/$range.pbf",
                GlyphFonts.assetPathFor("/fonts/Noto%20Sans%20Regular/$range.pbf")
            )
        }
    }

    @Test
    fun `anything else is a path nothing serves`() {
        listOf(
            "/fonts/Another%20Lettering/0-255.pbf",
            "/fonts/Noto%20Sans%20Regular/900-1023.pbf",
            "/fonts/Noto%20Sans%20Regular/0-255.json",
            "/fonts/Noto%20Sans%20Regular",
            "/fonts/Noto%20Sans%20Regular/../../share/secret.pbf",
            "/fonts/../../assets/fonts/Noto%20Sans%20Regular/0-255.pbf",
            "/tiles/linz-aerial/14/1017/660.webp",
            "/"
        ).forEach { path ->
            assertNull("$path would have been served", GlyphFonts.assetPathFor(path))
        }
    }
}
