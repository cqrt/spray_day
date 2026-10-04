package nz.mckenzie.sprayday.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.domain.asset.AssetRemoval
import nz.mckenzie.sprayday.domain.asset.AssetRemovalRules
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.BulkAssetEdits
import nz.mckenzie.sprayday.domain.asset.MethodPhrase
import nz.mckenzie.sprayday.domain.asset.PassPhrase
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.backup.AssetRecord
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.doc.DocTrack
import nz.mckenzie.sprayday.domain.doc.distanceM
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.domain.track.TrackInterchange
import nz.mckenzie.sprayday.domain.tiles.LatLngBounds
import nz.mckenzie.sprayday.ui.AssetEdits

/**
 * What the page is told about the work: the document behind `GET /api/state`, and what a write
 * answers with.
 *
 * It is the backup's own vocabulary - [AssetRecord], [GroupRecord], [ProductRecord], already
 * versioned, already restored by an older build and read by a newer one - rather than a second
 * asset shape invented for the web. A second shape is a second mapping to keep right, and the one
 * that would quietly fall behind is the one nobody looks at. What is added on top is the view
 * fields the page cannot work out for itself: which traffic light the asset is under, when it is
 * next due, and the version an edit has to quote back - all decided by the phone.
 *
 * **The geometry is not in here.** It travels once, in the GeoJSON the style's own source reads
 * (`/api/assets.geojson`, built by [nz.mckenzie.sprayday.map.AssetGeoJson]) - the same document the
 * app's own map draws, so the desk and the phone cannot disagree about where a line goes. A record
 * here carries `points` empty, and its `lengthM` still says how long it is.
 *
 * The clock is the phone's, not the laptop's: `nowEpochMs` is what every due colour in the document
 * was worked out against, so a page whose owner has their clock a day out still agrees with the
 * tractor about what is due.
 */
object WebEditorJson {

    /**
     * Defaults and nulls are written out rather than left off, so the page is handed every key
     * every time: a field that is sometimes absent is a field the page's own code has to guess
     * about, and guessing is what a document is for.
     */
    private val json = Json { encodeDefaults = true }

    fun build(document: WebEditorDocument): String = json.encodeToString(WebEditorDocument.serializer(), document)

    /**
     * One asset as the wire carries it.
     *
     * The mapping is written out here rather than reached for in the backup, where the same
     * mapping is private, and it is pinned field by field by this file's test: a column added to
     * the asset table and forgotten here would be a column the desk never sees, which is a quiet
     * way to lose something the operator typed on the phone.
     *
     * [paths] is the asset's whole geometry - the line, and the side tracks hanging off it - and it is
     * here for the version rather than for the document: the geometry itself travels once, in the
     * GeoJSON the map's own source reads, so the `points` this record carries stay empty - while the
     * desk still has to be told which *line* it was handed, or a card would go on talking about a track
     * that has since been moved, or that has grown a spur while the card was open.
     *
     * [recordingCount] is here for the same kind of reason: it is not shown, it is what the delete
     * sentence counts.
     */
    fun record(
        asset: AssetEntity,
        due: DueInfo,
        sprayCount: Int,
        groupName: String?,
        paths: List<List<GeoPoint>>,
        recordingCount: Int
    ): WebEditorAssetRecord = WebEditorAssetRecord(
        asset = AssetRecord(
            id = asset.id,
            name = asset.name,
            groupId = asset.groupId,
            // The **resolved** kind rather than the stored word. A row drawn before the kinds existed
            // is stored as INFRASTRUCTURE, and with the raw word the desk would have to keep its own
            // copy of the rule that reads it back by its shape - which is the mistake v0.6.43 had to
            // unpick in the page's wording. One reading, on the phone; the page compares names.
            kind = AssetKind.fromStorage(asset.kind, AssetShape.fromStorage(asset.shape)).name,
            shape = asset.shape,
            method = asset.method,
            notes = asset.notes,
            intervalDays = asset.intervalDays,
            swathWidthM = asset.swathWidthM,
            passesRequired = asset.passesRequired,
            passSeparationM = asset.passSeparationM,
            active = asset.active,
            createdAtEpochMs = asset.createdAtEpochMs,
            lastSprayedAtEpochMs = asset.lastSprayedAtEpochMs,
            lengthM = asset.lengthM,
            // The measured ground, beside the cached metres and for the same reason: a carpark's card
            // says how much ground it is, and the desk reads the phone's own number rather than working
            // one out from the corners it can see. Zero for everything that is not ground.
            areaM2 = asset.areaM2,
            points = emptyList()
        ),
        dueStatus = due.status.name,
        dueAtEpochMs = due.dueDateEpochMs,
        daysUntilDue = due.daysUntilDue,
        sprayCount = sprayCount,
        groupName = groupName,
        // Sent back by the desk with an edit, so a change made on the phone while the card was open
        // is refused here rather than written over. See [WebEditorVersion].
        version = WebEditorVersion.of(asset, groupName, paths),
        paths = paths.map { path -> path.map { WebEditorPoint(lat = it.lat, lng = it.lng) } },
        removal = WebEditorRemoval.of(AssetRemovalRules.of(asset.name, sprayCount, recordingCount))
    )

