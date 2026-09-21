package nz.mckenzie.sprayday.domain.geo

import kotlin.math.abs
import kotlin.math.cos
import kotlin.math.hypot
import kotlin.math.max

/**
 * A line that is sprayed twice.
 *
 * Half the tracks on the place are walked up one side and back down the other, and a road is
 * done an edge at a time; for those, one pass is not "half sprayed", it is not sprayed. Read as
 * a single pass - which is what the app did before this - a line walked once went green, the
 * traffic light went out for four months, and the other side was never done.
 *
 * The question here is therefore not *how much* of the line has been covered - that is
 * [Coverage] - but whether the passes over it can be told apart into two:
 *
 * - **Opposite directions.** Up one side and back down the other walks the same metres twice
 *   heading opposite ways, and the direction of travel needs no accuracy at all: consecutive
 *   fixes either head along the line or back along it, and five metres of sideways GPS wander
 *   does not touch that.
 * - **Opposite sides.** One side and then the other the same way along is two passes at two
 *   different offsets from the line - but only where those offsets are far enough apart to be
 *   real. On a six metre road sprayed in one-and-a-half metre strips they are about three
 *   metres apart and the app can see it; on a two metre track walked with a knapsack they are
 *   about a metre apart, which is inside the noise of a phone in a pocket, so there the app does
 *   not pretend: it goes by direction, and if that cannot tell either, it asks.
 *
 * Nothing here is stored: the answer is recomputed from the plan and the recordings, exactly as
 * the single-pass coverage is. The one thing that is stored is the operator's answer when the app
 * cannot tell - see [RecordedPass.bothSidesClaimed] - because a claim has to outlive the reading
 * of the fixes it was given for.
 *
 * A spray logged by hand (an asset spray with no recording) has no fixes to read and stands for
 * the whole line both ways, exactly as it stands for the whole line under [Coverage].
 */
object TwoPasses {

    /**
     * The distance apart at which two passes are far enough apart to say which side each was on.
     *
     * Below this the app goes by direction instead of by side. Two metres is roughly a third of
     * the accuracy of a typical fix: a difference smaller than this between two passes is noise
     * wearing a side's clothing, and a wrong "both sides done" is the one answer worth refusing.
     */
    const val MIN_RESOLVABLE_SEPARATION_M: Double = 2.0

    /**
     * How far along the line a pass has to travel before it counts as having been along this bit
     * of it.
     *
     * The fixes of a pass are at least the filter's three metres apart, so a single pair of them
     * is not enough to go on: this is the length of the *run* of one-directional movement that
     * counts, accumulated over however many fixes it takes. Five metres is a couple of paces, and
     * short enough that the last stretch of a line is not left owing forever.
     */
    private const val MIN_TRAVERSAL_M: Double = 5.0

    /**
     * How far a pass has to travel against its own direction before it counts as having turned.
     *
     * Without this, the wander of a single fix - a metre backwards while walking forwards under
     * trees - would break one pass into two, and two passes the app cannot tell apart is a
     * question it would then have to ask for no reason.
     */
    private const val MIN_REVERSAL_M: Double = 3.0

    /** The share of the stated separation two passes have to differ by to be on opposite sides. */
    private const val OPPOSITE_SIDE_SHARE: Double = 0.5

    /**
     * How far a pass may have got, in metres, across one of its own pauses and still be the same
     * run over this bit of line.
     *
     * A pause says the ground across it was not sprayed, so the pair either side of it is never
     * read as movement along the line. What it does not say is whether the pass came back to
     * where it had been: a breather, a tank or a gate is a pause of a few metres and the run goes
     * on; walking back down the line and starting up again is a pause of a kilometre, and that is
     * a second pass over this bit of line rather than one long one. Twenty-five metres is a few
     * fixes' worth, and well under any distance a pass could be said to have carried on over.
     */
    private const val MAX_PAUSE_STEP_M: Double = 25.0

    /** Floating-point slack, so a line every metre of which is done reads as done. */
    private const val DONE_EPSILON_M: Double = 1e-6

