package nz.mckenzie.sprayday.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPhrase
import nz.mckenzie.sprayday.domain.asset.AssetRemoval
import nz.mckenzie.sprayday.domain.asset.AssetRemovalRules
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.MethodPhrase
import nz.mckenzie.sprayday.domain.asset.PassPhrase
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.backup.AssetRecord
import nz.mckenzie.sprayday.domain.backup.GroupRecord
import nz.mckenzie.sprayday.domain.backup.ProductRecord
import nz.mckenzie.sprayday.domain.due.DueInfo
import nz.mckenzie.sprayday.domain.geo.GeoPoint
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
            kind = asset.kind,
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
    val newAsset: WebEditorNewAsset = WebEditorNewAsset.ofApp()
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
    val shape: String,
    val method: String,
    val intervalDays: String,
    val passesRequired: Int
) {
    companion object {
        fun ofApp() = WebEditorNewAsset(
            kind = AssetKind.TRACK.name,
            shape = AssetShape.LINE.name,
            method = SprayMethod.UNSET.name,
            intervalDays = AssetEntity.DEFAULT_INTERVAL_DAYS.toString(),
            passesRequired = AssetEntity.DEFAULT_PASSES_REQUIRED
        )
    }
}

/** One asset, with the traffic light the page paints it in. */
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
    val swathM: String? = null
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
    val shapes: List<WebEditorChoice>,
    val methods: List<WebEditorChoice>,
    /** One pass or two. The value is the number, because that is what the wire carries. */
    val passes: List<WebEditorChoice>,
    /** What the field under the typed name says when it is empty. */
    val blockHint: String,
    val swathHint: String,
    val separationHint: String
) {
    companion object {
        fun ofApp() = WebEditorChoices(
            kinds = AssetPhrase.kinds.map { WebEditorChoice(it.name, AssetPhrase.kind(it)) },
            shapes = AssetPhrase.shapes.map { WebEditorChoice(it.name, AssetPhrase.shapeChoice(it)) },
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
            separationHint = PassPhrase.SEPARATION_HINT
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