    /**
     * What a save answers with: the asset as the phone now holds it.
     *
     * The whole record rather than an acknowledgement, so the desk can update its own copy without
     * fetching the document again - and so the colour a row wears after a save is the phone's new
     * one rather than the laptop's guess at what its own edit did to the due date.
     */
    fun saved(record: WebEditorAssetRecord): String =
        json.encodeToString(WebEditorSaved.serializer(), WebEditorSaved(record))

    /**
     * What a refusal answers with.
     *
     * [reason] is for the page's own code to act on - it decides whether the card is stale and has
     * to be reloaded - and [message] is for the operator, in the words the phone itself would use.
     */
    fun refused(refusal: WebEditorRefusal, message: String): String =
        json.encodeToString(WebEditorRefused.serializer(), WebEditorRefused(refusal.reason, message))

    /**
     * What a delete answers with.
     *
     * One sentence and nothing else, because there is no record left to send: the page's next move is
     * to read the work again, which is how it learns what is on the farm now.
     */
    fun removed(message: String): String =
        json.encodeToString(WebEditorRemoved.serializer(), WebEditorRemoved(message))

    /**
     * What a bulk edit answers with: how many rows were changed, and the phone's sentence about it.
     *
     * No records travel with it, and that is deliberate rather than lazy. A bulk edit is the one write
     * whose answer is *cheaper read again*: six rows is more than a list this size wants to carry, and
     * the page's own copy of the work is out of date in every one of them - a rename changes the row,
     * a block change moves it, a kind change re-colours it. So the desk reads the work once instead of
     * taking six records, which is also what keeps a card that was open on one of them honest.
     */
    fun edited(count: Int, message: String): String =
        json.encodeToString(WebEditorEdited.serializer(), WebEditorEdited(count = count, message = message))

    /**
     * What reading a dropped track file answers with: the first track's paths, as the desk draws them.
     *
     * The reading's own facts travel with them rather than being worked out again by the page: how
     * many of the file's paths became side tracks, whether they had to be joined up instead, and how
     * many further tracks the file held - the desk draws one, and the page says so rather than letting
     * the rest vanish. The numbers in those words are the phone's.
     */
    fun gpx(track: TrackInterchange.Track, otherTracks: Int): String = json.encodeToString(
        WebEditorGpx.serializer(),
        WebEditorGpx(
            paths = track.reading.paths.map { path -> path.map { WebEditorPoint(lat = it.lat, lng = it.lng) } },
            sideTracks = track.reading.sideTracks,
            segmentsDidNotMeet = track.reading.segmentsDidNotMeet,
            otherTracks = otherTracks
        )
    )

    /**
     * What a DOC Tracks search answers with: the tracks it matched, and the phone's own word for a
     * search that could not be made at all.
     *
     * The geometry travels here rather than being asked for again at import, because a track ticked on
     * the desk is imported from the very vertices the desk was shown - so a service that changed
     * between the search and the import cannot put a different line on the farm.
     */
    fun docSearch(
        tracks: List<DocTrack>,
        importedRefs: Set<String>,
        from: GeoPoint?,
        message: String?,
        capped: Boolean
    ): String =
        json.encodeToString(
            WebEditorDocSearch.serializer(),
            WebEditorDocSearch(
                tracks = tracks.map { track ->
                    WebEditorDocTrack(
                        id = track.objectId,
                        name = track.name,
                        kind = track.kind,
                        points = track.pointCount,
                        lengthM = track.lengthM,
                        paths = track.reading.paths.map { path ->
                            path.map { WebEditorPoint(lat = it.lat, lng = it.lng) }
                        },
                        imported = track.sourceRef in importedRefs,
                        // The phone's own fix, so the desk can order by "nearest to phone" without
                        // its own distance arithmetic - and cannot disagree with the phone's order.
                        distanceM = from?.let { track.distanceM(it) }
                    )
                },
                message = message,
                capped = capped
            )
        )

