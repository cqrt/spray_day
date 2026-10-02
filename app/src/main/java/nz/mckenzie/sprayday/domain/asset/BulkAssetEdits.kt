package nz.mckenzie.sprayday.domain.asset

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.geo.GeoPoint
import nz.mckenzie.sprayday.ui.AssetEditFields
import nz.mckenzie.sprayday.ui.AssetEditResult
import nz.mckenzie.sprayday.ui.AssetEdits

/**
 * The same edit made to several assets at once, and what came of it.
 *
 * **One request, and everything lands or nothing does.** Applying an edit a row at a time from a
 * browser cannot promise that: a refusal on the fourth of six leaves three changed and three not, with
 * nothing anywhere saying which. So a bulk edit is judged whole - every row read, every version checked
 * and every field run through the app's own rules **before anything is written** - and a refusal names
 * the one asset that stopped it rather than reporting a count of damage.
 *
 * What it is *for* is the errand that would otherwise be done six times: a paddock's worth of
 * fencelines given one spray method, a morning's drawing put into a block, an interval corrected on
 * everything in one block because the season says so. What it is not is a way around a rule: the fields
 * go through [AssetEdits], the same as the phone's own form and the desk's own card, so a width, an
 * interval, a kind and a name mean here exactly what they mean there.
 *
 * Pure, like the rules it delegates to: what is left in here is the arithmetic of *which* rows may be
 * changed, not what a field may hold.
 */
object BulkAssetEdits {

    /**
     * The most assets one request may carry.
     *
     * A bulk edit is done from a list on a screen, and a list longer than this is a list nobody is
     * reading row by row. It is a refusal rather than a silent truncation: a desk that ticked three
     * hundred rows and had two hundred written would have no way of knowing which.
     */
    const val MAX_ASSETS = 200

    /**
     * The fields a bulk edit may set, which is the whole of what one asset's own form may set.
     *
     * Every field arrives **as text**, exactly as the phone's own form holds it, because that is what
     * [AssetEdits] reads: what a blank, a comma or a stray space means is decided in one place, and it
     * is not decided here. The kind and the method arrive as the names of states rather than as typed
     * text, for the same reason they do everywhere else - they are choices, not strings.
     */
    data class Fields(
        val name: String,
        val kind: String,
        val method: String,
        val blockName: String = "",
        val intervalDays: String,
        val swathWidthM: String = "",
        val passesRequired: Int,
        val passSeparationM: String = "",
        val notes: String = ""
    )

    /** One of the assets being changed, and the fingerprint of the row the desk read. */
    data class Entry(val id: Long, val version: String)

    /** One row as the phone holds it: the row itself, the block it is in, and its geometry. */
    data class AssetRow(val asset: AssetEntity, val blockName: String?)

    /**
     * A row judged and ready to write: the row as it was read, the row as it will be, and the block.
     *
     * The row as it was read travels beside the row as it will be because the write updates the row it
     * is handed - and that must be the row the phone holds *now*, or a field the desk never saw would
     * be written back from a stale copy.
     */
    data class AssetEdit(val before: AssetEntity, val after: AssetEntity, val blockName: String?)

    /** What a bulk edit turned into. */
    sealed interface Outcome {

        /** How many rows were changed. Every one of them, or this would be a [Refused]. */
        data class Edited(val count: Int) : Outcome

        /**
         * A row moved on the phone since the desk read it.
         *
         * Its own outcome rather than a message, because the desk has one thing to do about it that it
         * has to do about nothing else: read the work again. What *moved* is not reported and does not
         * need to be - the desk's copy of the whole work is out of date, not of that one field.
         */
        data class Stale(val id: Long) : Outcome

        /** The request itself could not be taken: nothing was read, and nothing is said about any row. */
        data class Unreadable(val message: String) : Outcome

        /**
         * A row the rules will not take, named, with the app's own sentence about it.
         *
         * Every row is checked before any is written, so this is a statement about a request that
         * changed nothing rather than about a half-finished job.
         */
        data class Refused(val id: Long, val name: String, val message: String) : Outcome

        /** No row with that number is on the phone, or it is archived and so not the desk's to change. */
        data class Missing(val id: Long) : Outcome
    }

