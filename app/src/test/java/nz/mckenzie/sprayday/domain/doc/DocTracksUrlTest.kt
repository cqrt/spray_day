package nz.mckenzie.sprayday.domain.doc

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The URL that asks DOC's service a question.
 *
 * The two choices worth holding still are that the format asked for is the one the app already
 * reads (`f=geojson`, `outSR=4326`), and that a name goes into the `where` clause as a value rather
 * than as SQL - a quote in a track's name must not be able to end the clause.
 */
class DocTracksUrlTest {

    @Test
    fun `no name is every track, and a name is a case-insensitive contains`() {
        assertEquals("1=1", DocTracksUrl.where(""))
        assertEquals("1=1", DocTracksUrl.where("   "))
        assertEquals(
            "UPPER(TechObjectName) LIKE UPPER('%Ocean%')",
            DocTracksUrl.where("Ocean")
        )
    }

    @Test
    fun `a quote in a name is doubled, so it searches rather than ending the clause`() {
        assertEquals(
            "UPPER(TechObjectName) LIKE UPPER('%O''Hara%')",
            DocTracksUrl.where("O'Hara")
        )
    }

    @Test
    fun `a name search asks for GeoJSON in WGS84 and no geometry filter`() {
        val url = DocTracksUrl.of(DocTrackQuery(nameContains = "Glory"))

        assertTrue(url.startsWith(DocTracksUrl.SERVICE_URL + "?"))
        assertTrue("the format the phone reads", url.contains("f=geojson"))
        assertTrue("ArcGIS reprojects for us", url.contains("outSR=4326"))
        assertTrue(
            url.contains("outFields=" + java.net.URLEncoder.encode(DocTracksUrl.OUT_FIELDS, "UTF-8"))
        )
        assertFalse("no envelope when there is no place", url.contains("geometry="))
    }

    @Test
    fun `a near-me search carries an envelope in WGS84`() {
        val url = DocTracksUrl.of(DocTrackQuery(near = GeoPoint(lat = -46.6, lng = 168.35)))

        assertTrue(url.contains("geometry="))
        assertTrue(url.contains("geometryType=esriGeometryEnvelope"))
        assertTrue(url.contains("inSR=4326"))
        assertTrue(url.contains("spatialRel=esriSpatialRelIntersects"))
        assertTrue("the request is capped", url.contains("resultRecordCount="))
    }

    @Test
    fun `an envelope is the near square, one degree at the equator`() {
        assertEquals(
            "-1.0000000,-1.0000000,1.0000000,1.0000000",
            DocTracksUrl.envelope(GeoPoint(lat = 0.0, lng = 0.0), radiusKm = 111.32)
        )
    }

    @Test
    fun `a map's own view is the envelope, west and south first`() {
        assertEquals(
            "168.1000000,-46.7000000,168.6000000,-46.5000000",
            DocTracksUrl.envelope(DocBounds(minLat = -46.7, minLng = 168.1, maxLat = -46.5, maxLng = 168.6))
        )
    }

    @Test
    fun `a map-view search carries an envelope and needs no place of its own`() {
        val url = DocTracksUrl.of(
            DocTrackQuery(bounds = DocBounds(minLat = -46.7, minLng = 168.1, maxLat = -46.5, maxLng = 168.6))
        )

        assertTrue(url.contains("geometry=168.1000000%2C-46.7000000%2C168.6000000%2C-46.5000000"))
        assertTrue(url.contains("geometryType=esriGeometryEnvelope"))
    }

    @Test
    fun `a page beyond the first asks from where the last ended`() {
        val url = DocTracksUrl.of(
            DocTrackQuery(
                bounds = DocBounds(minLat = -46.7, minLng = 168.1, maxLat = -46.5, maxLng = 168.6),
                limit = 2000,
                offset = 2000
            )
        )

        assertTrue(url.contains("resultOffset=2000"))
        assertTrue(url.contains("resultRecordCount=2000"))
    }

    @Test
    fun `the same radius reaches further in longitude the further south you are`() {
        // A degree of longitude is shorter at this latitude, so the box is wider east-west.
        val envelope = DocTracksUrl.envelope(GeoPoint(lat = -46.6, lng = 168.35), radiusKm = 25.0)
        val (minLng, minLat, maxLng, maxLat) = envelope.split(",").map { it.toDouble() }

        assertTrue("a box around the phone", minLng < 168.35 && maxLng > 168.35)
        assertTrue(minLat < -46.6 && maxLat > -46.6)
        assertTrue("wider than tall, far south", (maxLng - minLng) > (maxLat - minLat))
    }
}
