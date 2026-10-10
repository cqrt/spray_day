package nz.mckenzie.sprayday.domain.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Test

/**
 * The place a `FlocID` names, and the name an imported track is given.
 *
 * The map is the app's own - DOC publishes no key for its functional-location prefixes - so what is
 * held still here is that it reads case-insensitively, that an unknown prefix gives no place rather
 * than a wrong one, and that a track already named after its place is not named twice.
 */
class DocPlacesTest {

    @Test
    fun `a known prefix names a place, whatever its case`() {
        assertEquals("Catlins Coast", DocPlaces.placeFor("CATLNSCO-TK11"))
        assertEquals("Bluff Hill", DocPlaces.placeFor("bluffhil-TK02"))
    }

    @Test
    fun `an unknown prefix or none is no place rather than a wrong one`() {
        assertNull(DocPlaces.placeFor("SOMEWHERE-TK01"))
        assertNull(DocPlaces.placeFor("NOCH"))
        assertNull(DocPlaces.placeFor(null))
        assertNull(DocPlaces.placeFor(""))
    }

    @Test
    fun `a track is named with its place first`() {
        assertEquals(
            "Catlins Coast Historic House Walk",
            DocPlaces.assetName("Historic House Walk", "CATLNSCO-TK11")
        )
        assertEquals(
            "an unknown site keeps the name DOC gave it",
            "Glory Tk",
            DocPlaces.assetName("Glory Tk", "SOMEWHERE-TK01")
        )
        assertEquals("Glory Tk", DocPlaces.assetName("Glory Tk", null))
    }

    @Test
    fun `a track already named after its place is not named twice`() {
        assertEquals("Foveaux Walkway", DocPlaces.assetName("Foveaux Walkway", "FOVEAUXW-TK01"))
    }
}