    /** What importing the ticked tracks answers with: how many were made, and the phone's sentence. */
    fun docImported(count: Int, message: String): String =
        json.encodeToString(WebEditorDocImported.serializer(), WebEditorDocImported(imported = count, message = message))
}

/** Everything `GET /api/state` answers with. */
@Serializable
data class WebEditorDocument(
    /** The phone's clock, which every due status in this document was worked out against. */
    val nowEpochMs: Long,
    /** The active assets. An archived one is not on the map the operator works from. */
    val assets: List<WebEditorAssetRecord> = emptyList(),
    val groups: List<GroupRecord> = emptyList(),
    /** Read-only on the desk: the catalogue is the phone's business. */
    val products: List<ProductRecord> = emptyList(),
    /** The box the work is in, so the page can open on the farm rather than on the ocean. */
    val bounds: WebEditorBounds? = null,
    /** The phone's last fix, if it has one. Better than the browser's own: see the plan. */
    val position: WebEditorPosition? = null,
    /**
     * The phone's own traffic light, one colour per due state, keyed by the state's own name.
     *
     * The page's key is the one thing that cannot take its colour from a feature: a state nothing
     * is in has no feature to be painted by, and a grey dot beside "Overdue 0" would be the page's
     * own opinion about what overdue looks like - the one thing this arrangement exists to avoid.
     */
    val dueColours: Map<String, String> = emptyMap(),
    /**
     * The words and the choices the phone's own edit form offers.
     *
     * Carried in the document rather than written into the page, for the same reason the colours and
     * the dash patterns are: a second copy of the vocabulary is a second copy to keep in step, and
     * the one that falls behind is the one nobody looks at. A kind renamed on the phone is renamed
     * on the desk the next time the page is opened.
     */
    val choices: WebEditorChoices = WebEditorChoices.ofApp(),
    /**
     * What an asset somebody draws from scratch starts as.
     *
     * The phone's own defaults for a new asset - the same ones `AssetRepository.createAsset` and the
     * phone's own drawing screen use - so a blank form on a laptop starts where a blank form on the
     * phone starts. A "120" typed into JavaScript would be a second copy of a default, and the one that
     * falls behind is the one nobody looks at; this way the interval the desk fills in is the interval
     * the phone would have filled in.
     */
    val newAsset: WebEditorNewAsset = WebEditorNewAsset.ofApp(),
    /**
     * What a dropped track file (GPX, KML, KMZ or GeoJSON) has to know: the name it travels under, and the
     * largest file the phone will take. Both the phone's own answers - see [WebEditorGpxRules].
     */
    val gpx: WebEditorGpxRules = WebEditorGpxRules.ofApp(),
    /**
     * What changing several assets at once has to know, so the page does not invent either of them.
     */
    val together: WebEditorTogetherRules = WebEditorTogetherRules.ofApp()
)

/**
 * What a new asset is before anybody says otherwise.
 *
 * Numbers as the field would hold them ("120"), because that is what the form's fields are made of: a
 * page that did its own formatting would be a page that could send "120.0" to a rule expecting a whole
 * number's worth of digits.
 */
@Serializable
data class WebEditorNewAsset(
    val kind: String,
    val method: String,
    val intervalDays: String,
    val passesRequired: Int
) {
    companion object {
        fun ofApp() = WebEditorNewAsset(
            kind = AssetKind.TRACK.name,
            method = SprayMethod.UNSET.name,
            intervalDays = AssetEntity.DEFAULT_INTERVAL_DAYS.toString(),
            passesRequired = AssetEntity.DEFAULT_PASSES_REQUIRED
        )
    }
}

/**
 * One asset, with the traffic light the page paints it in.
 */
