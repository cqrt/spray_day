package nz.mckenzie.sprayday.domain.handover

import org.junit.Assert.assertEquals
import org.junit.Assert.assertTrue
import org.junit.Test
import java.time.ZoneId

/**
 * The handover record.
 *
 * The escaping is the part that must be right: a handover record is read by somebody
 * else's spreadsheet, and one unescaped comma in a block name silently shifts every
 * later column - which is worse than a visibly broken file, because it still looks
 * like data.
 */
class HandoverCsvTest {

    private val zone = ZoneId.of("Pacific/Auckland")

    /** 2026-09-14 15:32 NZST (03:32 UTC). */
    private val sprayedAt = 1_789_356_720_000L

    private fun row(
        assetName: String = "Home block",
        groupName: String? = "Home",
        method: String? = "BOOM",
        productName: String = "Glyphosate",
        amount: Double = 1500.0,
        unit: String = "mL",
        notes: String? = "wind from the south",
        recordingName: String? = "Home block \u00b7 14 Sep"
    ) = HandoverRow(
        sprayedAtEpochMs = sprayedAt,
        assetName = assetName,
        groupName = groupName,
        method = method,
        productName = productName,
        amount = amount,
        unit = unit,
        waterLitres = 400.0,
        distanceM = 2350.0,
        areaSqm = 14_100.0,
        operatorName = "Matt",
        notes = notes,
        recordingName = recordingName
    )

    private fun lines(csv: String) = csv.trimEnd().split("\r\n")

    @Test
    fun `the record starts with column names a person can read`() {
        val csv = HandoverCsv.render(emptyList(), zone)

        assertEquals(
            "Date,Asset,Group,Method,Product,Amount,Unit,Water (L),Distance (km),Area (ha),Operator,Notes,Recording",
            lines(csv).single()
        )
    }

    @Test
    fun `nothing sprayed still produces a usable file`() {
        val csv = HandoverCsv.render(emptyList(), zone)

        assertEquals("headers only, no stray blank rows", 1, lines(csv).size)
    }

    @Test
    fun `a spray reads across the row in the right order`() {
        val csv = HandoverCsv.render(listOf(row()), zone)

        val values = lines(csv)[1].split(",")
        assertEquals("2026-09-14 15:32", values[0])
        assertEquals("Home block", values[1])
        assertEquals("Home", values[2])
        assertEquals("Boom", values[3])
        assertEquals("Glyphosate", values[4])
        assertEquals("1500", values[5])
        assertEquals("mL", values[6])
        assertEquals("400", values[7])
        assertEquals("2.35", values[8])
        assertEquals("1.41", values[9])
        assertEquals("Matt", values[10])
        assertEquals("wind from the south", values[11])
        assertEquals("Home block \u00b7 14 Sep", values[12])
    }

    @Test
    fun `an asset nobody has said how to spray says nothing rather than guessing`() {
        val csv = HandoverCsv.render(listOf(row(method = null)), zone)

        assertEquals("", lines(csv)[1].split(",")[3])
    }

    @Test
    fun `a comma in a name does not shift every later column`() {
        val csv = HandoverCsv.render(listOf(row(assetName = "Home, north")), zone)

        val line = lines(csv)[1]
        assertTrue("the name should be quoted: $line", line.contains("\"Home, north\""))
        // Still thirteen fields once parsed the way a spreadsheet would.
        assertEquals(13, parse(line).size)
        assertEquals("Glyphosate", parse(line)[4])
        assertEquals("Recording", HandoverCsv.HEADERS[12])
    }

    @Test
    fun `a quote in a note is doubled rather than ending the field`() {
        val csv = HandoverCsv.render(listOf(row(notes = "sprayed the \"wet\" corner")), zone)

        val line = lines(csv)[1]
        assertTrue("quotes should be doubled: $line", line.contains("\"sprayed the \"\"wet\"\" corner\""))
    }

    @Test
    fun `a newline in a note stays inside its field`() {
        val csv = HandoverCsv.render(listOf(row(notes = "line one\nline two")), zone)

        assertTrue("the note should be quoted", csv.contains("\"line one\nline two\""))
        // The record still has exactly one data row: the newline is inside the quotes.
        assertEquals("quoted newlines are part of the field", 13, parseFieldCount(csv, 1))
    }

    @Test
    fun `empty fields are empty rather than the word null`() {
        val csv = HandoverCsv.render(
            listOf(row(groupName = null, method = null, notes = null, recordingName = null)),
            zone
        )

        val values = parse(lines(csv)[1])
        assertEquals("", values[2])
        assertEquals("", values[3])
        assertEquals("", values[11])
        assertEquals("", values[12])
    }

    @Test
    fun `a name that is only whitespace is quoted, because it matters`() {
        assertEquals("\" Home block\"", HandoverCsv.field(" Home block"))
        assertEquals("Home block", HandoverCsv.field("Home block"))
        assertEquals("", HandoverCsv.field(""))
    }

    @Test
    fun `amounts and distances are numbers, not text with units in them`() {
        val csv = HandoverCsv.render(listOf(row(amount = 1450.5)), zone)

        val values = lines(csv)[1].split(",")
        assertEquals("1450.5", values[5])
        assertTrue("no unit inside a numeric cell", !values[5].contains("mL"))
        assertTrue("no unit inside a distance cell", !values[8].contains("km"))
    }

    @Test
    fun `a spray with no water or distance leaves those cells blank for the same reason`() {
        val csv = HandoverCsv.render(
            listOf(
                HandoverRow(
                    sprayedAtEpochMs = sprayedAt,
                    assetName = "Home block",
                    groupName = null,
                    productName = "Glyphosate",
                    amount = 900.0,
                    unit = "mL"
                )
            ),
            zone
        )

        val values = lines(csv)[1].split(",")
        assertEquals("900", values[5])
        assertEquals("", values[7])
        assertEquals("", values[8])
    }

    /** Splits a CSV line the way a spreadsheet would: quotes protect commas. */
    private fun parse(line: String): List<String> {
        val fields = mutableListOf<String>()
        val current = StringBuilder()
        var inQuotes = false
        var index = 0
        while (index < line.length) {
            val character = line[index]
            when {
                character == '"' && inQuotes && line.getOrNull(index + 1) == '"' -> {
                    current.append('"')
                    index++
                }

                character == '"' -> inQuotes = !inQuotes
                character == ',' && !inQuotes -> {
                    fields += current.toString()
                    current.clear()
                }

                else -> current.append(character)
            }
            index++
        }
        fields += current.toString()
        return fields
    }

    private fun parseFieldCount(csv: String, lineIndex: Int): Int =
        parse(lines(csv)[lineIndex]).size
}
