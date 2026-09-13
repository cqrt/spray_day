package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.due.DueStatus
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

class TrackGeoJsonTest {

    private val wellingtonLine = listOf(
        GeoPoint(-41.2865, 174.7762),
        GeoPoint(-41.2866, 174.7763)
    )

    private fun line(name: String = "Track 4", color: String = TrackColors.GREEN) =
        TrackLine(trackId = 7L, name = name, colorHex = color, points = wellingtonLine)

    @Test
    fun `no tracks produces an empty feature collection`() {
        assertEquals("{\"type\":\"FeatureCollection\",\"features\":[]}", TrackGeoJson.build(emptyList()))
    }

    @Test
    fun `tracks with fewer than two points are not drawn`() {
        val single = TrackLine(1L, "Stub", TrackColors.GREEN, listOf(GeoPoint(-41.0, 174.0)))

        assertFalse(TrackGeoJson.build(listOf(single)).contains("Feature\",\"propert"))
    }

    @Test
    fun `a track becomes a linestring feature with its id and colour`() {
        val json = TrackGeoJson.build(listOf(line()))

        assertTrue(json.contains("\"type\":\"LineString\""))
        assertTrue(json.contains("\"id\":7"))
        assertTrue(json.contains("\"stroke\":\"${TrackColors.GREEN}\""))
        assertTrue(json.contains("\"name\":\"Track 4\""))
    }

    @Test
    fun `coordinates are written longitude first as geojson requires`() {
        val json = TrackGeoJson.build(listOf(line()))

        assertTrue(json.contains("[174.7762000,-41.2865000]"))
    }

    @Test
    fun `both points of the line are present`() {
        val json = TrackGeoJson.build(listOf(line()))

        assertEquals(2, Regex("\\[-?\\d+\\.\\d+,-?\\d+\\.\\d+\\]").findAll(json).count())
    }

    @Test
    fun `multiple tracks are comma separated features`() {
        val json = TrackGeoJson.build(listOf(line(), line(name = "Track 5", color = TrackColors.RED)))

        assertEquals(2, Regex("\"type\":\"Feature\",").findAll(json).count())
    }

    @Test
    fun `quotes in track names are escaped`() {
        val json = TrackGeoJson.build(listOf(line(name = "Block \"4\" \\ east")))

        assertTrue(json.contains("Block \\\"4\\\" \\\\ east"))
    }

    @Test
    fun `status maps to the traffic light colours`() {
        assertEquals(TrackColors.GREEN, TrackColors.forStatus(DueStatus.NOT_DUE))
        assertEquals(TrackColors.YELLOW, TrackColors.forStatus(DueStatus.DUE_SOON))
        assertEquals(TrackColors.RED, TrackColors.forStatus(DueStatus.OVERDUE))
        assertEquals(TrackColors.RED, TrackColors.forStatus(DueStatus.NEVER_SPRAYED))
    }
}
