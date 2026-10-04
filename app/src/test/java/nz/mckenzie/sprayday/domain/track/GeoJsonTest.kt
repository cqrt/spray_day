package nz.mckenzie.sprayday.domain.track

import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * What a GeoJSON file is read as: one track per feature, each named from its own properties, so a
 * collection of tracks becomes a collection of assets rather than one flattened line.
 *
 * GeoJSON's own trap is fixed by the spec - coordinates are `[lon, lat]`, WGS84 - but it is still the
 * one this file pins first, because taking them the other way would drop a Bluff track in the
 * Southern Ocean. The rest is that every shape a feature may wear - a line, a multi-line, a polygon's
 * rings - arrives as paths, and that a `Point` is a place rather than a line.
 */
class GeoJsonTest {

    @Test
    fun `a feature collection is one feature per track, each under its own name`() {
        val text = """
            {"type":"FeatureCollection","crs":{"type":"name","properties":{"name":"EPSG:4326"}},"features":[
              {"type":"Feature","id":1,"geometry":{"type":"LineString","coordinates":[[168.32,-46.61,0],[168.33,-46.62,0]]},
               "properties":{"TechObjectName":"Ocean Beach Tk"}},
              {"type":"Feature","id":2,"geometry":{"type":"LineString","coordinates":[[168.24,-46.53,0],[168.25,-46.54,0]]},
               "properties":{"TechObjectName":"Omaui Track"}}
            ]}
        """.trimIndent()

        val tracks = (TrackInterchange.readFile(text.toByteArray(Charsets.UTF_8)) as TrackInterchange.FileOutcome.Read).tracks

        assertEquals(2, tracks.size)
        assertEquals("Ocean Beach Tk", tracks[0].name)
        assertEquals("Omaui Track", tracks[1].name)
    }

    @Test
    fun `coordinates are lon lat, so a point is not flipped`() {
        val feature = GeoJsonParser.parse(
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.322589,-46.617908]]},"properties":{}}"""
        ).single()

        assertEquals(1, feature.paths.size)
        assertEquals(-46.617908, feature.paths[0][0].lat, 1e-9)
        assertEquals(168.322589, feature.paths[0][0].lng, 1e-9)
    }

    @Test
    fun `a multi line string is read as the several paths it is`() {
        val feature = GeoJsonParser.parse(
            """
            {"type":"Feature","geometry":{"type":"MultiLineString","coordinates":[
              [[168.24,-46.53],[168.25,-46.54]],
              [[168.25,-46.54],[168.26,-46.55]]
            ]},"properties":{}}
            """.trimIndent()
        ).single()

        assertEquals(2, feature.paths.size)
        assertEquals(2, feature.paths[0].size)
    }

    @Test
    fun `a polygon is read as the ring around its ground`() {
        val feature = GeoJsonParser.parse(
            """
            {"type":"Feature","geometry":{"type":"Polygon","coordinates":[
              [[168.0,-46.0],[168.1,-46.0],[168.1,-46.1],[168.0,-46.0]]
            ]},"properties":{}}
            """.trimIndent()
        ).single()

        assertEquals(1, feature.paths.size)
        assertEquals(4, feature.paths[0].size)
        assertEquals("the ring is closed", feature.paths[0].first(), feature.paths[0].last())
    }

    @Test
    fun `a point is a place, not a line, and is left out`() {
        val feature = GeoJsonParser.parse(
            """{"type":"Feature","geometry":{"type":"Point","coordinates":[168.3,-46.6]},"properties":{"name":"Gate"}}"""
        ).single()

        assertTrue("nothing to draw as a line", feature.paths.isEmpty())
    }

    @Test
    fun `a name is read from the common keys, and absent when there is none`() {
        fun name(json: String): String? =
            GeoJsonParser.parse(
                """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[0.0,0.0],[0.1,0.1]]},"properties":$json}"""
            ).single().name

        assertEquals("Fence", name("""{"name":"Fence"}"""))
        assertEquals("Glory Tk", name("""{"title":"Glory Tk"}"""))
        assertEquals("Omaui Track", name("""{"TechObjectName":"Omaui Track"}"""))
        // The file's CharName keys hold field names in their CharValue, not track names, so no scan may
        // pick one up: no name at all is better than the wrong one.
        assertNull(name("""{"CharName1":"TECHNICAL_OBJECT_NAME","CharValue1":"Boundary Fence"}"""))
        assertNull(name("""{}"""))
    }

    @Test
    fun `a bare geometry or a single feature is one track as well`() {
        val bare = GeoJsonParser.parse(
            """{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]}"""
        )
        val single = GeoJsonParser.parse(
            """{"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]},"properties":{}}"""
        )

        assertEquals(1, bare.size)
        assertEquals(1, single.size)
    }

    @Test
    fun `a file of tracks that do not meet is many tracks, not one line with jumps`() {
        // The whole reason a collection is one asset per feature: these two never touch, so flattening
        // them would draw a line across the country that nobody drove.
        val text = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]},"properties":{"name":"A"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[170.0,-44.0],[170.1,-44.1]]},"properties":{"name":"B"}}
            ]}
        """.trimIndent()

        val outcome = TrackInterchange.readFile(text.toByteArray(Charsets.UTF_8))

        val tracks = (outcome as TrackInterchange.FileOutcome.Read).tracks
        assertEquals(2, tracks.size)
        assertFalse("neither was joined to the other", tracks[0].reading.segmentsDidNotMeet)
        assertFalse(tracks[1].reading.segmentsDidNotMeet)
    }

    @Test
    fun `a point in a collection is dropped rather than refusing the file`() {
        val text = """
            {"type":"FeatureCollection","features":[
              {"type":"Feature","geometry":{"type":"Point","coordinates":[168.3,-46.6]},"properties":{"name":"A gate"}},
              {"type":"Feature","geometry":{"type":"LineString","coordinates":[[168.0,-46.0],[168.1,-46.1]]},"properties":{"name":"A track"}}
            ]}
        """.trimIndent()

        val tracks = (TrackInterchange.readFile(text.toByteArray(Charsets.UTF_8)) as TrackInterchange.FileOutcome.Read).tracks

        assertEquals("the place is not a track; the line is", 1, tracks.size)
        assertEquals("A track", tracks.single().name)
    }

    @Test
    fun `a file that is not GeoJSON at all is refused in words`() {
        val outcome = TrackInterchange.readFile("{ this is not json".toByteArray(Charsets.UTF_8))

        assertEquals(TrackInterchange.FileOutcome.Invalid(TrackInterchange.MALFORMED), outcome)
    }
}
