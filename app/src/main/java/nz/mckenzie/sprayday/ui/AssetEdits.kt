package nz.mckenzie.sprayday.ui

import nz.mckenzie.sprayday.data.db.AssetEntity
import nz.mckenzie.sprayday.domain.asset.AssetKind
import nz.mckenzie.sprayday.domain.asset.AssetShape
import nz.mckenzie.sprayday.domain.asset.SprayMethod

/** What the edit form made of what was typed into it. */
sealed interface AssetEditResult {

    /**
     * The asset to store, with the group it should sit in.
     *
     * The group travels as a name rather than an id because that is what the operator
     * typed, and because a name that matches nothing yet has to mean "start that
     * group" - resolving it is the repository's job, inside the same transaction as
     * the write. Blank means "no group", which is how an asset is taken out of one.
     */
    data class Ok(val asset: AssetEntity, val groupName: String?) : AssetEditResult

    /** What to tell the operator, in words that say what to fix. */
    data class Invalid(val message: String) : AssetEditResult
}

/**
 * Everything the edit form was holding, as typed or as chosen.
 *
 * Gathered into one value rather than passed as nine arguments: with this many fields,
 * two neighbouring names can be swapped without the compiler noticing, and a form is
 * exactly where such a swap would go unnoticed until a spray round was wrong.
 */
data class AssetEditFields(
    val name: String,
    val groupName: String,
    val kind: AssetKind,
    val method: SprayMethod,
    val intervalDays: String,
    val swathWidthM: String,
    val notes: String,
    /** Chooses between named states rather than typed: how many passes the job takes. */
    val passesRequired: Int = AssetEntity.DEFAULT_PASSES_REQUIRED,
    /** How far apart the two passes run, typed. Ignored when the job is one pass. */
    val passSeparationM: String = ""
)

/**
 * The editable fields of an asset.
 *
 * The form is text, so every field needs a decision about what a blank, a typo or a
 * comma means. Those decisions are worth testing on their own, which is why this is
 * pure and lives outside the view model: the screen only has to show the message.
 *
 * The kind and the spray method are the exceptions: they are choices between named states
 * rather than something typed, so they arrive already decided. The shape is not a field at all -
 * it is decided by the kind (see [nz.mckenzie.sprayday.domain.asset.AssetKind.shape]).
 */
object AssetEdits {

    /** Ten years. Past this the interval is a typo rather than an intention. */
    const val MAX_INTERVAL_DAYS = 3650

    /** No spray boom is wider than this, so a bigger number is a misread field. */
    const val MAX_SWATH_M = 100.0

    /** Narrower than this and it is not a boom, it is a stray keystroke. */
    const val MIN_SWATH_M = 0.1

    /**
     * What the form says where a kind that is ground would have had a swath width and a pass count.
     *
     * One sentence rather than an empty space, and the fields are not offered at all: a question that
     * vanished without a word reads as a screen that has lost something. Both fields are line ideas -
     * a width exists to guess an area that a ring measures from its own corners, and one run round a
     * carpark is the whole job.
     */
    const val GROUND_HINT = "A carpark is its own ground: its area is measured from the shape, " +
        "and one run round it is the job."

    /**
     * How far apart the two passes of a two-pass asset may be said to run.
     *
     * Half a metre is closer than the app can tell apart and fifty is a different paddock, so
     * either is a misread field rather than an answer. A field left empty is neither: it means
     * nobody has said, which is how the app reads "too close together to tell".
     */
    const val MIN_PASS_SEPARATION_M = 0.5
    const val MAX_PASS_SEPARATION_M = 50.0

    /**
     * What the block field says when nothing has been typed.
     *
     * A constant rather than a literal inside the branching below, because the desk's form says the
     * same sentence under the same field - the web editor is handed this and the two dynamic ones,
     * so a block field on a laptop explains itself in the phone's own words.
     */
    const val BLOCK_HINT = "Type a block, or leave it empty for an asset on its own"

    /**
     * What the swath field says under itself.
     *
     * The field is only used to estimate a treated area, so this sentence is the only thing that
     * says it may be left blank - which is why it belongs with the rule rather than with a screen,
     * and why the web editor is handed it rather than inventing its own.
     */
    const val SWATH_HINT = "Used for the treated-area estimate; leave empty if unknown"

    /**
     * The swath width the field should hold after the operator picks a method.
     *
     * Two things make this more than "fill in the default". The operator's boom is
     * adjustable, so a width they typed is never overwritten. And a width that is
     * still the *previous* method's default is not really theirs either: switching
     * from boom to knapsack should not leave a three-metre boom's width on a backpack,
     * so that one is replaced.
     */
    fun swathAfterMethodChange(
        previous: SprayMethod,
        chosen: SprayMethod,
        typed: String
    ): String {
        val current = typed.trim()
        val wasDefault = previous.defaultSwathM?.let(::formatPlainNumber)
        val untouched = current.isEmpty() || current == wasDefault
        if (!untouched) return typed
        return chosen.defaultSwathM?.let(::formatPlainNumber).orEmpty()
    }

