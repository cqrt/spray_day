package nz.mckenzie.sprayday.ui

/**
 * What the block form made of what was typed into it.
 *
 * A block is created by naming it on an asset, so this is only ever a rename and some notes -
 * but a rename is the one edit that can collide, and a collision has to be a sentence rather
 * than a crash from the unique index underneath.
 */
sealed interface BlockEditResult {

    /** The name to store and the notes to store with it, both already trimmed. */
    data class Ok(val name: String, val notes: String?) : BlockEditResult

    /** What to tell the operator, in words that say what to fix. */
    data class Invalid(val message: String) : BlockEditResult
}

/** Everything the block form was holding. */
data class BlockEditFields(val name: String, val notes: String)

/**
 * The editable fields of a block.
 *
 * Pure, like [AssetEdits], and for the same reason: the form collects text, and what a blank
 * or a duplicated name means is worth testing without a screen.
 */
object BlockEdits {

    fun apply(current: String, fields: BlockEditFields, otherBlocks: List<String>): BlockEditResult {
        val name = fields.name.trim()
        if (name.isEmpty()) {
            return BlockEditResult.Invalid("Give the block a name, or delete it instead")
        }
        // The block's own name is not a clash with itself, whatever case it is typed in -
        // otherwise fixing the capitalisation of a name would be refused by its own row.
        val clash = otherBlocks.firstOrNull { other ->
            other.equals(name, ignoreCase = true) && !other.equals(current, ignoreCase = true)
        }
        if (clash != null) {
            return BlockEditResult.Invalid(
                "There is already a block called \"$clash\". Two blocks cannot share a name, " +
                    "because assets are gathered by it."
            )
        }

        return BlockEditResult.Ok(name = name, notes = fields.notes.trim().ifBlank { null })
    }

    /**
     * What the delete confirmation says.
     *
     * The count is in the sentence on purpose: the fear this button raises is losing the assets,
     * and the promise that they stay is worth saying with the number rather than in the
     * abstract. Nothing else about them changes - they simply stop being grouped.
     */
    fun deleteMessage(name: String, assetCount: Int): String {
        val assets = when (assetCount) {
            0 -> "\"$name\" holds no assets."
            1 -> "The asset in \"$name\" stays where it is - it simply comes out of the block."
            else -> "The $assetCount assets in \"$name\" stay where they are - they simply come out of the block."
        }
        return "$assets Nothing else about them changes, and this cannot be undone."
    }
}
