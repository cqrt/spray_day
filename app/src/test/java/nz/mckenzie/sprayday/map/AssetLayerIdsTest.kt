package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetLayer
import nz.mckenzie.sprayday.domain.asset.AssetShape
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
    fun `every kind of place has its own marker layer, and no line kind shares one`() {
        val placeIds = PlaceIcons.KINDS.map { AssetLayerIds.pointOf(it) }
        val lineIds = AssetLayer.ALL
            .filter { it.kind.shape == AssetShape.LINE }
            .map { AssetLayerIds.of(it) }

        assertEquals("one layer per place kind", 5, placeIds.size)
        assertTrue(
            "a place kind hidden must not take a line with it: $lineIds",
            lineIds.none { it in placeIds }
        )
        assertEquals(
            "and those five are what the place kinds' own switches hide",
            placeIds,
            AssetLayer.ALL.filter { it.kind.shape == AssetShape.POINT }.map { AssetLayerIds.of(it) }
        )
    }

    @Test
    fun `ground with an edge has a layer of its own, and no place or line shares it`() {
        val carpark = AssetLayerIds.of(AssetLayer.CARPARKS)
        val placeIds = PlaceIcons.KINDS.map { AssetLayerIds.pointOf(it) }
        val lineIds = AssetLayer.ALL
            .filter { it.kind.shape == AssetShape.LINE }
            .map { AssetLayerIds.of(it) }

        assertTrue("a carpark is not drawn by a place's marker layer: $carpark", carpark !in placeIds)
        assertTrue("nor by a line's own layer: $carpark", carpark !in lineIds)
    }

    @Test
    fun `the ground's one switch hides both of its layers`() {
        val ground = AssetLayerIds.idsOf(AssetLayer.CARPARKS)

        assertEquals(
            "a surface, and the boundary round it",
            listOf(AssetLayerIds.CARPARKS_FILL, AssetLayerIds.CARPARKS),
            ground
        )
        assertEquals(
            "and the boundary is still the layer the ground is named by",
            AssetLayerIds.CARPARKS,
            AssetLayerIds.of(AssetLayer.CARPARKS)
        )

        val others = AssetLayer.ALL
            .filterNot { it == AssetLayer.CARPARKS }
            .flatMap { AssetLayerIds.idsOf(it) }
        assertTrue("no other switch hides the ground: $others", others.none { it in ground })
    }

    @Test
    fun `every layer the map draws is one a switch hides`() {
        val hidden = AssetLayer.ALL.flatMap { AssetLayerIds.idsOf(it) }

        // Compared as sets, because the two lists are in different orders on purpose: a switch is
        // listed where the operator looks for it, and a layer is drawn where it belongs - the ground's
        // fill under everything, its boundary over the lines.
        assertEquals(
            "a style layer with no switch is a layer an operator cannot hide: $hidden",
            AssetLayerIds.ALL.sorted(),
            hidden.sorted()
        )
        assertEquals(
            "and nothing is drawn twice: ${AssetLayerIds.ALL}",
            AssetLayerIds.ALL.size,
            AssetLayerIds.ALL.toSet().size
        )
    }

    @Test
    fun `the names are the map's own, so renaming a layer without this is a failing test`() {
        assertEquals("sprayday-assets-line-track", AssetLayerIds.of(AssetLayer.TRACKS))
        assertEquals("sprayday-assets-line-road", AssetLayerIds.of(AssetLayer.ROADS))
        assertEquals("sprayday-assets-line-infrastructure", AssetLayerIds.of(AssetLayer.FENCELINES))
        assertEquals("sprayday-assets-area-carpark", AssetLayerIds.of(AssetLayer.CARPARKS))
        assertEquals("sprayday-assets-area-carpark-fill", AssetLayerIds.CARPARKS_FILL)
        assertEquals("sprayday-assets-point-building", AssetLayerIds.of(AssetLayer.BUILDINGS))
        assertEquals("sprayday-assets-point-other-place", AssetLayerIds.of(AssetLayer.OTHER_PLACES))
    }
}
