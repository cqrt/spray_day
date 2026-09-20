package nz.mckenzie.sprayday.web

import kotlinx.serialization.Serializable
import kotlinx.serialization.json.Json
import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod
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
 * **What is deliberately not here.** Geometry, and deleting. Moving a vertex is a different write
 * with a different preview and it lands next. Deleting is the one operation in the app that can
 * take a season's spray history with it, so it gets its own slice of work, with the sentence that
 * says what would go said before anything goes.
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
    /** What the desk read when it opened this asset: see [WebEditorVersion]. */
    val version: String
)

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

    /** A field the phone will not take. [WebEditorWrite.Refused.message] says which and why. */
    INVALID(400, "invalid")
}

/** What an edit turned into: a row to write, or why it will not be written. */
sealed interface WebEditorEditResult {

    /**
     * The row to store and the block it belongs in - the name the operator typed, which the
     * repository resolves inside the same transaction as the write.
     */
    data class Ok(val asset: AssetEntity, val blockName: String?) : WebEditorEditResult

    data class Refused(val refusal: WebEditorRefusal, val message: String) : WebEditorEditResult
}

/** What a route answers a write with. */
sealed interface WebEditorWrite {

    /** The asset as the phone now holds it, so the page can update its own copy at once. */
    data class Saved(val record: WebEditorAssetRecord) : WebEditorWrite

    data class Refused(val refusal: WebEditorRefusal, val message: String) : WebEditorWrite
}

/**
 * The version of an asset as far as the desk is concerned.
 *
 * A hash of the fields the desk may write, and of nothing else. So a spray recorded while the card
 * is open does not refuse the edit - the desk cannot overwrite a spray - while a rename on the
 * phone does, because it is a field this edit is about to write. The id is in the hash as well: an
 * edit written against one asset must not be taken for an edit to another that happens to hold the
 * same fields, and the id is where such a mix-up would show.
 */
object WebEditorVersion {

    fun of(asset: AssetEntity, blockName: String?): String {
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
        )
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

    fun apply(current: AssetEntity, blockName: String?, body: String?): WebEditorEditResult {
        val edit = parse(body) ?: return Refused("That edit could not be read, so nothing was saved.")

        // The version first. When a field has moved, "somebody changed this on the phone" is the
        // useful thing to say, and arguing with a stale edit about whether its own fields are in
        // range would be an answer about an asset that is no longer there.
        if (edit.version != WebEditorVersion.of(current, blockName)) {
            return Refused(
                "This was changed on the phone while it was open here, so nothing was saved. " +
                    "Close the card and open it again to see the phone's version.",
                WebEditorRefusal.STALE
            )
        }

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
            is AssetEditResult.Ok -> WebEditorEditResult.Ok(result.asset, result.groupName)
        }
    }

    /** A refusal with no opinion of its own: a field the phone will not take as it was sent. */
    private fun Refused(message: String, refusal: WebEditorRefusal = WebEditorRefusal.INVALID) =
        WebEditorEditResult.Refused(refusal, message)

    private fun parse(body: String?): WebEditorEdit? {
        if (body.isNullOrBlank()) return null
        return runCatching { json.decodeFromString(WebEditorEdit.serializer(), body) }.getOrNull()
    }
}
