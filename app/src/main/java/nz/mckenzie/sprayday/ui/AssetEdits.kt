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
    val shape: AssetShape,
    val method: SprayMethod,
    val intervalDays: String,
    val swathWidthM: String,
    val notes: String
)

/**
 * The editable fields of an asset.
 *
 * The form is text, so every field needs a decision about what a blank, a typo or a
 * comma means. Those decisions are worth testing on their own, which is why this is
 * pure and lives outside the view model: the screen only has to show the message.
 *
 * The kind, the shape and the spray method are the exceptions: they are choices
 * between named states rather than something typed, so they arrive already decided.
 */
object AssetEdits {

    /** Ten years. Past this the interval is a typo rather than an intention. */
    const val MAX_INTERVAL_DAYS = 3650

    /** No spray boom is wider than this, so a bigger number is a misread field. */
    const val MAX_SWATH_M = 100.0

    /** Narrower than this and it is not a boom, it is a stray keystroke. */
    const val MIN_SWATH_M = 0.1

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

        // Blank means "not known", which is different from zero: an asset with no
        // swath width simply cannot have its treated area estimated.
        val swath = when (val text = fields.swathWidthM.trim()) {
            "" -> null
            else -> parsePositiveAmount(text)
                ?: return AssetEditResult.Invalid("Swath width must be a number of metres, or empty")
        }
        if (swath != null && swath !in MIN_SWATH_M..MAX_SWATH_M) {
            return AssetEditResult.Invalid(
                "Swath width must be between $MIN_SWATH_M m and $MAX_SWATH_M m, or empty"
            )
        }

        return AssetEditResult.Ok(
            asset = asset.copy(
                name = cleanName,
                kind = fields.kind.name,
                shape = fields.shape.name,
                method = fields.method.name,
                notes = fields.notes.trim().ifBlank { null },
                intervalDays = days,
                swathWidthM = swath
            ),
            groupName = fields.groupName.trim().ifBlank { null }
        )
    }
}