    /** What is outstanding on one stretch of a line that takes two passes. */
    enum class Outstanding {
        /** Nothing owed: two passes the app could tell apart have covered it. */
        NONE,

        /** One pass since it was last done, with the other one still to come. */
        ONE_PASS,

        /** Two or more passes that the app cannot tell apart, so it has to ask. */
        AMBIGUOUS
    }

    /** One stretch of the plan, with what the passes have made of it. */
    data class Stretch(
        val points: List<GeoPoint>,
        /**
         * When it was last done: the second of two passes that could be told apart, or the
         * operator's word for it. Null when nothing has ever covered it.
         */
        val completedAtEpochMs: Long?,
        val outstanding: Outstanding,
        val lengthM: Double
    ) {
        /** Two passes have covered this stretch and nothing is owed on it. */
        val isDone: Boolean
            get() = completedAtEpochMs != null && outstanding == Outstanding.NONE
    }

    /**
     * What the whole line comes to.
     *
     * The four lengths add up to [totalM], so an answer that is part done and part never covered
     * cannot be read as either of the two.
     */
    data class Result(
        val stretches: List<Stretch>,
        /** Metres done: two passes the app could tell apart. */
        val doneM: Double,
        /** Metres with one pass since they were last done. */
        val onePassM: Double,
        /** Metres with two passes the app cannot tell apart. */
        val ambiguousM: Double,
        /** Metres nothing has been near at all. */
        val missingM: Double,
        val totalM: Double,
        /**
         * The passes the line is still waiting on: the ones that have been along part of it
         * without finishing it. What the legs of an unfinished job are, and - once the job is
         * finished - nothing, so the ground driven making the job up is this and the pass being
         * finished.
         */
        val pending: List<RecordedPass> = emptyList()
    ) {
        /** Every metre of the line has had its two passes. */
        val isComplete: Boolean get() = doneM >= totalM - DONE_EPSILON_M

        /** Part of the line has had two passes the app cannot tell apart, so it has to ask. */
        val needsAnswer: Boolean get() = ambiguousM > 0.0

        /** Metres still to do, whether they are owed a pass or have never been touched. */
        val outstandingM: Double get() = onePassM + ambiguousM + missingM
    }

    /**
     * The plan cut into stretches, each carrying when it was last done and what it is still owed.
     *
     * Returns null when there is no line to walk - fewer than two points, all of them in the same
     * place, or no tolerance to probe with - which callers read as "there is nothing to say about
     * two passes on this one", exactly as an empty [Coverage.splitByCoverage] reads.
     *
     * [handSprayedAtEpochMs] is a spray logged by hand: the whole line, both ways, at that time.
     * [separationM] is how far apart the two passes run, when the operator has said - see
     * [MIN_RESOLVABLE_SEPARATION_M] for what happens when they have not.
     */
    /**
     * The same question asked of a whole track: the line's own passes, and the side tracks' ground.
     *
     * **The two-pass reading is the line's**, because that is what a two-pass job is: you drive up one
     * side and back down the other, and whether both sides are done is a question about the line. A
     * side track is a strip you drive up and back in one trip - see
     * [nz.mckenzie.sprayday.map.AssetCoverageStretches] for the same rule on the map - so a side track
     * is **done the first time it is driven**, and it owes no second pass to anybody. Asking the
     * two-pass reading about a spur is what made a dead end read as finished after one trip, for a
     * reason that had nothing to do with the spur.
     *
     * So: the line is judged as it always was, and each side track adds its own metres to the total,
     * to [doneM] if any pass covered it at all, or to [missingM] if nothing ever has. Everything a
     * caller reads off the answer - is the job complete, what is still owed, how far along it is - then
     * means what the operator means by it.
     *
     * Named `splitPaths` rather than an overload of [split]: `List<List<GeoPoint>>` and
     * `List<GeoPoint>` are the same `List` once compiled.
     *
     * Null when there is no line to walk, which callers read as "there is nothing to say about two
     * passes on this one" - the same as a null from [split].
     */
    fun splitPaths(
        planned: List<List<GeoPoint>>,
        passes: List<RecordedPass>,
        handSprayedAtEpochMs: Long? = null,
        separationM: Double? = null,
        toleranceM: Double = Coverage.DEFAULT_TOLERANCE_M
    ): Result? {
        val line = planned.firstOrNull().orEmpty()
        val read = split(
            planned = line,
            passes = passes,
            handSprayedAtEpochMs = handSprayedAtEpochMs,
            separationM = separationM,
            toleranceM = toleranceM
        ) ?: return null

        var doneM = read.doneM
        var missingM = read.missingM
        var totalM = read.totalM

        for (side in planned.drop(1)) {
            if (side.size < 2) continue
            val metres = polylineLengthMeters(side)
            // **The whole strip, not a touch of it.** The junction is shared with the line, so a pass
            // along the line is always within tolerance of the first dozen metres of a side track that
            // leaves it - which would make every spur "driven" the moment anybody drove past it. A side
            // track is done when every stretch of it carries a date: driven all the way up it, by a
            // pass or by a spray logged by hand.
            val stretches = Coverage.splitByCoverage(
                planned = side,
                passes = passes,
                assetSprayedAtEpochMs = handSprayedAtEpochMs,
                toleranceM = toleranceM
            )
            val covered = stretches.isNotEmpty() && stretches.all { it.lastSprayedAtEpochMs != null }
            if (covered) doneM += metres else missingM += metres
            totalM += metres
        }

        return read.copy(doneM = doneM, missingM = missingM, totalM = totalM)
    }

