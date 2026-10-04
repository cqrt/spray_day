package nz.mckenzie.sprayday.domain.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.io.ByteArrayOutputStream
import java.util.zip.ZipEntry
import java.util.zip.ZipOutputStream

/**
 * KMZ: a KML document inside a zip, read as the very track the same KML unzipped is.
 *
 * The archive is built here rather than committed, so what is tested is the rule - the first `.kml`
 * entry is the document, anything else is not - and not one file that happens to sit in the tree.
 */
class TrackFileTest {

    private fun zip(vararg entries: Pair<String, String>): ByteArray {
        val out = ByteArrayOutputStream()
        ZipOutputStream(out).use { archive ->
            for ((name, text) in entries) {
                archive.putNextEntry(ZipEntry(name))
                archive.write(text.toByteArray(Charsets.UTF_8))
                archive.closeEntry()
            }
        }
        return out.toByteArray()
    }

    private fun kml(name: String = "doc.kml", points: String = "174.0,-41.0 174.1,-41.1"): String = """
        <?xml version="1.0" encoding="UTF-8"?>
        <kml xmlns="http://www.opengis.net/kml/2.2"><Document><Placemark>
          <name>$name</name>
          <LineString><coordinates>$points</coordinates></LineString>
        </Placemark></Document></kml>
    """.trimIndent()

    @Test
    fun `a zipped kml is read as the track it holds`() {
        val outcome = TrackInterchange.readBytes(zip("doc.kml" to kml()))

        val reading = (outcome as TrackInterchange.Outcome.Read).reading
        assertEquals(1, reading.paths.size)
        assertEquals(2, reading.paths[0].size)
        assertEquals(-41.0, reading.paths[0][0].lat, 0.0)
        assertEquals(174.0, reading.paths[0][0].lng, 0.0)
    }

    @Test
    fun `the kml is found under a folder too, and not only at the root`() {
        val outcome = TrackInterchange.readBytes(zip("files/doc.kml" to kml()))

        assertTrue(outcome is TrackInterchange.Outcome.Read)
    }

    @Test
    fun `an archive holding no kml is refused in the operator's own words`() {
        val outcome = TrackInterchange.readBytes(zip("readme.txt" to "not a track"))

        assertEquals(
            TrackInterchange.Outcome.Invalid(TrackFile.NO_KML_IN_ARCHIVE),
            outcome
        )
    }

    @Test
    fun `plain xml bytes are read as text rather than mistaken for a zip`() {
        // A GPX file is text already: it has to go through the same door without being unzipped, so
        // the zip magic is what decides, not the file's extension that this layer never sees.
        val gpx = """<?xml version="1.0"?><gpx version="1.1"><trk><trkseg>""" +
            """<trkpt lat="-41.0" lon="174.0"/><trkpt lat="-41.1" lon="174.1"/>""" +
            """</trkseg></trk></gpx>"""

        assertTrue(TrackInterchange.readBytes(gpx.toByteArray(Charsets.UTF_8)) is TrackInterchange.Outcome.Read)
    }

    @Test
    fun `bytes that are not a track file at all are refused, not thrown`() {
        val outcome = TrackInterchange.readBytes(byteArrayOf(0x00, 0x01, 0x02, 0x03))

        assertEquals(TrackInterchange.Outcome.Invalid(TrackInterchange.MALFORMED), outcome)
    }
}
