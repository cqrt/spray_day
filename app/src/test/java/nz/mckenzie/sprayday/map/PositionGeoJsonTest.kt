package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.geo.haversineMeters
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The marker's GeoJSON.
 *
 * Two promises to somebody standing in a paddock are pinned down here. The ring around the dot
 * is the accuracy **in metres on the ground**, not a circle of screen pixels pretending to be
 * one - because a fix under trees is out by tens of metres, and a dot the same size as a good
 * one would claim a certainty the phone does not have. And a fix that carries no accuracy gets
 * no ring at all: an unknown accuracy is not a small one.
 */
class PositionGeoJsonTest {

    private val fix = GeoPoint(lat = -41.5, lng = 173.95, accuracyM = 12f)

    @Test
    fun `a phone that cannot say where it is draws nothing`() {
        assertEquals(PositionGeoJson.EMPTY, PositionGeoJson.build(null))
    }

    @Test
    fun `a fix is a dot, at the coordinates the map expects`() {
        val json = PositionGeoJson.build(fix)

        assertTrue(json, json.contains("\"part\":\"${PositionGeoJson.PART_DOT}\""))
        assertTrue("GeoJSON says longitude first", json.contains("[173.9500000,-41.5000000]"))
    }

    @Test
    fun `the ring around the dot is the accuracy, measured on the ground`() {
        val ring = ringVertices(PositionGeoJson.build(fix))

        assertEquals("forty-eight points, and the one that closes them", 49, ring.size)
        assertEquals("a GeoJSON polygon closes on itself", ring.first(), ring.last())
        ring.forEach { vertex ->
            val metres = haversineMeters(fix.lat, fix.lng, vertex.second, vertex.first)
            assertEquals("every vertex is the accuracy away", 12.0, metres, 0.5)
        }
    }

    @Test
    fun `a loose fix gets a bigger ring than a tight one, by the same ratio`() {
        val tight = ringVertices(PositionGeoJson.build(fix.copy(accuracyM = 2f))).first()
        val loose = ringVertices(PositionGeoJson.build(fix.copy(accuracyM = 200f))).first()

        assertEquals(2.0, haversineMeters(fix.lat, fix.lng, tight.second, tight.first), 0.5)
        assertEquals(200.0, haversineMeters(fix.lat, fix.lng, loose.second, loose.first), 5.0)
    }

    @Test
    fun `no accuracy, no ring`() {
        val json = PositionGeoJson.build(fix.copy(accuracyM = null))

        assertTrue(json, json.contains("\"part\":\"${PositionGeoJson.PART_DOT}\""))
        assertFalse(
            "a dot with no ring is the honest thing to draw: $json",
            json.contains("\"part\":\"${PositionGeoJson.PART_ACCURACY}\"")
        )
    }

    @Test
    fun `a zero accuracy is nothing, not a ring of no size`() {
        val json = PositionGeoJson.build(fix.copy(accuracyM = 0f))

        assertFalse(json, json.contains("Polygon"))
    }

    /** The polygon's vertices as (lng, lat), read back out of the JSON the map is handed. */
    private fun ringVertices(json: String): List<Pair<Double, Double>> {
        val polygonAt = json.indexOf("\"Polygon\"")
        assertTrue("no ring in $json", polygonAt >= 0)

        val open = json.indexOf("[[", polygonAt)
        val close = json.indexOf("]]", open)
        return json.substring(open + 2, close)
            .split("],[")
            .map { pair ->
                val (lng, lat) = pair.removePrefix("[").removeSuffix("]").split(",")
                lng.trim().toDouble() to lat.trim().toDouble()
            }
    }
}