    fun split(
        planned: List<GeoPoint>,
        passes: List<RecordedPass>,
        handSprayedAtEpochMs: Long? = null,
        separationM: Double? = null,
        toleranceM: Double = Coverage.DEFAULT_TOLERANCE_M
    ): Result? {
        if (planned.size < 2 || toleranceM <= 0.0) return null

        val walk = Coverage.PlannedWalk(planned, stepM = toleranceM / 2.0)
        if (walk.totalM <= 0.0 || walk.steps.isEmpty()) return null

        val read = passes.filter { it.points.isNotEmpty() }
        val indexes = read.map { pass ->
            Coverage.RecordedIndex(pass.points, cellSizeM = toleranceM, breaks = pass.breaks)
        }
        // Every fix of each pass, by time. A step only sees the fixes near it, so this is what says
        // whether a pass that was near this bit of the line twice was here twice in a row or left
        // and came back: see [runsOf].
        val passTimes = read.map { pass -> pass.points.map { it.timeMs }.sorted().toLongArray() }
        // And how far each pass moved across each of its own pauses. A step of a few metres is the
        // pass carrying on after a breather; a step of a kilometre is the pass leaving this bit of
        // line and coming back to it later, which is two passes over it and not one - see [runsOf].
        val pauseSteps = read.map { pass ->
            pass.breaks.map { pause -> stepAcrossPause(pass, pause) }
        }
        val sidesResolvable = separationM != null && separationM >= MIN_RESOLVABLE_SEPARATION_M
        val pending = mutableSetOf<RecordedPass>()

        val steps = walk.steps.map { step ->
            judge(
                step = step,
                walk = walk,
                passes = read,
                indexes = indexes,
                passTimes = passTimes,
                pauseSteps = pauseSteps,
                handSprayedAtEpochMs = handSprayedAtEpochMs,
                separationM = separationM,
                sidesResolvable = sidesResolvable,
                toleranceM = toleranceM,
                pending = pending
            )
        }

        val stretches = mutableListOf<Stretch>()
        var runStart = 0
        for (index in 1 until steps.size) {
            if (steps[index].completedAtEpochMs != steps[runStart].completedAtEpochMs ||
                steps[index].outstanding != steps[runStart].outstanding
            ) {
                stretches += stretchOf(walk, steps, runStart, index - 1)
                runStart = index
            }
        }
        stretches += stretchOf(walk, steps, runStart, steps.size - 1)

        return Result(
            stretches = stretches,
            doneM = steps.filter { it.isDone }.sumOf { it.lengthM },
            onePassM = steps.filter { it.outstanding == Outstanding.ONE_PASS }.sumOf { it.lengthM },
            ambiguousM = steps.filter { it.outstanding == Outstanding.AMBIGUOUS }.sumOf { it.lengthM },
            missingM = steps.filter { !it.isDone && it.outstanding == Outstanding.NONE }
                .sumOf { it.lengthM },
            totalM = walk.totalM,
            pending = read.filter { it in pending }
        )
    }

