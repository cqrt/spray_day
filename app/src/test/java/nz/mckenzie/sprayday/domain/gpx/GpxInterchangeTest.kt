package nz.mckenzie.sprayday.domain.gpx

import nz.mckenzie.sprayday.domain.geo.GeoPoint
import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a GPX file is read as, once, for the phone's own importer and the desk's drop alike.
 *
 * The join is the rule worth holding still: a file whose later segments start on the line's own
 * vertices is a track with side tracks, and one whose segments are somewhere else entirely is a
 * single line with a jump in it - the way this app has always read a file from a tool that cuts a
 * line up for its own reasons. Both callers say what happened in their own words, so what is pinned
 * here is the reading itself: the paths, how many side tracks, and whether the segments met.
 */
class GpxInterchangeTest {

    private fun gpx(segments: List<List<Pair<Double, Double>>>): String = buildString {
        append("""<?xml version="1.0"?><gpx version="1.1"><trk><name>Fence</name>""")
        for (segment in segments) {
            append("<trkseg>")
            for ((lat, lon) in segment) append("""<trkpt lat="$lat" lon="$lon"/>""")
            append("</trkseg>")
        }
        append("</trk></gpx>")
    }

    private fun read(xml: String) = (GpxInterchange.read(xml) as GpxInterchange.Outcome.Read).reading

    @Test
    fun `one segment is one line and nothing else`() {
        val reading = read(gpx(listOf(listOf(-41.0 to 174.0, -41.1 to 174.1))))

        assertEquals(1, reading.paths.size)
        assertEquals(2, reading.paths[0].size)
        assertEquals(0, reading.sideTracks)
        assertEquals(false, reading.segmentsDidNotMeet)
    }

    @Test
    fun `a segment starting on the line becomes a side track`() {
        // A fence and the spur into the gully: the second segment starts on the line's own second
        // vertex, to the bit, which is what the app's own drawing produces and its own rules ask for.
        val reading = read(
            gpx(
                listOf(
                    listOf(-41.0 to 174.0, -41.1 to 174.1),
                    listOf(-41.1 to 174.1, -41.2 to 174.1)
                )
            )
        )

        assertEquals("the line and its side track", 2, reading.paths.size)
        assertEquals(1, reading.sideTracks)
        assertEquals(false, reading.segmentsDidNotMeet)
        assertEquals(
            "and the junction is the same two numbers in both paths",
            reading.paths[0].last(),
            reading.paths[1].first()
        )
    }

    @Test
    fun `several segments that all start on the line are that many side tracks`() {
        val reading = read(
            gpx(
                listOf(
                    listOf(-41.0 to 174.0, -41.1 to 174.1, -41.2 to 174.2),
                    listOf(-41.1 to 174.1, -41.15 to 174.1),
                    listOf(-41.2 to 174.2, -41.25 to 174.2)
                )
            )
        )

        assertEquals(3, reading.paths.size)
        assertEquals(2, reading.sideTracks)
        assertEquals(false, reading.segmentsDidNotMeet)
    }

    @Test
    fun `segments that do not meet are joined into one line and said so`() {
        val reading = read(
            gpx(
                listOf(
                    listOf(-41.0 to 174.0, -41.1 to 174.1),
                    listOf(-42.0 to 175.0, -42.1 to 175.1)
                )
            )
        )

        assertEquals("one line, every point in it", 1, reading.paths.size)
        assertEquals(4, reading.paths[0].size)
        assertEquals("and nothing became a side track", 0, reading.sideTracks)
        assertTrue("and the reading says the segments did not meet", reading.segmentsDidNotMeet)
    }

    @Test
    fun `a second segment of one point is not a side track, so the file is joined up instead`() {
        // A path of one point is not a path the app stores, so a stray single fix in a file cannot
        // become a spur: the file is read the way every other unjoinable file is.
        val reading = read(
            gpx(listOf(listOf(-41.0 to 174.0, -41.1 to 174.1), listOf(-41.1 to 174.1)))
        )

        assertEquals(1, reading.paths.size)
        assertEquals(0, reading.sideTracks)
        assertTrue(reading.segmentsDidNotMeet)
    }

    @Test
    fun `a file whose points are loose under the track is one line`() {
        val xml = """
            <?xml version="1.0"?>
            <gpx version="1.0"><trk>
              <trkpt lat="-41.0" lon="174.0"/><trkpt lat="-41.1" lon="174.1"/>
            </trk></gpx>
        """.trimIndent()

        val reading = read(xml)

        assertEquals(1, reading.paths.size)
        assertEquals(false, reading.segmentsDidNotMeet)
    }

    @Test
    fun `a file with one point in it is not a line`() {
        val outcome = GpxInterchange.read(gpx(listOf(listOf(-41.0 to 174.0))))

        assertEquals(
            "the app's own sentence, word for word, from the repository's own import",
            GpxInterchange.Outcome.Invalid(GpxInterchange.TOO_SHORT),
            outcome
        )
    }

    @Test
    fun `a file with no track points at all is not a line`() {
        val xml = """<?xml version="1.0"?><gpx version="1.1"><trk><name>empty</name></trk></gpx>"""

        assertEquals(GpxInterchange.Outcome.Invalid(GpxInterchange.TOO_SHORT), GpxInterchange.read(xml))
    }

    @Test
    fun `a file that will not parse is refused in words rather than thrown`() {
        // The desk shows this sentence on the page and the phone's picker shows it in a message, so
        // both get words rather than an exception to interpret.
        val outcome = GpxInterchange.read("<gpx version=\"1.1\"><trk><trkseg></gpx>")

        assertEquals(GpxInterchange.Outcome.Invalid(GpxInterchange.MALFORMED), outcome)
    }

    @Test
    fun `the vertices are the file's own numbers, to the bit`() {
        val reading = read(gpx(listOf(listOf(-41.2865123 to 174.7762456, -41.2866123 to 174.7763456))))

        assertEquals(GeoPoint(lat = -41.2865123, lng = 174.7762456), reading.paths[0][0])
        assertEquals(GeoPoint(lat = -41.2866123, lng = 174.7763456), reading.paths[0][1])
    }
}
