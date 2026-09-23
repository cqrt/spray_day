package nz.mckenzie.sprayday.domain.asset

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The layers of the work the map can be asked to leave out.
 *
 * Two promises are pinned here, and both are made to somebody who has never opened the sheet: that
 * a map nobody has touched draws everything, and that what is stored is what is *hidden* - which
 * is why a layer added in a later build arrives on the map rather than missing from it.
 */
class AssetLayerTest {

    @Test
    fun `nothing stored hides nothing, so a fresh install draws everything`() {
        assertTrue(AssetLayer.hiddenIn(null).isEmpty())
        assertTrue(AssetLayer.hiddenIn(emptySet()).isEmpty())
    }

    @Test
    fun `the ids a preference stores are unique and never blank`() {
        val ids = AssetLayer.ALL.map { it.id }

        assertEquals("two layers sharing an id would hide each other", ids.size, ids.toSet().size)
        assertFalse(ids.any { it.isBlank() })
    }

    @Test
    fun `a stored id reads back as the layer it names`() {
        assertEquals(setOf(AssetLayer.ROADS), AssetLayer.hiddenIn(setOf("roads")))
        assertEquals(
            setOf(AssetLayer.TRACKS, AssetLayer.PLACES),
            AssetLayer.hiddenIn(setOf("tracks", "places"))
        )
    }

    @Test
    fun `an id this build does not know is ignored rather than hiding something`() {
        // A preference written by a build that had a layer this one does not.
        assertTrue(AssetLayer.hiddenIn(setOf("helicopter-pads")).isEmpty())
        assertEquals(setOf(AssetLayer.TRACKS), AssetLayer.hiddenIn(setOf("tracks", "quarry")))
    }

    @Test
    fun `there is a switch for each line the map draws, and one for every kind of place`() {
        assertEquals(
            "one per line style, plus the houses",
            4,
            AssetLayer.ALL.size
        )
        assertEquals(
            setOf(AssetLayer.TRACKS, AssetLayer.ROADS, AssetLayer.FENCELINES, AssetLayer.PLACES),
            AssetLayer.ALL.toSet()
        )
        // The eight kinds are finer than the four switches on purpose: five kinds of place share the
        // one layer, because what tells a trough from a table is its own glyph, not a switch.
        assertTrue(
            "the kinds should outnumber the switches, or the types bought nothing",
            AssetKind.entries.size > AssetLayer.ALL.size
        )
    }

    @Test
    fun `every switch says what it is and what it does`() {
        AssetLayer.ALL.forEach { layer ->
            assertTrue("${layer.name} has nothing to show on the switch", layer.displayName.isNotBlank())
            assertTrue("${layer.name} has no line to explain it", layer.summary.isNotBlank())
        }
    }
}
