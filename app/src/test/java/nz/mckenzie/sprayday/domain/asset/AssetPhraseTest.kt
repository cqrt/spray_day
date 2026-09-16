package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Test

/**
 * What an asset is called, and what a stored kind or shape means.
 *
 * A picker with a blank chip is a picker the operator cannot use, so the words are
 * tested for every value rather than only the ones a screen happens to draw today.
 */
class AssetPhraseTest {

    @Test
    fun `every kind has a name, and none of them is blank`() {
        assertEquals("Track", AssetPhrase.kind(AssetKind.TRACK))
        assertEquals("Road", AssetPhrase.kind(AssetKind.ROAD))
        assertEquals("Fenceline", AssetPhrase.kind(AssetKind.FENCELINE))
        assertEquals("Infrastructure", AssetPhrase.kind(AssetKind.INFRASTRUCTURE))
    }

    @Test
    fun `the shape choices read as sentences rather than as geometry`() {
        assertEquals("Follows a path", AssetPhrase.shapeChoice(AssetShape.LINE))
        assertEquals("Just one spot", AssetPhrase.shapeChoice(AssetShape.POINT))
    }

    @Test
    fun `both pickers offer everything there is, in enum order`() {
        assertEquals(AssetKind.entries.toList(), AssetPhrase.kinds)
        assertEquals(AssetShape.entries.toList(), AssetPhrase.shapes)
    }

    @Test
    fun `a stored kind is read back as itself`() {
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage("TRACK"))
        assertEquals(AssetKind.ROAD, AssetKind.fromStorage("ROAD"))
        assertEquals(AssetKind.INFRASTRUCTURE, AssetKind.fromStorage("INFRASTRUCTURE"))
    }

    @Test
    fun `an unknown kind reads as a track, because that is what every old asset is`() {
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage(null))
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage(""))
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage("PADDOCK"))
    }

    @Test
    fun `an unknown shape reads as a line`() {
        assertEquals(AssetShape.LINE, AssetShape.fromStorage(null))
        assertEquals(AssetShape.LINE, AssetShape.fromStorage("SQUIGGLE"))
        assertEquals(AssetShape.POINT, AssetShape.fromStorage("POINT"))
    }
}
