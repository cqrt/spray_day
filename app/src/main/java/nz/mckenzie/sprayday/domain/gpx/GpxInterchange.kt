package nz.mckenzie.sprayday.domain.gpx

import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * What a GPX file is read as: a track on the ground, or why the file is not one.
 *
 * **The one place a GPX file becomes a track.** The phone's own list screen imports a file from a
 * picker and the desk imports one dragged onto a map, and both have to mean the same thing by it: a
 * file whose later segments start on the first is a line with side tracks hanging off it, and a file
 * whose segments do not meet is the file of a tool that cuts a line up for its own reasons, which
 * this app has always read as one line with a jump in it. Two copies of that rule would be two
 * copies to keep in step, and the one that fell behind would be whichever of the two routes is the
 * rarer - so it is written once, here, and both callers are told the same thing about the same file.
 *
 * It decides nothing itself: the paths it hands back are the paths to store, and what to say about
 * the file is left to the caller, whose words are the ones the operator is looking at.
 */
object GpxInterchange {

    /** The file's segments read as one line, and what became of the ones after the first. */
    data class Reading(
        /** The paths to store: the line first, its side tracks after it. */
        val paths: List<List<GeoPoint>>,
        /**
         * How many of the file's own segments became side tracks. Zero when none did - either
         * because the file has one segment, or because its segments had to be joined up instead.
         */
        val sideTracks: Int,
        /**
         * Whether the file's own segments did **not** meet, and so were joined up into one line.
         *
         * Kept apart from "no side tracks" on purpose: a file that never had a second segment and a
         * file whose second segment starts somewhere else entirely are both read as one line, and
         * only the second is worth telling the operator about. False for a file that became side
         * tracks, because none of its segments were flattened.
         */
        val segmentsDidNotMeet: Boolean
    )

    /** The paths to store, or why this file is not a track. */
    sealed interface Outcome {

        data class Read(val reading: Reading) : Outcome

        /** In the app's own words, from `AssetRepository.importAssetGpx`: one sentence for both callers. */
        data class Invalid(val message: String) : Outcome
    }

    /**
     * What the file holds, and whether its segments are a line with side tracks.
     *
     * The join has to be exact, because that is what the app's own drawing produces and what its own
     * rules ask for: a side track starts *on* a vertex of the line, to the bit. A file that meets
     * that is read as the paths it wrote; one that does not is read the way this app has always read
     * one - every track point in document order, joined up, as a single line.
     */
    fun read(xml: String): Outcome {
        val segments = runCatching { GpxParser.parseSegments(xml) }
            .getOrElse { return Outcome.Invalid(MALFORMED) }
        val line = segments.firstOrNull().orEmpty()
        val sideTracks = segments.drop(1)

        val joined = sideTracks.isNotEmpty() &&
            sideTracks.all { side -> side.size >= 2 && line.any { vertex -> vertex == side.first() } }

        if (joined) {
            return Outcome.Read(Reading(listOf(line) + sideTracks, sideTracks.size, segmentsDidNotMeet = false))
        }

        // Every track point, in document order, as one line. A file whose segments do not meet is not
        // a refusal - it is a file from a tool that cuts a line up for its own reasons - but the
        // answer says so, because a track with a jump in it is worth knowing about.
        val flattened = segments.flatten()
        if (flattened.size < 2) return Outcome.Invalid(TOO_SHORT)
        return Outcome.Read(
            Reading(
                paths = listOf(flattened),
                sideTracks = 0,
                segmentsDidNotMeet = segments.size > 1
            )
        )
    }

    /** A line of one point is not a line, in the words the repository has always thrown this in. */
    const val TOO_SHORT = "A line needs at least two points"

    /**
     * A file that will not parse at all.
     *
     * Said in words rather than thrown, because both callers are showing it to somebody: the phone's
     * picker puts it in a message and the desk puts it on the page, and neither wants a stack trace.
     */
    const val MALFORMED = "That file could not be read as GPX. Pick a track file and try again."
}
