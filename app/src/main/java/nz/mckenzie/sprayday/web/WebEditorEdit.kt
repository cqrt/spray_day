package nz.mckenzie.sprayday.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetPathEdits
import nz.mckenzie.sprayday.domain.asset.AssetPathResult
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod
import nz.mckenzie.sprayday.domain.geo.AssetGeometry
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.ui.AssetEditFields
import nz.mckenzie.sprayday.ui.AssetEditResult
import nz.mckenzie.sprayday.ui.AssetEdits
import java.security.MessageDigest

/*
 * What a write to the editor is, and what the phone does with it.
 *
 * The desk decides nothing here either, which is the arrangement the whole feature rests on. It
 * sends the fields as text, exactly as they were typed, and this hands them to [AssetEdits] - the
 * object the phone's own edit screen uses - so the ranges, the blank-means-unknown rules and the
 * sentences that explain a refusal are the app's own rather than a second copy kept in step by
 * hand. A field the desk does not send cannot be lost, because the edit is applied to the row as
 * the phone holds it now and the repository writes that row back: the desk cannot overwrite a
 * last-sprayed date or an archived flag it never heard of.
 *
 * **Why a fingerprint and not a version column.** The desk has to be able to say "I am changing the
 * asset I read" and the phone has to be able to answer "that is not the asset you read any more".
 * A column would cost a Room migration and a field in the backup's vocabulary - a shape an older
 * build has to tolerate - to carry a number derived from the very row it sits in. So the version is
 * derived instead: [WebEditorVersion] hashes the fields the desk may write, the state document
 * hands it out, and the edit sends it back. It moves exactly when something the desk could
 * overwrite moves, and it costs the database nothing at all.
 *
 * **The path travels in the same body as the words.** Moving a vertex is not a second kind of write
 * with its own rules and its own staleness: the desk sends the details it is looking at *and* the line
 * it has drawn, judged by [AssetPathEdits] and written by the repository in one transaction. So a
 * drawing mode on the desk never holds a half-filled form: the row it sends is the row it read, with
 * one thing moved.
 *
 * **Deleting is judged when it happens**, not from what a card said a minute ago: see
 * [WebEditorRemoval] and [nz.mckenzie.sprayday.domain.asset.AssetRemovalRules] for the rule and for
 * why the desk may not put an asset away.
 */

/**
 * An edit as the desk sends it.
 *
 * Every number arrives as the text the field held, for the reason [AssetEdits] exists: what a
 * blank, a comma or a stray space means is decided in one place, and it is not decided here.
 */
@Serializable
data class WebEditorEdit(
    val name: String,
    val kind: String,
    val shape: String,
    val method: String,
    /** The block the field named, or absent for an asset on its own. */
    val blockName: String? = null,
    val intervalDays: String,
    val swathWidthM: String = "",
    val passesRequired: Int = AssetEntity.DEFAULT_PASSES_REQUIRED,
    val passSeparationM: String = "",
    val notes: String? = null,
    /**
     * The line the desk has drawn, when this write is about where the asset goes.
     *
     * Absent means "nothing about the drawing has changed", which is what the details form sends -
     * the same body either way, so a page has one shape of write to get right. Present means every
     * vertex, in order, as the map holds them: the desk sends the whole line rather than what
     * changed, which is what makes Ctrl+Z on a laptop cost the phone nothing.
     */
    val points: List<WebEditorPoint>? = null,
    /**
     * What the desk read when it opened this asset: see [WebEditorVersion].
     *
     * Blank on a body that is making a new asset, the one write with nothing to be stale against. A
     * write to an asset the phone already has is refused without it, because there would be nothing
     * to compare the phone's row against.
     */
    val version: String = ""
)

/** One vertex, as the wire carries it: the field names the phone's own [GeoPoint] uses. */
@Serializable
data class WebEditorPoint(val lat: Double, val lng: Double)

/**
 * Why a write was refused, and the status each reason is answered with.
 *
 * A table, with a test pinning it, rather than a `when` in the server: a page is written against
 * these numbers, so what a 409 means is part of the contract and not a detail of the routing.
 */
enum class WebEditorRefusal(val status: Int, val reason: String) {

