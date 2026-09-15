package nz.mckenzie.sprayday.domain.asset

/**
 * How the chemical actually goes on.
 *
 * This is per asset rather than per spray, because it is a property of the place: the
 * estuary road is always done off the ute, the shelter is always done by hand. It
 * matters for the numbers a handover has to stand behind - a knapsack has no swath
 * width and no hectare rate, so treated area cannot be estimated from it the same way.
 *
 * [UNSET] is the honest default. Every asset that existed before this field did keeps
 * it, rather than being assumed to have been sprayed with a boom.
 */
enum class SprayMethod {
    /** Not recorded: either nobody has said, or the work predates the field. */
    UNSET,

    /** A spray boom on a vehicle. */
    BOOM,

    /** A knapsack sprayer, carried. */
    KNAPSACK
}