    fun apply(asset: AssetEntity, fields: AssetEditFields): AssetEditResult {
        val cleanName = fields.name.trim()
        if (cleanName.isEmpty()) {
            return AssetEditResult.Invalid("Give it a name so it can be found later")
        }

        val days = fields.intervalDays.trim().toIntOrNull()
            ?: return AssetEditResult.Invalid("Days between sprays must be a whole number")
        if (days !in 1..MAX_INTERVAL_DAYS) {
            return AssetEditResult.Invalid(
                "Days between sprays must be between 1 and $MAX_INTERVAL_DAYS"
            )
        }

        // A kind that is ground answers both of those questions itself, so they are not read from the
        // fields at all - and not refused either: the form does not offer them for a carpark, so what
        // is left in them is not something the operator is asking to be stored. A width nothing uses
        // and a pass count that cannot happen are the sort of figures that end up quoted in a diary.
        val isGround = fields.kind.shape == AssetShape.AREA

        // Blank means "not known", which is different from zero: an asset with no
        // swath width simply cannot have its treated area estimated.
        val swath = if (isGround) null else when (val text = fields.swathWidthM.trim()) {
            "" -> null
            else -> parsePositiveAmount(text)
                ?: return AssetEditResult.Invalid("Swath width must be a number of metres, or empty")
        }
        if (swath != null && swath !in MIN_SWATH_M..MAX_SWATH_M) {
            return AssetEditResult.Invalid(
                "Swath width must be between $MIN_SWATH_M m and $MAX_SWATH_M m, or empty"
            )
        }

        // The two passes only exist if the job takes two, so a line that has just been set back
        // to one pass keeps no separation rather than storing a number nothing reads.
        val passes = if (isGround) {
            AssetEntity.DEFAULT_PASSES_REQUIRED
        } else {
            fields.passesRequired.coerceIn(
                AssetEntity.DEFAULT_PASSES_REQUIRED,
                AssetEntity.MAX_PASSES_REQUIRED
            )
        }
        val separation = when {
            passes < AssetEntity.TWO_PASSES_REQUIRED -> null
            fields.passSeparationM.isBlank() -> null
            else -> parsePositiveAmount(fields.passSeparationM.trim())
                ?: return AssetEditResult.Invalid(
                    "How far apart the two passes run must be a number of metres, or empty"
                )
        }
        if (separation != null && separation !in MIN_PASS_SEPARATION_M..MAX_PASS_SEPARATION_M) {
            return AssetEditResult.Invalid(
                "The two passes must be between $MIN_PASS_SEPARATION_M m and " +
                    "$MAX_PASS_SEPARATION_M m apart, or empty"
            )
        }

        return AssetEditResult.Ok(
            asset = asset.copy(
                name = cleanName,
                kind = fields.kind.name,
                // Written from the kind rather than from a second answer, so a stored row can only
                // say one thing: a picnic table is a place because a picnic table is a place.
                shape = fields.kind.shape.name,
                method = fields.method.name,
                notes = fields.notes.trim().ifBlank { null },
                intervalDays = days,
                swathWidthM = swath,
                passesRequired = passes,
                passSeparationM = separation
            ),
            groupName = fields.groupName.trim().ifBlank { null }
        )
    }

    /**
     * What the block field should say under itself.
     *
     * This is the only place a block can be started - by naming one that does not exist yet -
     * so the field says which of the two things the typed name is about to do. A misspelling
     * used to be a new block with one asset in it, and nothing anywhere said so.
     */
    fun blockHint(typed: String, existing: List<String>): String {
        val name = typed.trim()
        if (name.isEmpty()) return BLOCK_HINT
        val match = existing.firstOrNull { it.equals(name, ignoreCase = true) }
        return if (match != null) "In the block \"$match\"" else "Starts a new block called \"$name\""
    }

    /**
     * The blocks worth offering while the operator types.
     *
     * Nothing while the field is empty: this is a suggestion, not a menu, and a list that
     * drops open the moment the field is touched is in the way of the typing that would have
     * narrowed it. A name that is already there exactly is left out too, since picking it
     * would change nothing.
     */
    fun blockSuggestions(typed: String, existing: List<String>): List<String> {
        val name = typed.trim()
        if (name.isEmpty()) return emptyList()
        return existing.filter { block ->
            block.contains(name, ignoreCase = true) && !block.equals(name, ignoreCase = true)
        }
    }
}
