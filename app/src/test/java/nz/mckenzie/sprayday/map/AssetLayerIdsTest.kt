package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetLayer
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * Which style layer each switch hides.
 *
 * The link between a switch and the layer behind it is the one thing here that no compiler checks:
 * the layer is built in the map view and the switch is built in a sheet, and the two only meet
 * through this table. What is pinned is that every layer an operator can hide has a layer to hide,
 * that no two switches share one - two switches hiding one layer would make one of them a lie -
 * and that the names are the ones the map itself builds.
 */
class AssetLayerIdsTest {

    @Test
    fun `every layer an operator can hide has a style layer behind it`() {
        AssetLayer.ALL.forEach { layer ->
            assertTrue(
                "${layer.displayName} would switch nothing",
                AssetLayerIds.of(layer).isNotBlank()
            )
        }
    }

    @Test
    fun `no two switches hide the same layer`() {
        val ids = AssetLayer.ALL.map { AssetLayerIds.of(it) }

        assertEquals(
            "two switches hiding one layer makes one of them a lie: $ids",
            ids.size,
            ids.toSet().size
        )
    }

    @Test
    fun `a place is not a line, and is not drawn by a line's layer`() {
        val lineIds = AssetLayer.ALL
            .filter { it != AssetLayer.PLACES }
            .map { AssetLayerIds.of(it) }

        assertFalse(
            "hiding places must not take a line with it",
            lineIds.contains(AssetLayerIds.PLACES)
        )
    }

    @Test
    fun `the names are the map's own, so renaming a layer without this is a failing test`() {
        assertEquals("sprayday-assets-line-track", AssetLayerIds.of(AssetLayer.TRACKS))
        assertEquals("sprayday-assets-line-road", AssetLayerIds.of(AssetLayer.ROADS))
        assertEquals("sprayday-assets-line-infrastructure", AssetLayerIds.of(AssetLayer.FENCELINES))
        assertEquals("sprayday-assets-point", AssetLayerIds.of(AssetLayer.PLACES))
    }
}
