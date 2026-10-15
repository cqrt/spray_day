package nz.mckenzie.sprayday.map

import kotlinx.serialization.json.Json
import kotlinx.serialization.json.JsonArray
import kotlinx.serialization.json.JsonElement
import kotlinx.serialization.json.JsonObject
import kotlinx.serialization.json.JsonPrimitive

/**
 * The expression shapes MapLibre refuses, found in a style document before it is handed over.
 *
 * **Why this exists.** A style MapLibre refuses is not a warning on a map: the desk shows a blank
 * rectangle where the farm should be, and the only report of it is a line in a console nobody opens.
 * That is not hypothetical - v0.6.97 shipped `["literal", "city", "town"]`, where a `literal` takes
 * **one** argument and that argument is the array, and the operator's desk said
 * *"The map could not be drawn: layers[3].filter[2]: 'literal' expression requires exactly one
 * argument, but found 3 instead."* Nothing in the app's tests held the shape of the expressions the
 * style builders write; they held the *values* inside them, and the values were right.
 *
 * It is **not** the style specification. It is the operators this app actually writes, checked for
 * the one thing a plausible-looking edit gets wrong - an operator written with the wrong number of
 * arguments. An operator a style starts using without a line here is a gap in the net rather than a
 * pass, and the full specification is the authority on everything else.
 */
internal object StyleExpressions {

    /**
     * How many elements each operator the app writes takes, **counting the operator itself** - so
     * `["literal", [x]]` is 2.
     *
     * A range for the operators that take as many as they are given: `all`, `any`, `coalesce`, `in`
     * and `interpolate` are variadic, and the lower bound is the fewest that mean anything.
     */
    private val ARITY: Map<String, IntRange> = mapOf(
        "literal" to 2..2,
        "get" to 2..2,
        "==" to 3..3,
        "!=" to 3..3,
        "in" to 3..Int.MAX_VALUE,
        "all" to 2..Int.MAX_VALUE,
        "any" to 2..Int.MAX_VALUE,
        "coalesce" to 2..Int.MAX_VALUE,
        // ["interpolate", interpolation, input, stop, value, ...]: two stops at the very least.
        "interpolate" to 6..Int.MAX_VALUE
    )

    /** Every bad expression in [json], each as a sentence naming where it is. */
    fun problems(json: String): List<String> {
        val found = mutableListOf<String>()
        walk(Json.parseToJsonElement(json), "", found)
        return found
    }

    private fun walk(node: JsonElement, where: String, found: MutableList<String>) {
        when (node) {
            is JsonArray -> {
                // An operator is an array starting with a *string*: the guard is what keeps this
                // from firing on the arrays that are values rather than expressions - a font stack,
                // a dash pattern, a list of tile URLs.
                val head = (node.firstOrNull() as? JsonPrimitive)?.takeIf { it.isString }?.content
                val allowed = head?.let { ARITY[it] }
                if (allowed != null && node.size !in allowed) {
                    found += "\"$head\" at ${where.ifEmpty { "the top level" }} is written with " +
                        "${node.size - 1} argument(s) and takes ${allowed.describe()}: $node"
                }
                node.forEachIndexed { index, child -> walk(child, "$where[$index]", found) }
            }

            is JsonObject -> node.forEach { (key, child) -> walk(child, "$where.$key", found) }

            else -> Unit
        }
    }

    /**
     * How many **arguments** a range means, in words: the operator itself is not an argument.
     */
    private fun IntRange.describe(): String = when {
        first == last -> "exactly ${first - 1}"
        last == Int.MAX_VALUE -> "at least ${first - 1}"
        else -> "between ${first - 1} and ${last - 1}"
    }
}
