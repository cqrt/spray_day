package nz.mckenzie.sprayday.map

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test

/**
 * The net itself: that it catches the malformed expression v0.6.97 shipped, and that it does not
 * catch the arrays a style is full of which are values rather than expressions.
 *
 * A checker that fires on everything is as useless as one that fires on nothing, and this one has to
 * tell a font stack from a filter - so both halves are pinned here.
 */
class StyleExpressionsTest {

    /** The exact shape the operator's desk refused, in the words it refused it with. */
    @Test
    fun `the literal that MapLibre refused is found`() {
        val wrong = """{"layers":[{"id":"linz-place-towns",""" +
            """"filter":["in",["get","place"],["literal","city","town"]]}]}"""

        val found = StyleExpressions.problems(wrong)

        assertEquals("one problem, and one only: $found", 1, found.size)
        assertTrue("it names the operator: ${found[0]}", found[0].contains("\"literal\""))
        assertTrue("and where it is: ${found[0]}", found[0].contains(".filter"))
        assertTrue("and what it wants: ${found[0]}", found[0].contains("exactly 1"))
    }

    @Test
    fun `the shape MapLibre wants is not`() {
        val right = """{"layers":[{"id":"linz-place-towns",""" +
            """"filter":["in",["get","place"],["literal",["city","town"]]]}]}"""

        assertEquals(emptyList<String>(), StyleExpressions.problems(right))
    }

    @Test
    fun `an expression with the wrong number of arguments is found wherever it is written`() {
        // In a paint property, in a layout property, and nested inside another expression: the walk
        // has to reach all three, because the builders write all three.
        val wrong = """
            {
              "layers": [
                {
                  "paint": { "fill-color": ["get"] },
                  "layout": { "icon-image": ["coalesce", ["get", "icon"], ["literal", "a", "b"]] }
                }
              ]
            }
        """.trimIndent()

        val found = StyleExpressions.problems(wrong)

        assertEquals("both are found: $found", 2, found.size)
        assertTrue(found.any { it.contains("\"get\"") && it.contains("paint") })
        assertTrue(found.any { it.contains("\"literal\"") && it.contains("layout") })
    }

    @Test
    fun `the arrays that are values rather than expressions are left alone`() {
        val values = """
            {
              "sources": { "linz-aerial": { "tiles": ["http://localhost:8791/tiles/linz-aerial/{z}/{x}/{y}.webp"] } },
              "layers": [
                {
                  "layout": {
                    "text-font": ["Noto Sans Regular"],
                    "text-offset": [0, 0.85],
                    "line-dasharray": [1, 3]
                  }
                }
              ]
            }
        """.trimIndent()

        assertEquals(
            "a font stack is a list of one string, not a call: " +
                StyleExpressions.problems(values),
            emptyList<String>(),
            StyleExpressions.problems(values)
        )
    }
}
