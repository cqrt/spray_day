package nz.mckenzie.sprayday.domain.gpx

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertThrows
import org.junit.Assert.assertTrue
import org.junit.Test

class GpxTest {

    private val points = listOf(
        GeoPoint(lat = -41.2865, lng = 174.7762, altitudeM = 12.5, accuracyM = 5f, timeMs = 1_760_000_000_000L),
        GeoPoint(lat = -41.2866, lng = 174.7763, altitudeM = 13.0, accuracyM = 4f, timeMs = 1_760_000_003_000L),
        GeoPoint(lat = -41.2867, lng = 174.7764, altitudeM = 13.5, accuracyM = 3f, timeMs = 1_760_000_006_000L)
    )

    /** A fix with nothing but coordinates - the shape a bare GPS fix can take. */
    private val barePoint = GeoPoint(lat = -41.0, lng = 174.0)

    @Test
    fun `writer produces a gpx 1_1 document`() {
        val xml = GpxWriter.write("Track 4", listOf(points))
        assertTrue(xml.startsWith("<?xml version=\"1.0\" encoding=\"UTF-8\"?>"))
        assertTrue(xml.contains("version=\"1.1\""))
        assertTrue(xml.contains("xmlns=\"http://www.topografix.com/GPX/1/1\""))
        assertTrue(xml.contains("<name>Track 4</name>"))
        assertEquals(3, Regex("<trkpt ").findAll(xml).count())
    }

    @Test
    fun `writer emits coordinates with seven decimal places`() {
        val xml = GpxWriter.write("Track", listOf(listOf(points[0])))
        assertTrue(xml.contains("lat=\"-41.2865000\""))
        assertTrue(xml.contains("lon=\"174.7762000\""))
    }

    @Test
    fun `writer escapes xml special characters in names`() {
        val xml = GpxWriter.write("Block 4 & 5 <north>", listOf(points))
        assertTrue(xml.contains("Block 4 &amp; 5 &lt;north&gt;"))
        assertTrue(!xml.contains("<north>"))
    }

    @Test
    fun `writer omits optional elements when absent`() {
        val xml = GpxWriter.write("Track", listOf(listOf(barePoint)))
        assertTrue(xml.contains("lat=\"-41.0000000\""))
        assertTrue(!xml.contains("<ele>"))
        assertTrue(!xml.contains("<time>"))
        assertTrue(!xml.contains("<hdop>"))
    }

    @Test
    fun `time is iso 8601 utc whole seconds`() {
        assertEquals("2025-10-09T08:53:20Z", GpxWriter.formatTime(1_760_000_000_000L))
    }

    @Test
    fun `round trip preserves geometry elevation and time`() {
        val parsed = GpxParser.parse(GpxWriter.write("Track 4", listOf(points)))

        assertEquals(points.size, parsed.size)
        points.forEachIndexed { index, original ->
            val actual = parsed[index]
            assertEquals(original.lat, actual.lat, 1e-7)
            assertEquals(original.lng, actual.lng, 1e-7)
            assertEquals(original.altitudeM!!, actual.altitudeM!!, 0.01)
            assertEquals(original.timeMs, actual.timeMs)
            assertEquals(original.accuracyM!!, actual.accuracyM!!, 0.01f)
        }
    }