    /** No asset with that id is on the phone, or it is archived and so not the desk's to change. */
    MISSING(404, "not-found"),

    /** Somebody changed what this edit would have written over. */
    STALE(409, "stale"),

    /**
     * The phone will not take it away: sprays, or a recording, are attached.
     *
     * Its own status rather than a 409 of the stale kind, because a page does something different
     * with it: nothing has moved and nothing needs reloading, the answer is simply no, with the
     * numbers in [WebEditorWrite.Refused.message] and the phone named as the place to do it.
     */
    IN_USE(409, "in-use"),

    /** A field the phone will not take. [WebEditorWrite.Refused.message] says which and why. */
    INVALID(400, "invalid")
}

/** What an edit turned into: a row to write, or why it will not be written. */
sealed interface WebEditorEditResult {

    /**
     * The row to store and the block it belongs in - the name the operator typed, which the
     * repository resolves inside the same transaction as the write.
     *
     * [points] is the line to store with the row, or null when this write said nothing about the
     * drawing and the geometry stays exactly as it is.
     */
    data class Ok(
        val asset: AssetEntity,
        val blockName: String?,
        val points: List<GeoPoint>? = null
    ) : WebEditorEditResult

    data class Refused(val refusal: WebEditorRefusal, val message: String) : WebEditorEditResult
}

/** A new asset, as a desk's drawing and the form beside it describe one. */
data class WebEditorDraft(
    val asset: AssetEntity,
    val blockName: String?,
    /**
     * The geometry to store with it, and never empty: an asset with nothing drawn is not something
     * the desk can make, and [AssetPathEdits] refuses that for both shapes - so this type says what is
     * true rather than leaving every caller to check it.
     *
     * A [nz.mckenzie.sprayday.domain.geo.AssetGeometry] rather than a list of points, because that is
     * what the repository writes: a track made here has one line and no side tracks, and saying so in
     * the type is how the writing code holds no opinion about it.
     */
    val geometry: AssetGeometry
)

/** What making a new asset turned into. */
sealed interface WebEditorCreateResult {

    data class Ok(val draft: WebEditorDraft) : WebEditorCreateResult

    data class Refused(val refusal: WebEditorRefusal, val message: String) : WebEditorCreateResult
}

/** What a route answers a write with. */
sealed interface WebEditorWrite {

    /** The asset as the phone now holds it, so the page can update its own copy at once. */
    data class Saved(val record: WebEditorAssetRecord) : WebEditorWrite

    /** The same document, for an asset that did not exist until this write: answered with a 201. */
    data class Created(val record: WebEditorAssetRecord) : WebEditorWrite

    /** Something is gone from the phone: the phone's own sentence saying what happened. */
    data class Removed(val message: String) : WebEditorWrite

    data class Refused(val refusal: WebEditorRefusal, val message: String) : WebEditorWrite
}

/**
 * The version of an asset as far as the desk is concerned.
 *
 * A hash of the fields the desk may write - the geometry among them, because the desk writes that
 * too - and of nothing else. So a spray recorded while the card is open does not refuse the edit,
 * because the desk cannot overwrite a spray, while a rename on the phone does, because it is a field
 * this edit is about to write. The id is in the hash as well: an edit written against one asset must
 * not be taken for an edit to another that happens to hold the same fields, and the id is where such
 * a mix-up would show.
 *
 * The path is hashed as the coordinates themselves, in order, one vertex to a line. Both ends of the
 * check read those numbers out of the same rows, so the two hashes agree to the bit - while a vertex
 * moved by anybody, the phone or a second tab or a GPX import, changes the version, and a card
 * quoting the old one is refused rather than quietly undoing the move.
 */
object WebEditorVersion {