@Serializable
data class WebEditorAssetRecord(
    val asset: AssetRecord,
    /** [nz.mckenzie.sprayday.domain.due.DueStatus] name, e.g. `DUE_SOON`. */
    val dueStatus: String,
    /** The start of the day it is next due, phone's time zone; null when never sprayed. */
    val dueAtEpochMs: Long? = null,
    /** Negative when overdue, null when never sprayed, so the page can word it without arithmetic. */
    val daysUntilDue: Long? = null,
    val sprayCount: Int = 0,
    val groupName: String? = null,
    /**
     * The whole of the asset's drawing: the line first, its side tracks after it.
     *
     * The geometry a page **draws** comes from the GeoJSON, one feature per path, and it always has -
     * but a page cannot hand back what it only ever saw as drawn lines, and a write that carried one
     * path for a track with side tracks would drop them. So the paths a write has to carry are here,
     * beside the version: the same half of the contract, for the same reason.
     *
     * Empty for an asset with nothing drawn and for a place with no spot yet. A page that ignores this
     * field still works: its writes carry one path, and the phone refuses those for a track that has
     * side tracks rather than writing over them.
     */
    val paths: List<List<WebEditorPoint>> = emptyList(),
    /**
     * What an edit to this asset has to quote back, so the phone can refuse one written against an
     * asset that has since changed. Opaque here on purpose: only the phone works out what it means.
     */
    val version: String,
    /**
     * What deleting it would take, and whether the desk may do it at all.
     *
     * Always present, so the page never has to work the rule out for itself: it greys the button out
     * or it does not, and it shows [WebEditorRemoval.sentence] either way.
     */
    val removal: WebEditorRemoval
)

/**
 * What deleting an asset from the desk would take with it.
 *
 * [allowed] is false once anything is recorded against it - a spray, a recording - and then the
 * sentence says how much and where the delete belongs instead. See
 * [nz.mckenzie.sprayday.domain.asset.AssetRemovalRules] for why the desk may not put an asset away.
 */
@Serializable
data class WebEditorRemoval(val allowed: Boolean, val sentence: String) {
    companion object {
        fun of(removal: AssetRemoval) = WebEditorRemoval(
            allowed = removal.allowed,
            sentence = removal.sentence
        )
    }
}

/** What a delete answers with: the phone's own sentence about what happened. */
@Serializable
data class WebEditorRemoved(val message: String)

/**
 * What a bulk edit answers with: how many rows were changed, and the phone's own sentence about it.
 *
 * The count travels beside the sentence rather than inside it so a page can do arithmetic with it
 * without reading prose - and so the sentence stays the phone's to word.
 */
@Serializable
data class WebEditorEdited(val count: Int, val message: String)

/**
 * What `POST /api/gpx` answers with: the line and side tracks a dropped file holds.
 *
 * [paths] is exactly the shape the write that follows carries them back in, so a drawing read out of
 * a file is the same kind of thing as one traced over the imagery - which is why the desk can let it
 * be tidied, extended and saved by the one path every drawing takes.
 */
@Serializable
data class WebEditorGpx(
    /** Path 0 is the line, the rest its side tracks. */
    val paths: List<List<WebEditorPoint>> = emptyList(),
    /** How many of the file's own segments became side tracks. */
    val sideTracks: Int = 0,
    /**
     * Whether the file's segments did not meet, and every point was joined into one line instead.
     *
     * Here rather than left to the page to work out from a count of paths: the difference between a
     * file with one segment and a file whose segments could not be joined is a difference the page
     * cannot see, and it is the one an operator wants told about.
     */
    val segmentsDidNotMeet: Boolean = false,
    /**
     * How many further tracks the file held beyond the one the desk is drawing.
     *
     * The desk holds a single drawing, so a file of named tracks - a GeoJSON FeatureCollection - is
     * drawn one track at a time; this is what lets the page say the rest are there rather than
     * leaving the operator to think the whole file was four times smaller than it was.
     */
    val otherTracks: Int = 0
)

/** A box on the ground, in the order a person would say it. */
@Serializable
data class WebEditorBounds(
    val minLat: Double,
    val minLng: Double,
    val maxLat: Double,
    val maxLng: Double
) {
    companion object {
        fun of(bounds: LatLngBounds) = WebEditorBounds(
            minLat = bounds.minLat,
            minLng = bounds.minLng,
            maxLat = bounds.maxLat,
            maxLng = bounds.maxLng
        )
    }
}

/** Where the phone was when it last knew. */
@Serializable
data class WebEditorPosition(val lat: Double, val lng: Double)

