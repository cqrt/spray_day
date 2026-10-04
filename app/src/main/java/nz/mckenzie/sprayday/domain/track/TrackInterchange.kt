package nz.mckenzie.sprayday.domain.track

import nz.mckenzie.sprayday.domain.geo.GeoPoint

/**
 * What a track file is read as: the track or tracks on the ground, or why the file is not one.
 *
 * **The one place a track file becomes a track.** The phone's own list screen imports a file from a
 * picker and the desk imports one dragged onto a map, and both have to mean the same thing by it: a
 * file whose later paths start on the first is a line with side tracks hanging off it, and a file
 * whose paths do not meet is the file of a tool that cuts a line up for its own reasons, which this
 * app has always read as one line with a jump in it. Two copies of that rule would be two copies to
 * keep in step, and the one that fell behind would be whichever of the two routes is the rarer - so
 * it is written once, here, and both callers are told the same thing about the same file.
 *
 * The format is not the caller's business: a `{` or `[` says GeoJSON, and an XML root of `<kml>` or
 * `<gpx>` says which XML reader to use, and every one of them hands back the same kind of paths.
 * It decides nothing itself: the paths it hands back are the paths to store, and what to say about
 * the file is left to the caller, whose words are the ones the operator is looking at.
 *
 * **A file may hold more than one track.** A GeoJSON FeatureCollection is a set of named tracks, and
 * each feature becomes its own asset rather than being flattened with the rest - see [readFile]. A
 * GPX or KML file is one track, so [readFile] hands back one.
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

    /**
     * One track a file holds: what to call it where the format names it, and its reading.
     *
     * [name] is null when the format gives no name - a GPX or KML track is imported under the file's
     * own name, as it always was - and a GeoJSON feature carries its own when its properties have one.
     */
    data class Track(val name: String?, val reading: Reading)

    /** The paths to store, or why this file is not a track. */
    sealed interface Outcome {

        data class Read(val reading: Reading) : Outcome

        /** In the app's own words, from the repository's import: one sentence for both callers. */
        data class Invalid(val message: String) : Outcome
    }

    /** Every track the file holds, or why it holds none. */
    sealed interface FileOutcome {

        data class Read(val tracks: List<Track>) : FileOutcome

        data class Invalid(val message: String) : FileOutcome
    }

    /**
     * What the file holds, and whether its paths are a line with side tracks.
     *
     * The join has to be exact, because that is what the app's own drawing produces and what its own
     * rules ask for: a side track starts *on* a vertex of the line, to the bit. A file that meets
     * that is read as the paths it wrote; one that does not is read the way this app has always read
     * one - every point in document order, joined up, as a single line.
     */
    fun read(file: String): Outcome = firstOf(readText(file))

    /**
     * The first (or only) track a file's **bytes** hold.
     *
     * Kept for callers that want one reading and nothing else; the desk and the phone both use
     * [readFile], which hands back every track a file holds. A file that is not a readable archive is
     * refused in [TrackFile]'s own words rather than with a stack trace.
     */
    fun readBytes(file: ByteArray): Outcome {
        val text = runCatching { TrackFile.text(file) }
            .getOrElse { return Outcome.Invalid(it.message ?: MALFORMED) }
        return firstOf(readText(text))
    }

    /**
     * Every track a file's bytes hold, once a zipped `.kmz` has been opened.
     *
     * The picker and the desk both start with bytes, because a KMZ is not text; this opens the
     * archive (see [TrackFile]) and then reads the GeoJSON, KML or GPX inside, so a zipped file means
     * exactly what the same file unzipped means.
     */
    fun readFile(file: ByteArray): FileOutcome {
        val text = runCatching { TrackFile.text(file) }
            .getOrElse { return FileOutcome.Invalid(it.message ?: MALFORMED) }
        return readText(text)
    }

    /**
     * The file's text read by whichever format it is.
     *
     * GeoJSON announces itself with a `{` or `[`; anything else is XML, read by [KmlParser] or
     * [GpxParser] according to its root element. A document that is neither is refused in one place,
     * so what a file that is not a track reads as is decided once.
     */
    private fun readText(text: String): FileOutcome {
        val trimmed = text.trimStart('\uFEFF', ' ', '\t', '\n', '\r')
        if (trimmed.startsWith("{") || trimmed.startsWith("[")) return geoJson(trimmed)

        val document = runCatching { xmlDocument(text) }.getOrElse { return FileOutcome.Invalid(MALFORMED) }
        val root = document.documentElement
        val name = root?.localName ?: root?.nodeName?.substringAfterLast(':')
        val segments = if (name.equals("kml", ignoreCase = true)) {
            KmlParser.segmentsOf(document)
        } else {
            GpxParser.segmentsOf(document)
        }
        return when (val outcome = readingOf(segments)) {
            is Outcome.Read -> FileOutcome.Read(listOf(Track(null, outcome.reading)))
            is Outcome.Invalid -> FileOutcome.Invalid(outcome.message)
        }
    }

    /** A GeoJSON FeatureCollection read as one track per feature, each under its own name. */
    private fun geoJson(text: String): FileOutcome {
        val features = runCatching { GeoJsonParser.parse(text) }
            .getOrElse { return FileOutcome.Invalid(MALFORMED) }
        // A feature that is not a line at all - a Point, or a stray single vertex - is dropped rather
        // than refusing the whole file: a set of tracks with one odd member is still a set of tracks.
        val tracks = features.mapNotNull { feature ->
            when (val outcome = readingOf(feature.paths)) {
                is Outcome.Read -> Track(feature.name, outcome.reading)
                is Outcome.Invalid -> null
            }
        }
        if (tracks.isEmpty()) return FileOutcome.Invalid(TOO_SHORT)
        return FileOutcome.Read(tracks)
    }

    private fun firstOf(outcome: FileOutcome): Outcome = when (outcome) {
        is FileOutcome.Invalid -> Outcome.Invalid(outcome.message)
        is FileOutcome.Read ->
            outcome.tracks.firstOrNull()?.let { Outcome.Read(it.reading) } ?: Outcome.Invalid(TOO_SHORT)
    }

    /**
     * The join rule itself, over one track's paths.
     *
     * Shared by every format: an XML file has one track's paths and a GeoJSON feature has one track's
     * paths, and the rule does not care which it was.
     */
    private fun readingOf(segments: List<List<GeoPoint>>): Outcome {
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
     * One track's paths read by the join rule, for a caller that already has the paths.
     *
     * The DOC Tracks browser queries tracks from a web service and already holds each feature's
     * geometry, so it needs the rule without a file to read first. One rule, whichever door.
     */
    fun reading(paths: List<List<GeoPoint>>): Outcome = readingOf(paths)

    /** A line of one point is not a line, in the words the repository has always thrown this in. */
    const val TOO_SHORT = "A line needs at least two points"

    /**
     * A file that will not parse, or that is not a track file at all.
     *
     * Said in words rather than thrown, because both callers are showing it to somebody: the phone's
     * picker puts it in a message and the desk puts it on the page, and neither wants a stack trace.
     */
    const val MALFORMED =
        "That file could not be read as a track. Pick a GPX, KML, KMZ or GeoJSON file and try again."
}