    /** One step of the plan, judged. */
    private data class Judged(
        val completedAtEpochMs: Long?,
        val outstanding: Outstanding,
        val lengthM: Double
    ) {
        val isDone: Boolean
            get() = completedAtEpochMs != null && outstanding == Outstanding.NONE
    }

    /** One stretch of the answer: the plan's own geometry between two step boundaries. */
    private fun stretchOf(
        walk: Coverage.PlannedWalk,
        steps: List<Judged>,
        firstStep: Int,
        lastStep: Int
    ): TwoPasses.Stretch {
        val startM = walk.steps[firstStep].startM
        val endM = walk.steps[lastStep].endM
        return Stretch(
            points = walk.slice(startM, endM),
            completedAtEpochMs = steps[firstStep].completedAtEpochMs,
            outstanding = steps[firstStep].outstanding,
            lengthM = endM - startM
        )
    }

    /**
     * What the passes have made of one step of the plan.
     *
     * Every pass is read for the runs of movement it made near this bit of the line - which way
     * each run was heading and which side of the line it was on - and those runs are then taken in
     * the order they happened: two runs that can be told apart are the two passes this line needs,
     * and a run left over at the end is what the line is still owed.
     */
    private fun judge(
        step: Coverage.PlannedWalk.Step,
        walk: Coverage.PlannedWalk,
        passes: List<RecordedPass>,
        indexes: List<Coverage.RecordedIndex>,
        passTimes: List<LongArray>,
        pauseSteps: List<List<Double>>,
        handSprayedAtEpochMs: Long?,
        separationM: Double?,
        sidesResolvable: Boolean,
        toleranceM: Double,
        pending: MutableSet<RecordedPass>
    ): Judged {
        val lengthM = step.lengthM
        val direction = Direction.of(walk.slice(step.startM, step.endM))
            ?: return Judged(handSprayedAtEpochMs, Outstanding.NONE, lengthM)

        // A pass the operator said did both sides counts as the job done at its own time, so
        // anything before it is history and anything after it is the start of the next job.
        var doneAt = handSprayedAtEpochMs
        val runs = mutableListOf<Traversal>()
        passes.forEachIndexed { index, pass ->
            val fixes = indexes[index].nearby(step.probe, toleranceM)
            if (fixes.isEmpty()) return@forEachIndexed
            if (pass.bothSidesClaimed && (doneAt == null || pass.atEpochMs > doneAt)) {
                doneAt = pass.atEpochMs
            }
            runs += runsOf(fixes, pass, direction, passTimes[index], pass.breaks, pauseSteps[index])
        }
        val owed = mutableListOf<Traversal>()
        val since = runs.filter { doneAt == null || it.atEpochMs > doneAt }.sortedBy { it.atEpochMs }
        for (run in since) {
            val other = owed.firstOrNull { apart(it, run, separationM, sidesResolvable) }
            if (other == null) {
                owed += run
            } else {
                // Two passes that can be told apart: it is done as of the later of the two - which
                // is dated from the pass itself, not from the metre of the line that finished it,
                // so that every stretch of a line done by one pass shares one date and the map
                // draws it as one line.
                doneAt = max(doneAt ?: 0L, run.pass.atEpochMs)
                owed.clear()
            }
        }
        owed.forEach { pending += it.pass }

        return Judged(
            completedAtEpochMs = doneAt,
            outstanding = when {
                owed.size >= 2 -> Outstanding.AMBIGUOUS
                owed.size == 1 -> Outstanding.ONE_PASS
                else -> Outstanding.NONE
            },
            lengthM = lengthM
        )
    }

