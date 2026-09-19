package nz.mckenzie.sprayday.domain.geo

import java.time.Instant
import kotlin.math.cos
import org.junit.Assert.assertEquals
import org.junit.Assert.assertFalse
import org.junit.Assert.assertNull
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * A line that is sprayed twice.
 *
 * The report this exists for: a track walked up one side and back down the other read as *done*
 * after the first side, so the traffic light went out for four months and the other side was never
 * sprayed. What the app is allowed to claim about a line walked once - and about the two passes it
 * can tell apart, and the two it cannot - is decided here.
 */
class TwoPassesTest {

    private val start = Instant.parse("2026-09-17T09:00:00Z").toEpochMilli()

    private fun at(seconds: Long) = start + seconds * 1_000L

    private val lat = -41.5

    /** Metres of longitude at this latitude, so the geometry here is true metres. */
    private val lngPerM = METRES_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(lat))

    /** A straight line east, 1 km unless told otherwise. */
    private fun lineEast(lengthM: Double = 1_000.0) = listOf(
        GeoPoint(lat, 173.9),
        GeoPoint(lat, 173.9 + lengthM / lngPerM)
    )

    /**
     * Fixes walking along the line from [fromM] for [lengthM], [offsetM] metres to one side of it,
     * ten metres apart and ten seconds apart from [seconds].
     */
    private fun walk(
        fromM: Double,
        lengthM: Double,
        offsetM: Double = 0.0,
        seconds: Long = 0,
        stepM: Double = 10.0
    ): List<GeoPoint> = buildList {
        var travelled = 0.0
        while (travelled <= lengthM) {
            add(
                GeoPoint(
                    lat = lat + offsetM / METRES_PER_DEG_LAT,
                    lng = 173.9 + (fromM + travelled) / lngPerM,
                    timeMs = at(seconds + travelled.toLong())
                )
            )
            travelled += stepM
        }
    }

    /** The same line walked the other way: from [fromM] back towards the near end. */
    private fun walkBack(
        fromM: Double,
        lengthM: Double,
        offsetM: Double = 0.0,
        seconds: Long = 0,
        stepM: Double = 10.0
    ): List<GeoPoint> = buildList {
        var travelled = 0.0
        while (travelled <= lengthM) {
            add(
                GeoPoint(
                    lat = lat + offsetM / METRES_PER_DEG_LAT,
                    lng = 173.9 + (fromM - travelled) / lngPerM,
                    timeMs = at(seconds + travelled.toLong())
                )
            )
            travelled += stepM
        }
    }

    private fun read(
        passes: List<RecordedPass>,
        handSprayedAtEpochMs: Long? = null,
        separationM: Double? = null,
        planned: List<GeoPoint> = lineEast()
    ): TwoPasses.Result = TwoPasses.split(
        planned = planned,
        passes = passes,
        handSprayedAtEpochMs = handSprayedAtEpochMs,
        separationM = separationM
    ) ?: error("the line should be walkable")

    /**
     * A pass as the app gets one: the fixes, dated when the pass ended.
     *
     * The date matters here. A pass read from the database is dated from its spray event, which is
     * the end of the pass, so its own fixes are all before it - and the two-pass maths reads
     * "before this pass" and "after it" off exactly that.
     */
    private fun passOf(
        fixes: List<GeoPoint>,
        bothSidesClaimed: Boolean = false
    ) = RecordedPass(
        atEpochMs = fixes.lastOrNull()?.timeMs ?: 0L,
        points = fixes,
        bothSidesClaimed = bothSidesClaimed
    )

    @Test
    fun `a line walked once is not done`() {
        val result = read(listOf(passOf(walk(0.0, 1_000.0, offsetM = 1.5))))

        assertFalse("one pass is not the job", result.isComplete)
        assertEquals("the whole line is owed its other pass", result.totalM, result.onePassM, 2.0)
        assertEquals("and none of it has been done twice", 0.0, result.doneM, 2.0)
        assertEquals("the pass it is waiting on is known", 1, result.pending.size)
    }

    @Test
    fun `up one side and back down the other in one recording finishes the line`() {
        // The shape the tracks are actually walked in: out and back, one session, one recording.
        val fixes = walk(0.0, 1_000.0, offsetM = 1.5) +
            walkBack(1_000.0, 1_000.0, offsetM = -1.5, seconds = 2_000)

        val result = read(listOf(passOf(fixes)))

        assertTrue("both ways along the line is both passes", result.isComplete)
        assertEquals(0, result.pending.size)
    }

    @Test
    fun `one side today and the other way tomorrow finishes the line`() {
        val up = passOf(walk(0.0, 1_000.0, offsetM = 1.5))
        val back = passOf(walkBack(1_000.0, 1_000.0, offsetM = -1.5, seconds = 86_400))

        val result = read(listOf(up, back))

        assertTrue(result.isComplete)
        val done = result.stretches.mapNotNull { it.completedAtEpochMs }.min()
        assertTrue(
            "done when the second pass was made, not the first: $done",
            done >= at(86_400) && done <= at(87_400)
        )
        assertEquals("the second pass is not still pending", 0, result.pending.size)
    }

    @Test
    fun `two passes the same way along it finish the line when they are far enough apart`() {
        // The six metre road: an edge at a time, both times heading the same way. Three metres
        // apart is two strips the app can tell apart.
        val first = passOf(walk(0.0, 1_000.0, offsetM = 1.5))
        val second = passOf(walk(0.0, 1_000.0, offsetM = -1.5, seconds = 2_000))

        val result = read(listOf(first, second), separationM = 3.0)

        assertTrue("one side each counts, even heading the same way", result.isComplete)
    }

    @Test
    fun `two passes the same way cannot be told apart when they run too close together`() {
        // The knapsack track: two walks a metre apart, which is inside the noise of a phone in a
        // pocket. The app must not claim to have seen two sides it cannot see.
        val first = passOf(walk(0.0, 1_000.0, offsetM = 0.5))
        val second = passOf(walk(0.0, 1_000.0, offsetM = -0.5, seconds = 2_000))

        val result = read(listOf(first, second), separationM = 1.0)

        assertFalse("a difference it cannot resolve is not evidence", result.isComplete)
        assertTrue("so it has to ask", result.needsAnswer)
        assertEquals(result.totalM, result.ambiguousM, 2.0)
        assertEquals(0.0, result.onePassM, 2.0)
    }

    @Test
    fun `two passes with nobody saying how far apart they run cannot be told apart either`() {
        val first = passOf(walk(0.0, 1_000.0, offsetM = 1.5))
        val second = passOf(walk(0.0, 1_000.0, offsetM = -1.5, seconds = 2_000))

        val result = read(listOf(first, second), separationM = null)

        assertTrue("no number to read, so no claim", result.needsAnswer)
    }

    @Test
    fun `two passes the same way on the same side are not two sides`() {
        // The mistake worth catching: up one side twice, thinking both were done.
        val first = passOf(walk(0.0, 1_000.0, offsetM = 1.5))
        val second = passOf(walk(0.0, 1_000.0, offsetM = 1.4, seconds = 2_000))

        val result = read(listOf(first, second), separationM = 3.0)

        assertTrue("the same side twice is not both sides", result.needsAnswer)
    }

    @Test
    fun `a pass that covered half the line leaves the other half untouched`() {
        val fixes = walk(0.0, 500.0, offsetM = 1.5) +
            walkBack(500.0, 500.0, offsetM = -1.5, seconds = 600)

        val result = read(listOf(passOf(fixes)))

        assertEquals("half of it was walked both ways", result.totalM / 2, result.doneM, 30.0)
        assertEquals("and the other half never touched", result.totalM / 2, result.missingM, 30.0)
    }

    @Test
    fun `a spray logged by hand does the whole line both ways`() {
        val result = read(passes = emptyList(), handSprayedAtEpochMs = at(0))

        assertTrue(
            "a spray with no fixes stands for the whole line, as it always has",
            result.isComplete
        )
        assertEquals(at(0), result.stretches.first().completedAtEpochMs)
    }

    @Test
    fun `the operator's word finishes a line the fixes could not tell apart`() {
        val first = passOf(walk(0.0, 1_000.0, offsetM = 0.5))
        val claimed = passOf(
            fixes = walk(0.0, 1_000.0, offsetM = -0.5, seconds = 2_000),
            bothSidesClaimed = true
        )

        val result = read(listOf(first, claimed), separationM = 1.0)

        assertTrue("the claim is what closes it", result.isComplete)
        assertEquals(claimed.atEpochMs, result.stretches.first().completedAtEpochMs)
    }

    @Test
    fun `an extra pass after the job was done starts the next one`() {
        val up = passOf(walk(0.0, 1_000.0, offsetM = 1.5))
        val back = passOf(walkBack(1_000.0, 1_000.0, offsetM = -1.5, seconds = 2_000))
        val again = passOf(walk(0.0, 1_000.0, offsetM = 1.5, seconds = 86_400))

        val result = read(listOf(up, back, again))

        val done = result.stretches.mapNotNull { it.completedAtEpochMs }.min()
        assertTrue(
            "still done as of the pass that finished it, so the line's colour does not move: $done",
            done >= at(2_000) && done <= at(3_000)
        )
        assertEquals(
            "but spraying it again has started another job, which owes its other pass",
            result.totalM,
            result.onePassM,
            2.0
        )
    }

    @Test
    fun `two walks up the line in one recording are two passes, not a there and back`() {
        // Up one side, walk back down with the sprayer off and the pass paused, up the other side:
        // near any one metre of the line the fixes look like a there-and-back, because the last fix
        // of the first walk and the first of the second are a few metres apart heading back down the
        // line between them. Two things say otherwise, and both are the operator's own record: the
        // pause they pressed for the walk back, and the fact that the ground between the two walks
        // was never driven at all - the fixes in between are somewhere else entirely.
        val oneWay = walk(0.0, 1_000.0, offsetM = 0.5, seconds = 0)
        val again = walk(0.0, 1_000.0, offsetM = -0.5, seconds = 2_000)
        val paused = RecordingBreak(fromEpochMs = at(1_100), toEpochMs = at(1_900))

        val result = read(
            listOf(RecordedPass(at(3_000), oneWay + again, breaks = listOf(paused))),
            separationM = 1.0
        )

        assertFalse("the app cannot tell these two sides apart", result.isComplete)
        assertTrue("so it asks rather than calling it done", result.needsAnswer)
    }

    @Test
    fun `two walks up the line either side of a road are the job`() {
        // The same shape on a road, where the two strips are far enough apart to be real: one edge
        // and then the other, the walk back left unrecorded, and the line is done.
        val oneEdge = walk(0.0, 1_000.0, offsetM = 1.5, seconds = 0)
        val otherEdge = walk(0.0, 1_000.0, offsetM = -1.5, seconds = 2_000)
        val paused = RecordingBreak(fromEpochMs = at(1_100), toEpochMs = at(1_900))

        val result = read(
            listOf(RecordedPass(at(3_000), oneEdge + otherEdge, breaks = listOf(paused))),
            separationM = 3.0
        )

        assertTrue("one edge each is both passes", result.isComplete)
    }

    @Test
    fun `a pause does not turn one pass into two, and the ground under it is not walked`() {
        // Stopping for a breather in the middle of the walk is still one walk over the ground either
        // side of it. What the pause does say is that the ground under it was not sprayed - which is
        // the same thing Coverage says about that pause - so those metres are owed a pass like any
        // other metre nothing has been along.
        val result = read(
            listOf(
                RecordedPass(
                    atEpochMs = at(1_000),
                    points = walk(0.0, 1_000.0, offsetM = 1.5),
                    breaks = listOf(RecordingBreak(fromEpochMs = at(400), toEpochMs = at(700)))
                )
            )
        )

        assertFalse("one pass, however often it stopped", result.isComplete)
        assertEquals(
            "either side of the pause it is owed its other pass",
            700.0,
            result.onePassM,
            60.0
        )
        assertEquals(
            "and the stopped-over stretch has not been walked at all",
            300.0,
            result.missingM,
            60.0
        )
        assertFalse("with nothing to ask about", result.needsAnswer)
    }

    @Test
    fun `fixes that wander backwards do not read as a turn`() {
        // A metre of wander under trees must not break a pass in two, or every track would end up
        // with a question to answer.
        val fixes = walk(0.0, 1_000.0, offsetM = 1.5).toMutableList()
        val wobble = fixes[55]
        fixes[55] = wobble.copy(lng = wobble.lng - 1.0 / lngPerM)

        val result = read(listOf(passOf(fixes)))

        assertFalse("still one pass", result.needsAnswer)
        assertEquals("and still owed its other one", result.totalM, result.onePassM, 2.0)
    }

    @Test
    fun `a line with no length to walk says nothing about two passes`() {
        assertNull(
            "nothing to measure against",
            TwoPasses.split(listOf(GeoPoint(lat, 173.9), GeoPoint(lat, 173.9)), emptyList())
        )
    }
}
