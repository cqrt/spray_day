package nz.mckenzie.sprayday.domain.doc

import org.junit.Assert.assertEquals
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * DOC's answer read as the tracks it holds.
 *
 * The service returns GeoJSON, so this is mostly the proof that the `properties` the app does not
 * otherwise use - `OBJECTID` and `SubObjectType` - come through, and that a feature that is not a
 * line is dropped rather than failing the whole page.
 */
class DocTracksJsonTest {

    private val serviceAnswer = """
        {"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"EPSG:4326"}},"features":[
          {"type":"Feature","id":35239046,"geometry":{"type":"LineString","coordinates":[
              [168.349159886,-46.6222429467],[168.349244881,-46.622198892]
          ]},"properties":{"OBJECTID":35239046,"TechObjectName":"Glory Tk","SubObjectType":"Walking Track","FlocID":"BLUFFHIL-TK02","CharName7":"WEB_TRACK_TYPE","CharValue7":"One way"}},
          {"type":"Feature","id":1,"geometry":{"type":"Point","coordinates":[168.3,-46.6]},
           "properties":{"OBJECTID":1,"TechObjectName":"A gate","SubObjectType":"Short Walk"}}
        ]}
    """.trimIndent()

    @Test
    fun `the tracks carry their id, name and kind`() {
        val tracks = DocTracksJson.parse(serviceAnswer)

        assertEquals("the point is not a track", 1, tracks.size)
        val track = tracks.single()
        assertEquals(35239046L, track.objectId)
        assertEquals("Glory Tk", track.name)
        assertEquals("Walking Track", track.kind)
        assertEquals(
            "the track's type is found among the pivoted characteristics, by its name",
            "One way",
            track.trackType
        )
        assertEquals("the site key is carried, for the imported asset's place", "BLUFFHIL-TK02", track.flocId)
        assertTrue("its own arithmetic over the vertices", track.lengthM > 0.0)
        assertEquals(2, track.pointCount)
    }

    @Test
    fun `the type is read from whichever slot holds it, and a blank one is no type`() {
        // The characteristic's slot varies by row, which is the whole reason it is found by its name.
        val text = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]},
               "properties":{"OBJECTID":1,"CharName2":"WEB_TRACK_TYPE","CharValue2":"Loop"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.2,-46.2],[168.3,-46.3]]},
               "properties":{"OBJECTID":2,"CharName4":"WEB_TRACK_TYPE","CharValue4":""}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.4,-46.4],[168.5,-46.5]]},
               "properties":{"OBJECTID":3,"CharName4":"WEB_DOGS_ALLOWED","CharValue4":"Dogs on a leash only"}}
            ]}
        """.trimIndent()

        val tracks = DocTracksJson.parse(text)
        assertEquals("Loop", tracks[0].trackType)
        assertNull("a blank value is no type", tracks[1].trackType)
        assertNull("a track with no such characteristic has no type", tracks[2].trackType)
    }

    @Test
    fun `a feature with no name is still named, from its id`() {
        val text = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]},
               "properties":{"OBJECTID":42,"SubObjectType":"Route"}}
            ]}
        """.trimIndent()

        val track = DocTracksJson.parse(text).single()
        assertEquals("DOC track 42", track.name)
        assertEquals("Route", track.kind)
    }

    @Test
    fun `an answer with nothing in it is no tracks, not a failure`() {
        val text = """{"type":"FeatureCollection","features":[]}"""

        assertTrue(DocTracksJson.parse(text).isEmpty())
    }

    @Test
    fun `a feature with no properties still reads as a track, named for what it has`() {
        val one = """{"type":"FeatureCollection","features":[
            {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]}}
        ]}""".trimIndent()

        val track = DocTracksJson.parse(one).single()
        assertNull(track.kind)
        assertEquals("DOC track unknown", track.name)
    }
}