    @Test
    fun `parses a document with no namespace declaration`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.0" creator="other-tool">
              <trk><trkseg>
                <trkpt lat="-41.0" lon="174.0"><ele>10</ele></trkpt>
                <trkpt lat="-41.1" lon="174.1"><ele>11</ele></trkpt>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        val parsed = GpxParser.parse(xml)
        assertEquals(2, parsed.size)
        assertEquals(-41.0, parsed[0].lat, 1e-9)
        assertEquals(11.0, parsed[1].altitudeM!!, 1e-9)
    }

    @Test
    fun `parses a document with a namespace prefix`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx:gpx xmlns:gpx="http://www.topografix.com/GPX/1/1" version="1.1" creator="other-tool">
              <gpx:trk><gpx:trkseg>
                <gpx:trkpt lat="-41.2" lon="174.2"><gpx:ele>22</gpx:ele><gpx:time>2026-09-13T05:41:33Z</gpx:time></gpx:trkpt>
              </gpx:trkseg></gpx:trk>
            </gpx:gpx>
        """.trimIndent()

        val parsed = GpxParser.parse(xml)
        assertEquals(1, parsed.size)
        assertEquals(22.0, parsed[0].altitudeM!!, 1e-9)
        assertTrue(parsed[0].timeMs > 0L)
    }

    @Test
    fun `points missing coordinates are skipped`() {
        val xml = """
            <?xml version="1.0" encoding="UTF-8"?>
            <gpx version="1.1">
              <trk><trkseg>
                <trkpt lon="174.0"/>
                <trkpt lat="-41.0"/>
                <trkpt lat="-41.0" lon="174.0"/>
              </trkseg></trk>
            </gpx>
        """.trimIndent()

        assertEquals(1, GpxParser.parse(xml).size)
    }

    @Test
    fun `a document with no track points parses to an empty list`() {
        val xml = """<?xml version="1.0"?><gpx version="1.1"><trk><name>empty</name></trk></gpx>"""
        assertTrue(GpxParser.parse(xml).isEmpty())
    }

    @Test
    fun `malformed xml is rejected rather than silently ignored`() {
        assertThrows(Exception::class.java) {
            GpxParser.parse("<gpx version=\"1.1\"><trk><trkseg></gpx>")
        }
    }

    /* ---- A track with a side track, which is what several segments are for --------------- */

    /** A line, and a spur hanging off its far end - the shape the app draws and writes. */
    private val line = listOf(GeoPoint(lat = -41.50, lng = 174.90), GeoPoint(lat = -41.51, lng = 174.91))
    private val spur = listOf(line.last(), GeoPoint(lat = -41.52, lng = 174.91))

    @Test
    fun `writer writes one segment per path, which is what a gpx track's segments are for`() {
        val xml = GpxWriter.write("Gully track", listOf(line, spur))

        assertEquals("the line and its side track", 2, Regex("<trkseg>").findAll(xml).count())
        assertEquals("and every vertex of both", 4, Regex("<trkpt ").findAll(xml).count())
        assertEquals(
            "with the junction in both paths, so a reader sees a track with a spur on it",
            2,
            Regex(Regex.escape("lat=\"-41.5100000\" lon=\"174.9100000\"")).findAll(xml).count()
        )
    }

    @Test
    fun `a written track with a side track reads back as the same two paths`() {
        val xml = GpxWriter.write("Gully track", listOf(line, spur))

        val read = GpxParser.parseSegments(xml)

        assertEquals(2, read.size)
        listOf(line, spur).forEachIndexed { pathIndex, expected ->
            assertEquals("path $pathIndex has the same vertices", expected.size, read[pathIndex].size)
            expected.forEachIndexed { index, vertex ->
                assertEquals(vertex.lat, read[pathIndex][index].lat, 1e-7)
                assertEquals(vertex.lng, read[pathIndex][index].lng, 1e-7)
            }
        }
        assertEquals(
            "and the junction is the same two numbers in both, which is what the rules check",
            read[1].first(),
            read[0].last()
        )
    }

    @Test
    fun `the segments of a gpx are read as paths, in the order the file has them`() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk>
              <trkseg><trkpt lat="-41.0" lon="174.0"/><trkpt lat="-41.1" lon="174.1"/></trkseg>
              <trkseg><trkpt lat="-41.1" lon="174.1"/><trkpt lat="-41.2" lon="174.1"/></trkseg>
            </trk></gpx>
        """.trimIndent()

        val paths = GpxParser.parseSegments(xml)

        assertEquals(2, paths.size)
        assertEquals(-41.0, paths[0].first().lat, 1e-9)
        assertEquals(-41.2, paths[1].last().lat, 1e-9)
    }

    @Test
    fun `a file that never says where a segment ends is one path`() {
        // Some tools write every point loose under the track, and the app has always read those as one
        // line.
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.0"><trk>
              <trkpt lat="-41.0" lon="174.0"/><trkpt lat="-41.1" lon="174.1"/>
            </trk></gpx>
        """.trimIndent()

        assertEquals(1, GpxParser.parseSegments(xml).size)
    }

    @Test
    fun `an empty segment is not a path`() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.1"><trk>
              <trkseg><trkpt lat="-41.0" lon="174.0"/><trkpt lat="-41.1" lon="174.1"/></trkseg>
              <trkseg></trkseg>
            </trk></gpx>
        """.trimIndent()

        assertEquals("a segment with nothing in it is dropped rather than kept empty", 1, GpxParser.parseSegments(xml).size)
    }

    @Test
    fun `every point still comes out as one line, which is what an import has always read`() {
        val xml = GpxWriter.write("Gully track", listOf(line, spur))

        assertEquals(4, GpxParser.parse(xml).size)
    }
}
