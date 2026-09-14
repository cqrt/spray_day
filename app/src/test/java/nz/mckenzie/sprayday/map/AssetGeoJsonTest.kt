package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class AssetGeoJsonTest {

    private val wellingtonLine = listOf(
        GeoPoint(-41.2865, 174.7762),
        GeoPoint(-41.2866, 174.7763)
    )

    private fun line(name: String = "Track 4", color: String = AssetColors.GREEN) =
        AssetLine(assetId = 7L, name = name, colorHex = color, points = wellingtonLine)

    @Test
    fun `no tracks produces an empty feature collection`() {
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", AssetGeoJson.build(emptyList()))
    }

    @Test
    fun `tracks with fewer than two points are not drawn`() {
        val single = AssetLine(1L, "Stub", AssetColors.GREEN, listOf(GeoPoint(-41.0, 174.0)))

        assertFalse(AssetGeoJson.build(listOf(single)).contains("Feature\",\"propert"))
    }

    @Test
    fun `a track becomes a linestring feature with its id and colour`() {
        val json = AssetGeoJson.build(listOf(line()))

        assertTrue(json.contains("\"type\":\"LineString\""))
        assertTrue(json.contains("\"id\":7"))
        assertTrue(json.contains("\"stroke\":\"${AssetColors.GREEN}\""))
        assertTrue(json.contains("\"name\":\"Track 4\""))
    }

    @Test
    fun `coordinates are written longitude first as geojson requires`() {
        val json = AssetGeoJson.build(listOf(line()))

        assertTrue(json.contains("[174.7762000,-41.2865000]"))
    }

    @Test
    fun `both points of the line are present`() {
        val json = AssetGeoJson.build(listOf(line()))

        assertEquals(2, Regex("\\[-?\\d+\\.\\d+,-?\\d+\\.\\d+\\]").findAll(json).count())
    }

    @Test
    fun `multiple tracks are comma separated features`() {
        val json = AssetGeoJson.build(listOf(line(), line(name = "Track 5", color = AssetColors.RED)))

        assertEquals(2, Regex("\"type\":\"Feature\",").findAll(json).count())
    }

    @Test
    fun `quotes in track names are escaped`() {
        val json = AssetGeoJson.build(listOf(line(name = "Block \"4\" \\ east")))

        assertTrue(json.contains("Block \\\"4\\\" \\\\ east"))
    }

    @Test
    fun `status maps to the traffic light colours`() {
        assertEquals(AssetColors.GREEN, AssetColors.forStatus(DueStatus.NOT_DUE))
        assertEquals(AssetColors.YELLOW, AssetColors.forStatus(DueStatus.DUE_SOON))
        assertEquals(AssetColors.RED, AssetColors.forStatus(DueStatus.OVERDUE))
        assertEquals(AssetColors.RED, AssetColors.forStatus(DueStatus.NEVER_SPRAYED))
    }
}