/**
 * The body `POST /api/gpx` carries: the file the operator dropped, base64-encoded.
 *
 * Base64 rather than the file's own characters, because a KMZ is a zip and not text - a GPX, KML or
 * GeoJSON file would survive as text, but a zipped one would not, and one wire for every file is
 * simpler than two. The bytes are the file exactly as it was on disk, so nothing about a track's name,
 * its coordinates or its compression is changed by the trip.
 */
@Serializable
data class WebEditorGpxBody(
    /** See [WebEditorServer.GPX_FIELD] for why the page is told this name rather than writing it in. */
    val gpx: String = ""
)

/**
 * The track-file facts the page has to know, carried in the state document.
 *
 * The field a dropped file travels under, and how large a file the phone will take. Both are the
 * phone's answers: a page that guessed the field name would send a file the phone read as absent,
 * which is indistinguishable from a GPX, KML, KMZ or GeoJSON file with no track in it, and a page that
 * guessed the size would pass on a refusal about a request that never arrived. The largest is
 * [WebEditorServer.MAX_GPX_BYTES] - the file's own size, which is what the page can check before
 * anything is sent.
 */
@Serializable
data class WebEditorGpxRules(
    val field: String,
    val maxBytes: Int = WebEditorServer.MAX_GPX_BYTES
) {
    companion object {
        fun ofApp() = WebEditorGpxRules(field = WebEditorServer.GPX_FIELD)
    }
}

/**
 * A DOC track as the desk's own browser lists it: its key, its name, its kind, its own metres and
 * points, and the geometry an import sends back.
 *
 * The geometry is here - unlike an asset's record, which leaves it to the GeoJSON document - because
 * these tracks are not on the phone yet: the desk has to be able to draw them, and ticking one has to
 * be able to make it without a second trip to DOC.
 */
@Serializable
data class WebEditorDocTrack(
    val id: Long = 0,
    val name: String = "",
    val kind: String? = null,
    val points: Int = 0,
    val lengthM: Double = 0.0,
    val paths: List<List<WebEditorPoint>> = emptyList(),
    /** True when a track with this source is already on the phone: the desk shows it and will not
     * import it again. */
    val imported: Boolean = false,
    /**
     * The metres from the phone's own fix to the nearest vertex of this track, or null when the phone
     * has no fix. Worked out on the phone so the desk's "nearest" order is the phone's.
     */
    val distanceM: Double? = null
)

/**
 * The answer to `GET /api/doc`: the tracks a search matched, and the phone's own word when there is
 * one to say - nothing matched, or the service could not be asked. [capped] is true when the service
 * returned a full page, so the desk can say the search showed the first of more.
 */
@Serializable
data class WebEditorDocSearch(
    val tracks: List<WebEditorDocTrack> = emptyList(),
    val message: String? = null,
    val capped: Boolean = false
)

/** One track the desk ticked, on its way back to be made into an asset. */
@Serializable
data class WebEditorDocTrackBody(
    /** The service's own key, kept so the asset records where it came from. */
    val id: Long = 0,
    val name: String = "",
    val paths: List<List<WebEditorPoint>> = emptyList()
)

/** The body `POST /api/doc/import` carries: the ticked tracks, with the geometry they were shown. */
@Serializable
data class WebEditorDocImportBody(
    val tracks: List<WebEditorDocTrackBody> = emptyList()
)

/** The answer to an import: how many assets were made, and the phone's own sentence about it. */
@Serializable
data class WebEditorDocImported(
    val imported: Int = 0,
    val message: String = ""
)

/**
 * The facts a bulk edit needs, carried in the state document rather than written into the page.
 *
 * [maxAssets] is how many rows one request may carry - the phone refuses more, and the page says so
 * before sending rather than passing on a refusal about the size of a request. [different] is the word
 * the page shows for a field the picked assets do not agree on: one word, from the phone, so the desk
 * and any screen the phone grows later say the same thing about the same state.
 */
@Serializable
data class WebEditorTogetherRules(
    val maxAssets: Int = BulkAssetEdits.MAX_ASSETS,
    val different: String = DIFFERENT
) {
    companion object {
        /**
         * "More than one", because that is what it means and it is said to the operator rather than
         * about them: a field showing this is a field the picked rows disagree on.
         */
        const val DIFFERENT = "More than one"

        fun ofApp() = WebEditorTogetherRules()
    }
}