    /** One run of driving along the line near one step: which way, when, and how far off it. */
    private data class Traversal(
        val atEpochMs: Long,
        /** True when it headed the same way the plan was drawn, false when it came back. */
        val towards: Boolean,
        /** How far to the left of the line it ran, in metres, on average. */
        val offsetM: Double,
        val pass: RecordedPass
    )

    /**
     * Whether two runs are two passes rather than the same pass twice.
     *
     * Opposite directions always count: that is up one side and back down the other, and it is the
     * shape that needs no accuracy. Otherwise they have to be on opposite sides of the line, which
     * only counts when the operator has said the two passes run far enough apart for the difference
     * between them to be real.
     */
    private fun apart(
        first: Traversal,
        second: Traversal,
        separationM: Double?,
        sidesResolvable: Boolean
    ): Boolean {
        if (first.towards != second.towards) return true
        if (!sidesResolvable || separationM == null) return false
        return abs(first.offsetM - second.offsetM) >= separationM * OPPOSITE_SIDE_SHARE
    }

    /**
     * The runs of movement one pass made in the fixes near one step.
     *
     * Fixes arrive a few metres apart, so a run is accumulated across them rather than judged on a
     * single pair: what counts is the distance travelled one way before the pass turned round.
     *
     * A [RecordingBreak] deliberately does *not* end a run. A pause says the ground between two
     * fixes was not sprayed - which is [Coverage]'s business, and it reads the breaks for exactly
     * that - but the pass itself is still there, one side of the line, heading one way, and a run
     * broken in two by every pause would read as two passes over the same bit of line. What a
     * pause does hide is a turn: a pass that stops, turns round and comes back reads as one run
     * each way, because the fixes after the pause are heading the other way.
     *
     * [passTimes] is every fix the pass holds, by time, which is what tells two walks over this bit
     * of line apart from one walk that turned round on it. A step sees only the fixes near itself,
     * so two walks up the same side look like a there-and-back - the last fix of the first walk
     * and the first fix of the second are a few metres apart, heading back down the line between
     * them - unless the fixes in between are looked at: a pass that was somewhere else in between
     * did not turn round here, it left and came back, and that is two passes over this bit of line.
     */
    private fun runsOf(
        fixes: List<GeoPoint>,
        pass: RecordedPass,
        direction: Direction,
        passTimes: LongArray,
        breaks: List<RecordingBreak>,
        pauseSteps: List<Double>
    ): List<Traversal> {
        if (fixes.size < 2) return emptyList()

        val found = mutableListOf<Traversal>()
        var run = 0.0
        var against = 0.0
        var startedAtMs = fixes.first().timeMs
        val offsets = mutableListOf<Double>()

        fun close() {
            if (abs(run) >= MIN_TRAVERSAL_M) {
                found += Traversal(
                    atEpochMs = startedAtMs,
                    towards = run > 0.0,
                    offsetM = if (offsets.isEmpty()) 0.0 else offsets.sum() / offsets.size,
                    pass = pass
                )
            }
            run = 0.0
            against = 0.0
            offsets.clear()
        }

        fun begin(from: GeoPoint, to: GeoPoint, along: Double) {
            run = along
            startedAtMs = from.timeMs
            offsets += direction.offsetOf(from)
            offsets += direction.offsetOf(to)
        }

        for (index in 1 until fixes.size) {
            val from = fixes[index - 1]
            val to = fixes[index]
            if (breaks.withIndex().any { (position, pause) ->
                    pause.fallsBetween(from.timeMs, to.timeMs) &&
                        pauseSteps[position] > MAX_PAUSE_STEP_M
                }
            ) {
                // The operator stopped, and the pass came back somewhere else afterwards: walking
                // back down the line with the sprayer off, then up it again. Two passes over this
                // bit of line, however it looks locally.
                close()
                continue
            }
            if (breaks.any { it.fallsBetween(from.timeMs, to.timeMs) }) {
                // Stopped, and the pass carried on from about where it was - a breather, a tank, a
                // gate. The ground across the pause was not sprayed, so the pair is not movement;
                // but the run over this bit of line goes on, or every pause would read as a second
                // pass and the app would have to ask a question nobody needs.
                continue
            }
            if (wasElsewhereBetween(passTimes, from.timeMs, to.timeMs)) {
                // Away from this bit of the line and back, so no movement of it is read here.
                close()
                continue
            }

            val along = direction.along(from, to)
            if (along == 0.0) {
                // Sideways: neither coming along the line nor going back down it.
                continue
            }
            if (run == 0.0) {
                begin(from, to, along)
                continue
            }
            if ((along > 0.0) == (run > 0.0)) {
                run += along
                against = 0.0
                offsets += direction.offsetOf(to)
                continue
            }

            against += -along
            if (against >= MIN_REVERSAL_M) {
                // Turned round: the run that has ended is one pass, and this is the next.
                close()
                begin(from, to, along)
            }
        }
        close()

        return found
    }

