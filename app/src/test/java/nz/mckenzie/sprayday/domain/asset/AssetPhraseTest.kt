package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What an asset is called, which shape each type is, and what a stored kind means.
 *
 * A picker with a blank chip is a picker the operator cannot use, so the words are tested for every
 * value rather than only the ones a screen happens to draw today. The shape table is tested for the
 * same kind of reason: the type deciding the shape is the whole point of it, and a kind answering
 * wrongly would draw a picnic table as a line across a paddock with nothing else to notice.
 */
class AssetPhraseTest {

    @Test
    fun `every kind has a name, and none of them is blank`() {
        assertEquals("Track", AssetPhrase.kind(AssetKind.TRACK))
        assertEquals("Road", AssetPhrase.kind(AssetKind.ROAD))
        assertEquals("Fenceline", AssetPhrase.kind(AssetKind.FENCELINE))
        assertEquals("Carpark", AssetPhrase.kind(AssetKind.CARPARK))
        assertEquals("Building", AssetPhrase.kind(AssetKind.BUILDING))
        assertEquals("Sign", AssetPhrase.kind(AssetKind.SIGN))
        assertEquals("Bench seat", AssetPhrase.kind(AssetKind.BENCH))
        assertEquals("Picnic table", AssetPhrase.kind(AssetKind.TABLE))
        assertEquals("Other place", AssetPhrase.kind(AssetKind.OTHER_PLACE))

        AssetKind.entries.forEach { kind ->
            assertTrue("$kind has no name", AssetPhrase.kind(kind).isNotBlank())
        }
    }

    @Test
    fun `the picker offers everything there is, in enum order`() {
        assertEquals(AssetKind.entries.toList(), AssetPhrase.kinds)
    }

    @Test
    fun `three kinds are lines, one is ground, and five are places`() {
        assertEquals(
            setOf(AssetKind.TRACK, AssetKind.ROAD, AssetKind.FENCELINE),
            AssetKind.entries.filter { it.shape == AssetShape.LINE }.toSet()
        )
        assertEquals(
            setOf(AssetKind.CARPARK),
            AssetKind.entries.filter { it.shape == AssetShape.AREA }.toSet()
        )
        assertEquals(
            setOf(
                AssetKind.BUILDING,
                AssetKind.SIGN,
                AssetKind.BENCH,
                AssetKind.TABLE,
                AssetKind.OTHER_PLACE
            ),
            AssetKind.entries.filter { it.shape == AssetShape.POINT }.toSet()
        )
    }

    @Test
    fun `a kind that is ground is asked for the metres round it, and its ground is not called an estimate`() {
        assertEquals("Length", AssetPhrase.lengthLabel(AssetShape.LINE))
        assertEquals("Length", AssetPhrase.lengthLabel(AssetShape.POINT))
        assertEquals("Round it", AssetPhrase.lengthLabel(AssetShape.AREA))

        assertEquals(
            "a line's area is guessed from a swath width, and the words have to say so",
            "about 0.4 ha",
            AssetPhrase.areaPhrase(AssetShape.LINE, "0.4 ha")
        )
        assertEquals(
            "a ring's ground is measured from its own corners",
            "0.4 ha of ground",
            AssetPhrase.areaPhrase(AssetShape.AREA, "0.4 ha")
        )
    }

    @Test
    fun `changing a shape is asked for in that shape's own words`() {
        assertEquals("Change the line", AssetPhrase.changeLabel(AssetShape.LINE))
        assertEquals("Change the line", AssetPhrase.changeLabel(AssetShape.POINT))
        assertEquals(
            "ground is re-fenced, not redrawn - and the drawing screen said line about a carpark",
            "Change the boundary",
            AssetPhrase.changeLabel(AssetShape.AREA)
        )
    }

    @Test
    fun `a stored kind is read back as itself`() {
        AssetKind.entries.forEach { kind ->
            assertEquals(kind, AssetKind.fromStorage(kind.name, kind.shape))
        }
    }

    @Test
    fun `old infrastructure is read by the shape beside it, the only thing that says what it was`() {
        assertEquals(AssetKind.FENCELINE, AssetKind.fromStorage("INFRASTRUCTURE", AssetShape.LINE))
        assertEquals(AssetKind.OTHER_PLACE, AssetKind.fromStorage("INFRASTRUCTURE", AssetShape.POINT))
    }

    @Test
    fun `an unknown kind reads as a track, because that is what every old asset is`() {
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage(null, AssetShape.LINE))
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage("", AssetShape.LINE))
        assertEquals(AssetKind.TRACK, AssetKind.fromStorage("PADDOCK", AssetShape.POINT))
    }

    @Test
    fun `an unknown kind has no meaning at all, which is what a write is refused on`() {
        assertNull(AssetKind.known("PADDOCK", AssetShape.LINE))
        assertNull(AssetKind.known(null, AssetShape.POINT))
        assertNull(AssetKind.known("", AssetShape.LINE))

        assertEquals(AssetKind.SIGN, AssetKind.known("SIGN", AssetShape.POINT))
        assertEquals(AssetKind.FENCELINE, AssetKind.known("INFRASTRUCTURE", AssetShape.LINE))
    }

    @Test
    fun `an unknown shape reads as a line`() {
        assertEquals(AssetShape.LINE, AssetShape.fromStorage(null))
        assertEquals(AssetShape.LINE, AssetShape.fromStorage("SQUIGGLE"))
        assertEquals(AssetShape.POINT, AssetShape.fromStorage("POINT"))
    }
}