    /**
     * The fingerprint of one asset, as a desk may write it.
     *
     * [paths] is the whole geometry - the line first and its side tracks after it - because a side
     * track added on the phone is a change to what the desk is holding: a card that never saw the
     * spur is out of date in exactly the way a card that never saw a renamed track is. The paths are
     * hashed with their boundaries marked, so the same vertices split differently are a different
     * version rather than a collision.
     */
    fun of(
        asset: AssetEntity,
        blockName: String?,
        paths: List<List<GeoPoint>> = emptyList()
    ): String {
        // Named fields, one to a line. A hash of joined values would read "Estuary" and "3" the same
        // as "Estuary 3" and nothing, which is a collision an operator could type by accident.
        val fields = listOf(
            "id" to asset.id.toString(),
            "name" to asset.name,
            "kind" to asset.kind,
            "shape" to asset.shape,
            "method" to asset.method,
            "block" to blockName.orEmpty(),
            "notes" to asset.notes.orEmpty(),
            "intervalDays" to asset.intervalDays.toString(),
            "swathWidthM" to asset.swathWidthM?.toString().orEmpty(),
            "passesRequired" to asset.passesRequired.toString(),
            "passSeparationM" to asset.passSeparationM?.toString().orEmpty()
        ) + paths.flatMapIndexed { pathIndex, path ->
            listOf("path$pathIndex" to path.size.toString()) +
                path.mapIndexed { index, point ->
                    "point$pathIndex.$index" to "${point.lat},${point.lng}"
                }
        }
        val canonical = fields.joinToString(separator = "") { (name, value) ->
            "$name=${value.replace("\n", "\\n")}\n"
        }
        val digest = MessageDigest.getInstance("SHA-256").digest(canonical.toByteArray(Charsets.UTF_8))
        return digest.joinToString(separator = "") { byte ->
            (byte.toInt() and 0xFF).toString(16).padStart(2, '0')
        }
    }
}

/** The rules an edit is judged by, in the order a page would want to hear them. */
object WebEditorEdits {

    /** Anything the page sends that this build does not know about is ignored rather than fatal. */
    private val json = Json { ignoreUnknownKeys = true }

    /**
     * The one sentence for a body the phone can make no edit of: not JSON, or missing the pieces
     * every edit has, or not saying which version it was written against.
     *
     * One sentence rather than one per missing field, because a body that arrived half-formed is a
     * fault in the page rather than something the operator typed and can put right - while a field
     * they *did* type, out of range, is answered by the rules below in the app's own words.
     */
    private const val UNREADABLE = "That edit could not be read, so nothing was saved."

    /**
     * An edit to an asset the phone already has.
     *
     * [paths] is the asset's geometry *as the phone holds it now* - the line and its side tracks -
     * which is half of what the version the desk quoted is made of.
     */
    fun apply(
        current: AssetEntity,
        blockName: String?,
        paths: List<List<GeoPoint>>,
        body: String?
    ): WebEditorEditResult {
        val edit = parse(body) ?: return Refused(UNREADABLE)

        // The version first. When a field has moved, "somebody changed this on the phone" is the
        // useful thing to say, and arguing with a stale edit about whether its own fields are in
        // range would be an answer about an asset that is no longer there. A body that did not say
        // which version it was written against cannot be taken at all: there is nothing to compare.
        if (edit.version.isBlank()) return Refused(UNREADABLE)
        if (edit.version != WebEditorVersion.of(current, blockName, paths)) {
            return Refused(
                "This was changed on the phone while it was open here, so nothing was saved. " +
                    "Close the card and open it again to see the phone's version.",
                WebEditorRefusal.STALE
            )
        }

        return judge(edit, current)
    }

    /**
     * A new asset, drawn and described on a computer.
     *
     * Judged against a blank row so every rule still comes from the phone: [AssetEdits] is handed a
     * row with nothing in it, which is what a new asset is, and answers in its own words about the
     * name it was not given, the interval that is not a number, and so on. The id is the repository's
     * to issue and the date is the phone's clock rather than the laptop's.
     */
    fun create(body: String?, nowEpochMs: Long): WebEditorCreateResult {
        val edit = parse(body) ?: return CreateRefused(UNREADABLE)

        val judged = judge(edit, AssetEntity(name = "", createdAtEpochMs = nowEpochMs))
        return when (judged) {
            is WebEditorEditResult.Refused ->
                WebEditorCreateResult.Refused(judged.refusal, judged.message)

            is WebEditorEditResult.Ok -> {
                val points = judged.points
                    ?: return CreateRefused("A new track needs somewhere to go: draw it on the map.")
                WebEditorCreateResult.Ok(
                    WebEditorDraft(
                        asset = judged.asset,
                        blockName = judged.blockName,
                        // A track drawn on the desk has one line and no side tracks: there is no way
                        // to draw a side track here yet, so there is nothing to carry over.
                        geometry = AssetGeometry.of(points)
                    )
                )
            }
        }
    }

