package nz.mckenzie.sprayday.map

import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
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

    private fun line(
        name: String = "Track 4",
        color: String = AssetColors.GREEN,
        kind: AssetKind = AssetKind.TRACK,
        shape: AssetShape = AssetShape.LINE,
        id: Long = 7L,
        points: List<GeoPoint> = wellingtonLine,
        sideTracks: List<List<GeoPoint>> = emptyList()
    ) = AssetLine(
        assetId = id,
        name = name,
        colorHex = color,
        points = points,
        kind = kind,
        shape = shape,
        sideTracks = sideTracks
    )

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

    @Test
    fun `a feature says what it is, so the map can draw each kind differently`() {
        val json = AssetGeoJson.build(listOf(line(kind = AssetKind.ROAD)))

        assertTrue(json.contains("\"kind\":\"ROAD\""))
        assertTrue(json.contains("\"shape\":\"LINE\""))
    }

    @Test
    fun `a place is a point feature, not a line with one vertex in it`() {
        val place = AssetLine(
            assetId = 9L,
            name = "Picnic table",
            colorHex = AssetColors.GREEN,
            points = listOf(GeoPoint(-41.2865, 174.7762)),
            kind = AssetKind.INFRASTRUCTURE,
            shape = AssetShape.POINT
        )

        val json = AssetGeoJson.build(listOf(place))

        assertTrue("a place is drawn where it is: $json", json.contains("\"type\":\"Point\""))
        assertTrue(json.contains("[174.7762000,-41.2865000]"))
        assertTrue(json.contains("\"shape\":\"POINT\""))
    }

    @Test
    fun `a place needs only one point, but a line still needs two`() {
        val onePoint = listOf(GeoPoint(-41.0, 174.0))
        val place = AssetLine(9L, "Shelter", AssetColors.GREEN, onePoint, shape = AssetShape.POINT)
        val stub = AssetLine(1L, "Stub", AssetColors.GREEN, onePoint, shape = AssetShape.LINE)

        assertTrue(AssetGeoJson.build(listOf(place)).contains("\"type\":\"Point\""))
        assertFalse(
            "a one-point line is still not a line",
            AssetGeoJson.build(listOf(stub)).contains("\"type\":\"Feature\"")
        )
    }

    @Test
    fun `a place carries the house it is to be drawn with, in its traffic-light colour`() {
        val place = AssetLine(
            assetId = 9L,
            name = "Trough",
            colorHex = AssetColors.forStatus(DueStatus.NEVER_SPRAYED),
            points = listOf(GeoPoint(-41.2865, 174.7762)),
            kind = AssetKind.INFRASTRUCTURE,
            shape = AssetShape.POINT
        )

        val json = AssetGeoJson.build(listOf(place))

        assertTrue(
            "a place never sprayed is drawn as the red house: $json",
            json.contains("\"${AssetGeoJson.ICON_PROPERTY}\":\"${PlaceIcons.houseImageName(AssetColors.RED)}\"")
        )
    }

    @Test
    fun `a line is not given a picture, because its own layer draws it`() {
        val json = AssetGeoJson.build(listOf(line(), line(kind = AssetKind.INFRASTRUCTURE)))

        assertFalse(
            "only a place is drawn as a picture: $json",
            json.contains(AssetGeoJson.ICON_PROPERTY)
        )
    }

    @Test
    fun `a half-sprayed track is two features, one per stretch, opening the same asset`() {
        val halfSprayed = AssetLine(
            assetId = 7L,
            name = "Track 4",
            colorHex = AssetColors.GREEN,
            points = wellingtonLine,
            stretches = listOf(
                AssetStretch(colorHex = AssetColors.GREEN, points = wellingtonLine),
                AssetStretch(
                    colorHex = AssetColors.RED,
                    points = listOf(GeoPoint(-41.2867, 174.7764), GeoPoint(-41.2868, 174.7765))
                )
            )
        )

        val json = AssetGeoJson.build(listOf(halfSprayed))

        assertEquals("one feature per stretch", 2, Regex("\"type\":\"Feature\",").findAll(json).count())
        assertTrue(json.contains("\"stroke\":\"${AssetColors.GREEN}\""))
        assertTrue(json.contains("\"stroke\":\"${AssetColors.RED}\""))
        assertEquals(
            "both parts are the same asset, so a tap on either opens it",
            2,
            Regex("\"id\":7").findAll(json).count()
        )
    }

    @Test
    fun `a stretch too short to be a line is dropped, and the whole line drawn instead`() {
        val shortStretch = AssetLine(
            assetId = 7L,
            name = "Track 4",
            colorHex = AssetColors.GREEN,
            points = wellingtonLine,
            stretches = listOf(
                AssetStretch(colorHex = AssetColors.RED, points = listOf(GeoPoint(-41.2865, 174.7762)))
            )
        )

        val json = AssetGeoJson.build(listOf(shortStretch))

        assertEquals("the track is not lost off the map", 1, Regex("\"type\":\"Feature\",").findAll(json).count())
        assertTrue(json.contains("\"stroke\":\"${AssetColors.GREEN}\""))
        assertFalse("a dot where the line should be is worse than the line", json.contains(AssetColors.RED))
    }

    @Test
    fun `a track with a side track is drawn as one feature per path`() {
        // The map's own rule, and the one the drawing screen's own pixels were read against: the line is
        // a feature, the side track is a feature, and the junction is in both - so a tap on the spur and
        // a tap on the line open the same track.
        val junction = GeoPoint(0.0, 0.0005)
        val json = AssetGeoJson.build(
            listOf(
                line(
                    id = 6L,
                    points = listOf(GeoPoint(0.0, 0.0), junction, GeoPoint(0.0, 0.001)),
                    sideTracks = listOf(listOf(junction, GeoPoint(0.001, 0.0005)))
                )
            )
        )

        assertEquals("two lines drawn", 2, Regex("\"type\":\"LineString\"").findAll(json).count())
        assertEquals(
            "and both carry the asset's id, so a tap on either opens the track",
            2,
            Regex("\"id\":6").findAll(json).count()
        )
        assertEquals(
            "the junction is a vertex of both features, at the same two numbers: [lng,lat]",
            2,
            Regex(Regex.escape("[0.0005000,0.0000000]")).findAll(json).count()
        )
    }

    @Test
    fun `a side track with one point is not drawn, because there is no line in it`() {
        val junction = GeoPoint(0.0, 0.0005)
        val json = AssetGeoJson.build(
            listOf(
                line(
                    id = 6L,
                    points = listOf(GeoPoint(0.0, 0.0), junction),
                    sideTracks = listOf(listOf(junction))
                )
            )
        )

        assertEquals(
            "one line, and the single point is not a second",
            1,
            Regex("\"type\":\"LineString\"").findAll(json).count()
        )
    }
}
