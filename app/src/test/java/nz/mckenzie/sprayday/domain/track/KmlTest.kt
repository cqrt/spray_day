package nz.mckenzie.sprayday.domain.track

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a KML file is read as: the same paths a GPX file hands back, so the one join rule in
 * [TrackInterchange] can mean the same thing by either.
 *
 * The trap KML brings that GPX does not is the order of the numbers - KML's `<coordinates>` are
 * `lon,lat`, GPX's `trkpt` attributes are `lat`/`lon` - so a reader that took them the same way
 * would put a track in the Southern Ocean and nowhere near the file's own ground. That is the first
 * thing pinned here. The rest is that every shape a place is written with - a line, a polygon's ring,
 * a Google Earth `<gx:Track>` - arrives as a path.
 */
class KmlTest {

    private fun read(xml: String) = (TrackInterchange.read(xml) as TrackInterchange.Outcome.Read).reading

    @Test
    fun `a linestring's coordinates are lon lat, so the point is not flipped`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
              <name>Fence</name>
              <LineString><coordinates>174.0,-41.0 174.1,-41.1</coordinates></LineString>
            </Placemark></Document></kml>
        """.trimIndent()

        val reading = read(xml)

        assertEquals(1, reading.paths.size)
        assertEquals(GeoPoint(lat = -41.0, lng = 174.0), reading.paths[0][0])
        assertEquals(GeoPoint(lat = -41.1, lng = 174.1), reading.paths[0][1])
    }

    @Test
    fun `coordinates split on any whitespace, newlines and tabs included`() {
        val xml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
              <LineString><coordinates>
                174.0,-41.0,12
                174.1,-41.1,14
              </coordinates></LineString>
            </Placemark></Document></kml>
        """.trimIndent()

        val reading = read(xml)

        assertEquals(2, reading.paths[0].size)
        assertEquals(12.0, reading.paths[0][0].altitudeM)
        assertEquals(14.0, reading.paths[0][1].altitudeM)
    }

    @Test
    fun `a polygon is read as the ring around its ground`() {
        val xml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
              <Polygon><outerBoundaryIs><LinearRing><coordinates>
                174.0,-41.0 174.1,-41.0 174.1,-41.1 174.0,-41.0
              </coordinates></LinearRing></outerBoundaryIs></Polygon>
            </Placemark></Document></kml>
        """.trimIndent()

        val reading = read(xml)

        assertEquals(1, reading.paths.size)
        assertEquals(4, reading.paths[0].size)
        assertEquals(
            "the ring is closed: the last point is the first",
            reading.paths[0].first(),
            reading.paths[0].last()
        )
    }

    @Test
    fun `a second line starting on the first becomes a side track, exactly as GPX would`() {
        val xml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
              <Placemark><LineString><coordinates>
                174.0,-41.0 174.1,-41.1
              </coordinates></LineString></Placemark>
              <Placemark><LineString><coordinates>
                174.1,-41.1 174.2,-41.1
              </coordinates></LineString></Placemark>
            </Document></kml>
        """.trimIndent()

        val reading = read(xml)

        assertEquals("the line and its side track", 2, reading.paths.size)
        assertEquals(1, reading.sideTracks)
        assertFalse(reading.segmentsDidNotMeet)
        assertEquals(
            "and the junction is the same two numbers in both paths",
            reading.paths[0].last(),
            reading.paths[1].first()
        )
    }

    @Test
    fun `lines that do not meet are joined into one line and said so`() {
        val xml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document>
              <Placemark><LineString><coordinates>174.0,-41.0 174.1,-41.1</coordinates></LineString></Placemark>
              <Placemark><LineString><coordinates>175.0,-42.0 175.1,-42.1</coordinates></LineString></Placemark>
            </Document></kml>
        """.trimIndent()

        val reading = read(xml)

        assertEquals(1, reading.paths.size)
        assertEquals(4, reading.paths[0].size)
        assertEquals(0, reading.sideTracks)
        assertTrue(reading.segmentsDidNotMeet)
    }

    @Test
    fun `a Google Earth track takes its times from the when elements beside its coords`() {
        // gx:coord is `lon lat alt`, and each one takes the time of the gx:when in the same position.
        val xml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"
                 xmlns:gx="http://www.google.com/kml/ext/2.2"><Document><Placemark>
              <gx:Track>
                <when>2026-09-13T05:41:33Z</when>
                <when>2026-09-13T05:41:35Z</when>
                <gx:coord>174.0 -41.0 12</gx:coord>
                <gx:coord>174.1 -41.1 14</gx:coord>
              </gx:Track>
            </Placemark></Document></kml>
        """.trimIndent()

        val reading = read(xml)

        assertEquals(2, reading.paths[0].size)
        assertEquals(-41.0, reading.paths[0][0].lat, 0.0)
        assertEquals(174.0, reading.paths[0][0].lng, 0.0)
        assertEquals(1_789_278_093_000L, reading.paths[0][0].timeMs)
        assertEquals(12.0, reading.paths[0][0].altitudeM)
        assertEquals(1_789_278_095_000L, reading.paths[0][1].timeMs)
    }

    @Test
    fun `a file that is not a line at all is still refused in words`() {
        val xml = """
            <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
              <Point><coordinates>174.0,-41.0</coordinates></Point>
            </Placemark></Document></kml>
        """.trimIndent()

        // A KML Point is a place, not a track, and this import makes a track: nothing to read is the
        // same refusal a GPX file with no track in it gets.
        assertEquals(
            TrackInterchange.Outcome.Invalid(TrackInterchange.TOO_SHORT),
            TrackInterchange.read(xml)
        )
    }
}
