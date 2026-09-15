package nz.mckenzie.sprayday.domain.asset

/**
 * How to say how a spray was applied, in the operator's words.
 *
 * Shared by the asset screen, the edit form and the handover record, so the words the
 * operator picks from cannot drift apart from the words in the file they hand over.
 *
 * [of] is for records: it says nothing at all for [SprayMethod.UNSET], because a cell
 * that reads "Not recorded" in a spreadsheet is a claim about the work rather than a
 * gap in it. [choice] is for the picker, where "not recorded" is a thing the operator
 * has to be able to choose and therefore has to be able to read.
 */
object MethodPhrase {

    /** "Boom", "Knapsack", or nothing when nobody has said. */
    fun of(method: SprayMethod): String = when (method) {
        SprayMethod.UNSET -> ""
        SprayMethod.BOOM -> "Boom"
        SprayMethod.KNAPSACK -> "Knapsack"
    }

    /** What to offer in the picker: the three states, named. */
    fun choice(method: SprayMethod): String = when (method) {
        SprayMethod.UNSET -> "Not recorded"
        else -> of(method)
    }

    /** The methods in the order the picker offers them, starting from the default. */
    val choices: List<SprayMethod> = listOf(
        SprayMethod.UNSET,
        SprayMethod.BOOM,
        SprayMethod.KNAPSACK
    )
}