    /**
     * The row a body describes, added to [current] and judged by the app's own rules.
     *
     * One function for both writes on purpose: a new asset and an edit of one differ in the row they
     * are applied to and in whether a version must be quoted, and in nothing else. Two copies of
     * these rules would be two copies to keep in step, and the one that fell behind would be
     * whichever of the two writes is the rarer.
     */
    private fun judge(edit: WebEditorEdit, current: AssetEntity): WebEditorEditResult {

        // Named states rather than typed ones, so an unknown name is a refusal and not a silent
        // fallback onto a default: a kind the page made up must not become a track.
        val kind = AssetKind.entries.firstOrNull { it.name == edit.kind }
            ?: return Refused("The phone does not know what kind of thing \"${edit.kind}\" is.")
        val shape = AssetShape.entries.firstOrNull { it.name == edit.shape }
            ?: return Refused("The phone does not know a shape called \"${edit.shape}\".")
        val method = SprayMethod.entries.firstOrNull { it.name == edit.method }
            ?: return Refused("The phone does not know a spray method called \"${edit.method}\".")

        val fields = AssetEditFields(
            name = edit.name,
            groupName = edit.blockName.orEmpty(),
            kind = kind,
            shape = shape,
            method = method,
            intervalDays = edit.intervalDays,
            swathWidthM = edit.swathWidthM,
            notes = edit.notes.orEmpty(),
            passesRequired = edit.passesRequired,
            passSeparationM = edit.passSeparationM
        )

        // The app's own answer, message and all. The row it hands back is the row the phone holds
        // now with the editable fields replaced, so the write that follows cannot take anything
        // else with it - and `saveAssetEdits` resolves the block in the same transaction.
        return when (val result = AssetEdits.apply(current, fields)) {
            is AssetEditResult.Invalid -> Refused(result.message)
            is AssetEditResult.Ok -> {
                // The drawing, judged beside the words: a line the desk sends that its own shape
                // cannot be - a place with five points, a path of one - is refused in
                // `AssetPathEdits`'s words, which are the app's own.
                when (val drawn = pathOf(shape, edit.points)) {
                    null -> WebEditorEditResult.Ok(result.asset, result.groupName)
                    is AssetPathResult.Invalid -> Refused(drawn.message)
                    // The desk's body carries one line and no side tracks - the wire has no field for
                    // them yet - so a written path is the line, and what happens to an asset that has
                    // side tracks is the caller's decision rather than something to guess at here.
                    is AssetPathResult.Ok -> WebEditorEditResult.Ok(
                        asset = result.asset,
                        blockName = result.groupName,
                        points = drawn.paths.first()
                    )
                }
            }
        }
    }

    /**
     * The path a body carried, judged for the shape the row has.
     *
     * Null when the body said nothing about the drawing. A body that says nothing and a body that
     * sends an empty list are told apart deliberately: the first leaves the line exactly as it is,
     * and the second is a page saying it has drawn nothing - which for either shape is a refusal
     * rather than a way to empty a track.
     */
    private fun pathOf(shape: AssetShape, points: List<WebEditorPoint>?): AssetPathResult? =
        points?.let { drawn ->
            AssetPathEdits.apply(shape, drawn.map { GeoPoint(lat = it.lat, lng = it.lng) })
        }

    /** A refusal with no opinion of its own: a field the phone will not take as it was sent. */
    private fun Refused(message: String, refusal: WebEditorRefusal = WebEditorRefusal.INVALID) =
        WebEditorEditResult.Refused(refusal, message)

    private fun CreateRefused(message: String, refusal: WebEditorRefusal = WebEditorRefusal.INVALID) =
        WebEditorCreateResult.Refused(refusal, message)

    private fun parse(body: String?): WebEditorEdit? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(WebEditorEdit.serializer(), body) }.getOrNull()
    }
}