    /**
     * Whether one operator's field values may be put on several rows, and the rows each one becomes.
     *
     * [entries] is what the desk ticked, in the order it ticked them, and [fields] is what it typed.
     * The work is done in two passes on purpose: **every** row is read and judged first, and only then
     * is anything written - so a refusal cannot leave three rows changed and three not.
     *
     * [find] is the row the phone holds now, or null when it holds none - a lookup rather than a list,
     * because a caller that already has the work in hand should not have to read it twice.
     * [paths] is that row's geometry, and [isCurrent] is whether the fingerprint the desk quoted is
     * still that row's - the one question about a row this file does not answer for itself, because
     * what a fingerprint is made of belongs to the wire rather than to the rules about fields.
     * [save] is the write, and it is only ever reached with rows that have already been judged.
     */
    suspend fun apply(
        entries: List<Entry>,
        fields: Fields,
        find: suspend (Long) -> AssetRow?,
        paths: suspend (Long) -> List<List<GeoPoint>>,
        isCurrent: (Entry, AssetRow, List<List<GeoPoint>>) -> Boolean,
        save: suspend (List<AssetEdit>) -> Unit
    ): Outcome {
        if (entries.isEmpty()) {
            return Outcome.Unreadable("That edit did not name any assets, so nothing was changed.")
        }
        if (entries.map { it.id }.distinct().size != entries.size) {
            return Outcome.Unreadable(
                "That edit named the same asset twice, so nothing was changed."
            )
        }
        if (entries.size > MAX_ASSETS) {
            return Outcome.Unreadable(
                "That is ${entries.size} assets, and the phone changes up to $MAX_ASSETS at a time. " +
                    "Change them in smaller groups."
            )
        }

        val edits = mutableListOf<AssetEdit>()
        for (entry in entries) {
            val row = find(entry.id) ?: return Outcome.Missing(entry.id)
            val geometry = paths(entry.id)

            // The version first, and for the same reason one asset's own edit checks it first: when
            // something has moved, "this is out of date" is the more useful sentence, and arguing about
            // the fields of an asset that is no longer the one that was read would be an answer about
            // the wrong row.
            if (entry.version.isBlank() || !isCurrent(entry, row, geometry)) {
                return Outcome.Stale(entry.id)
            }

            when (val judged = judge(row, fields)) {
                is Judged.Refused -> return Outcome.Refused(entry.id, row.asset.name, judged.message)
                is Judged.Ok -> edits += AssetEdit(row.asset, judged.asset, judged.blockName)
            }
        }

        save(edits)
        return Outcome.Edited(edits.size)
    }

    /**
     * What to say when the rows have been changed: the count, in the app's own voice.
     *
     * Here rather than in the desk's own words because it is a statement about what the *phone* did,
     * and the desk's own confirmation - "Saved. The phone has it." - is a second sentence about the
     * same event. One row is not "1 assets", and one row is not a bulk edit either: it is the same
     * sentence an ordinary save would get, which is why it reads as an asset rather than as a job.
     */
    fun editedMessage(count: Int): String = when (count) {
        1 -> "Changed 1 asset."
        else -> "Changed all $count assets together."
    }

    private sealed interface Judged {
        data class Ok(val asset: AssetEntity, val blockName: String?) : Judged

        data class Refused(val message: String) : Judged
    }

    /**
     * One row through the app's own rules, with the operator's values put where they belong.
     *
     * **The values a bulk edit does not set are the values that row already has**, and that is the
     * whole of how one form can be about six different assets: every field is sent - the rules refuse an
     * asset with no name, and a blank interval is not a whole number - so the untouched ones travel as
     * themselves, and the row comes out the other side identical in everything but what was ticked.
     */
    private fun judge(row: AssetRow, fields: Fields): Judged {
        val current = row.asset

        // Named states rather than typed ones, exactly as one asset's own edit reads them: a kind the
        // page made up must not become a track. "Infrastructure" is the one other value, and it is what
        // every fenceline and trough on a farm sprayed before the kinds existed was called - the row's
        // own shape says which of the two it was, so a page from before the types still edits a
        // fenceline as a fenceline.
        val kind = AssetKind.known(fields.kind, AssetShape.fromStorage(current.shape))
            ?: return Judged.Refused("The phone does not know what kind of thing \"${fields.kind}\" is.")
        val method = SprayMethod.entries.firstOrNull { it.name == fields.method }
            ?: return Judged.Refused("The phone does not know a spray method called \"${fields.method}\".")

        return when (
            val edited = AssetEdits.apply(
                current,
                AssetEditFields(
                    name = fields.name,
                    groupName = fields.blockName,
                    kind = kind,
                    method = method,
                    intervalDays = fields.intervalDays,
                    swathWidthM = fields.swathWidthM,
                    notes = fields.notes,
                    passesRequired = fields.passesRequired,
                    passSeparationM = fields.passSeparationM
                )
            )
        ) {
            is AssetEditResult.Invalid -> Judged.Refused(edited.message)
            is AssetEditResult.Ok -> Judged.Ok(edited.asset, edited.groupName)
        }
    }
}
