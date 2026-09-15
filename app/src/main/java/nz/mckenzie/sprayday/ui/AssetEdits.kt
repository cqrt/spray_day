package nz.mckenzie.sprayday.ui

import nz.mckenzie.sprayday.data.db.AssetEntity

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
 * The editable fields of an asset, as typed.
 *
 * The form is text, so every field needs a decision about what a blank, a typo or a
 * comma means. Those decisions are worth testing on their own, which is why this is
 * pure and lives outside the view model: the screen only has to show the message.
 */
object AssetEdits {

    /** Ten years. Past this the interval is a typo rather than an intention. */
    const val MAX_INTERVAL_DAYS = 3650

    /** No spray boom is wider than this, so a bigger number is a misread field. */
    const val MAX_SWATH_M = 100.0

    /** Narrower than this and it is not a boom, it is a stray keystroke. */
    const val MIN_SWATH_M = 0.1

    fun apply(
        asset: AssetEntity,
        name: String,
        groupName: String,
        intervalDays: String,
        swathWidthM: String,
        notes: String
    ): AssetEditResult {
        val cleanName = name.trim()
        if (cleanName.isEmpty()) {
            return AssetEditResult.Invalid("Give the track a name so it can be found later")
        }

        val days = intervalDays.trim().toIntOrNull()
            ?: return AssetEditResult.Invalid("Days between sprays must be a whole number")
        if (days !in 1..MAX_INTERVAL_DAYS) {
            return AssetEditResult.Invalid(
                "Days between sprays must be between 1 and $MAX_INTERVAL_DAYS"
            )
        }

        // Blank means "not known", which is different from zero: an asset with no
        // swath width simply cannot have its treated area estimated.
        val swath = when (val text = swathWidthM.trim()) {
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
                notes = notes.trim().ifBlank { null },
                intervalDays = days,
                swathWidthM = swath
            ),
            groupName = groupName.trim().ifBlank { null }
        )
    }
}