/** One thing an operator can pick, as the phone stores it and as the phone says it. */
@Serializable
data class WebEditorChoice(
    val value: String,
    val label: String,
    /**
     * The swath width this choice implies, or null when picking it implies nothing.
     *
     * Only the spray methods carry one, and it is [nz.mckenzie.sprayday.domain.asset.SprayMethod.defaultSwathM]
     * - the phone's own table. The desk uses it the way [AssetEdits.swathAfterMethodChange] does: a
     * width left blank, or still holding the previous method's own default, moves with the choice,
     * and a width the operator typed does not. Without it, picking "Knapsack" on a laptop would leave
     * a boom's three metres on a backpack, which is the thing that rule exists to stop.
     */
    val swathM: String? = null,

    /**
     * The shape this choice makes, or null when picking it makes nothing.
     *
     * Only the kinds carry one, and it is [nz.mckenzie.sprayday.domain.asset.AssetKind.shape] -
     * [AssetShape]'s name, the same word the phone's own screens read and the record stores. The desk
     * draws a ring differently from a line: the closing side is drawn as it is drawn, the ground
     * inside is counted, and a side track is not offered. A page that had to recognise "Carpark" by
     * name to know that would be a second copy of [AssetKind], drifting from this one the day a
     * second kind of ground ships.
     */
    val shape: String? = null
)

/**
 * The vocabulary the desk's form offers, taken from the phone's own phrases.
 *
 * Every list here is a list the phone's own edit screen offers, and every label is the string that
 * screen shows - including the three sentences under the fields, which are the phone's and travel
 * with the rules they explain rather than being written again in JavaScript. What the page does with
 * them is draw a `<select>`; what it must not do is decide what the options are.
 */
@Serializable
data class WebEditorChoices(
    val kinds: List<WebEditorChoice>,
    val methods: List<WebEditorChoice>,
    /** One pass or two. The value is the number, because that is what the wire carries. */
    val passes: List<WebEditorChoice>,
    /** What the field under the typed name says when it is empty. */
    val blockHint: String,
    val swathHint: String,
    val separationHint: String,
    /**
     * What a kind that is ground says where a line would have had a swath width and a pass count.
     *
     * [AssetEdits.GROUND_HINT], word for word, because the desk's form is the phone's form and a
     * field that vanishes on a laptop without a word reads as a screen that has lost something.
     */
    val groundHint: String
) {
    companion object {
        fun ofApp() = WebEditorChoices(
            // The shape travels beside the label so the desk can draw a ring as ground - see
            // [WebEditorChoice.shape] - and it is the phone's own answer rather than the page's.
            kinds = AssetPhrase.kinds.map {
                WebEditorChoice(it.name, AssetPhrase.kind(it), shape = it.shape.name)
            },
            methods = MethodPhrase.choices.map {
                WebEditorChoice(
                    value = it.name,
                    label = MethodPhrase.choice(it),
                    swathM = it.defaultSwathM?.let(::plainNumber)
                )
            },
            passes = PassPhrase.choices.map { WebEditorChoice(it.toString(), PassPhrase.choice(it)) },
            blockHint = AssetEdits.BLOCK_HINT,
            swathHint = AssetEdits.SWATH_HINT,
            separationHint = PassPhrase.SEPARATION_HINT,
            groundHint = AssetEdits.GROUND_HINT
        )

        /**
         * A number as the field would hold it: "3" rather than "3.0".
         *
         * `AssetEdits` formats its own defaults the same way, and that formatter is private to the
         * rules it belongs to. Three metres and one metre are the only two numbers that ever come
         * through here, so this is a formatting decision rather than a copy of a table.
         */
        private fun plainNumber(value: Double): String =
            if (value == Math.floor(value) && !value.isInfinite()) {
                value.toInt().toString()
            } else {
                value.toString()
            }
    }
}

/** What `PUT /api/assets/<id>` answers a save with: the asset as the phone now holds it. */
@Serializable
data class WebEditorSaved(val asset: WebEditorAssetRecord)

/**
 * What a write answers a refusal with.
 *
 * [reason] is the machine's half - [WebEditorRefusal.reason], so a page can tell a stale card from
 * a field it got wrong - and [message] is the operator's half, already worded by the phone.
 */
@Serializable
data class WebEditorRefused(val reason: String, val message: String)