    /**
     * Whether the pass recorded fixes between two times that are not in the list being walked.
     *
     * True when the pass was somewhere else in between: it left the neighbourhood of this step and
     * came back, which is not a run of driving along this bit of the line however the two fixes
     * either side of the absence happen to line up.
     */
    private fun wasElsewhereBetween(passTimes: LongArray, fromMs: Long, toMs: Long): Boolean {
        if (toMs <= fromMs) return false
        // Anything strictly between the two times: the bar is low on purpose, because the pass only
        // has to have been out of this step's reach for one fix.
        val firstAfter = passTimes.binarySearch(fromMs).let { if (it < 0) -it - 1 else it + 1 }
        return firstAfter < passTimes.size && passTimes[firstAfter] < toMs
    }

    /**
     * How far a pass moved over one of its own pauses: the distance between the last fix it took
     * before it stopped and the first one it took after it carried on.
     *
     * Nothing recorded in between, because that is what a pause is - so this is the only thing that
     * says whether a pause was a breather or a walk back down the line and up again. Zero when
     * there is no fix on one side of it, which is a pause at the very start or end of a pass: the
     * pass did not go anywhere, it simply had not started or had finished.
     */
    private fun stepAcrossPause(pass: RecordedPass, pause: RecordingBreak): Double {
        val before = pass.points.lastOrNull { it.timeMs <= pause.fromEpochMs } ?: return 0.0
        val after = pause.toEpochMs?.let { resumed ->
            pass.points.firstOrNull { it.timeMs >= resumed }
        } ?: return 0.0
        return haversineMeters(before.lat, before.lng, after.lat, after.lng)
    }

    /**
     * Which way the line runs at one step, and which side of it a fix is on.
     *
     * A local flat frame in metres, the same approximation the distance maths uses: over the
     * length of a sprayed line it is accurate to well under a metre, and it is the signs that are
     * read from it rather than the distances themselves.
     */
    private class Direction(
        private val lat: Double,
        private val lng: Double,
        private val east: Double,
        private val north: Double
    ) {
        /** Metres travelled along the plan's own direction getting from [from] to [to]. */
        fun along(from: GeoPoint, to: GeoPoint): Double =
            (east(to) - east(from)) * east + (north(to) - north(from)) * north

        /** Metres to one side of the plan's direction that [point] sits; the sign is the side. */
        fun offsetOf(point: GeoPoint): Double = east * north(point) - north * east(point)

        private fun east(point: GeoPoint): Double =
            (point.lng - lng) * METRES_PER_DEG_LNG_AT_EQUATOR * cos(Math.toRadians(lat))

        private fun north(point: GeoPoint): Double = (point.lat - lat) * METRES_PER_DEG_LAT

        companion object {
            /** The direction of the first segment of [chord], or null when it has no length. */
            fun of(chord: List<GeoPoint>): Direction? {
                if (chord.size < 2) return null
                val from = chord.first()
                val to = chord[1]
                val east = (to.lng - from.lng) * METRES_PER_DEG_LNG_AT_EQUATOR *
                    cos(Math.toRadians(from.lat))
                val north = (to.lat - from.lat) * METRES_PER_DEG_LAT
                val length = hypot(east, north)
                if (length <= 0.0) return null
                return Direction(
                    lat = from.lat,
                    lng = from.lng,
                    east = east / length,
                    north = north / length
                )
            }
        }
    }
}
