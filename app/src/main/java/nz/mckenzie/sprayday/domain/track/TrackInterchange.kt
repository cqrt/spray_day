package nz.mckenzie.sprayday.domain.track

import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * What a track file is read as: a track on the ground, or why the file is not one.
 *
 * **The one place a track file becomes a track.** The phone's own list screen imports a GPX or KML
 * file from a picker and the desk imports one dragged onto a map, and both have to mean the same
 * thing by it: a file whose later paths start on the first is a line with side tracks hanging off it,
 * and a file whose paths do not meet is the file of a tool that cuts a line up for its own reasons,
 * which this app has always read as one line with a jump in it. Two copies of that rule would be two
 * copies to keep in step, and the one that fell behind would be whichever of the two routes is the
 * rarer - so it is written once, here, and both callers are told the same thing about the same file.
 *
 * The format is not the caller's business: the root element says whether the file is GPX or KML, and
 * [GpxParser] or [KmlParser] hands back the same kind of paths either way. It decides nothing itself:
 * the paths it hands back are the paths to store, and what to say about the file is left to the
 * caller, whose words are the ones the operator is looking at.
 */
object TrackInterchange {

    /** The file's paths read as one line, and what became of the ones after the first. */
    data class Reading(
        /** The paths to store: the line first, its side tracks after it. */
        val paths: List<List<GeoPoint>>,
        /**
         * How many of the file's own paths became side tracks. Zero when none did - either
         * because the file has one path, or because they had to be joined up instead.
         */
        val sideTracks: Int,
        /**
         * Whether the file's own paths did **not** meet, and so were joined up into one line.
         *
         * Kept apart from "no side tracks" on purpose: a file that never had a second path and a
         * file whose second path starts somewhere else entirely are both read as one line, and
         * only the second is worth telling the operator about. False for a file that became side
         * tracks, because none of its paths were flattened.
         */
        val segmentsDidNotMeet: Boolean
    )

    /** The paths to store, or why this file is not a track. */
    sealed interface Outcome {

        data class Read(val reading: Reading) : Outcome

        /** In the app's own words, from `AssetRepository.importAssetTrack`: one sentence for both callers. */
        data class Invalid(val message: String) : Outcome
    }

    /**
     * What the file holds, and whether its paths are a line with side tracks.
     *
     * The join has to be exact, because that is what the app's own drawing produces and what its own
     * rules ask for: a side track starts *on* a vertex of the line, to the bit. A file that meets
     * that is read as the paths it wrote; one that does not is read the way this app has always read
     * one - every point in document order, joined up, as a single line.
     */
    fun read(xml: String): Outcome {
        val segments = runCatching { segmentsOf(xml) }.getOrElse { return Outcome.Invalid(MALFORMED) }
        val line = segments.firstOrNull().orEmpty()
        val sideTracks = segments.drop(1)

        val joined = sideTracks.isNotEmpty() &&
            sideTracks.all { side -> side.size >= 2 && line.any { vertex -> vertex == side.first() } }

        if (joined) {
            return Outcome.Read(Reading(listOf(line) + sideTracks, sideTracks.size, segmentsDidNotMeet = false))
        }

        // Every point, in document order, as one line. A file whose paths do not meet is not a
        // refusal - it is a file from a tool that cuts a line up for its own reasons - but the
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

    /**
     * What a file's **bytes** hold, once a zipped `.kmz` has been opened.
     *
     * The picker and the desk both start with bytes, because a KMZ is not text; this opens the
     * archive (see [TrackFile]) and then reads the KML or GPX it finds with [read], so a zipped file
     * means exactly what the same file unzipped means. A file that is not a readable archive is
     * refused in [TrackFile]'s own words rather than with a stack trace.
     */
    fun readBytes(file: ByteArray): Outcome {
        val text = runCatching { TrackFile.text(file) }
            .getOrElse { return Outcome.Invalid(it.message ?: MALFORMED) }
        return read(text)
    }

    /**
     * The file's paths, read by the reader for whichever format its root element names.
     *
     * The document is parsed once and then handed to the reader, so a file is not read twice to find
     * out what it is; a root that is neither `<gpx>` nor `<kml>` goes to the GPX reader, whose own
     * failure is what refuses it - one place decides what a file that is not a track reads as.
     */
    private fun segmentsOf(xml: String): List<List<GeoPoint>> {
        val document = xmlDocument(xml)
        val root = document.documentElement
        val name = root?.localName ?: root?.nodeName?.substringAfterLast(':')
        return if (name.equals("kml", ignoreCase = true)) {
            KmlParser.segmentsOf(document)
        } else {
            GpxParser.segmentsOf(document)
        }
    }

    /** A line of one point is not a line, in the words the repository has always thrown this in. */
    const val TOO_SHORT = "A line needs at least two points"

    /**
     * A file that will not parse, or that is not a track file at all.
     *
     * Said in words rather than thrown, because both callers are showing it to somebody: the phone's
     * picker puts it in a message and the desk puts it on the page, and neither wants a stack trace.
     */
    const val MALFORMED = "That file could not be read as a track. Pick a GPX or KML file and try again."
}
