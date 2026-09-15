package nz.mckenzie.sprayday.domain.handover

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.util.Locale
import nz.mckenzie.sprayday.domain.asset.MethodPhrase
import nz.mckenzie.sprayday.domain.asset.SprayMethod

/**
 * One spray, as a row of a handover record.
 *
 * A row per product rather than per spray: "1.5 L of Glyphosate on Home block on the
 * 14th" is the unit an auditor, a client or the next operator asks about, and it is
 * the only shape that adds up in a spreadsheet.
 */
data class HandoverRow(
    val sprayedAtEpochMs: Long,
    val assetName: String,
    val groupName: String?,
    /**
     * [nz.mckenzie.sprayday.domain.asset.SprayMethod] name as stored, blank when nobody
     * has said how this was done. Read through [nz.mckenzie.sprayday.domain.asset.MethodPhrase]
     * so the file and the app use the same words.
     */
    val method: String? = null,
    val productName: String,
    val amount: Double,
    val unit: String,
    val waterLitres: Double? = null,
    val distanceM: Double? = null,
    val areaSqm: Double? = null,
    val operatorName: String? = null,
    val notes: String? = null,
    val recordingName: String? = null
)

/**
 * The season as a CSV, for handing to somebody else.
 *
 * A CSV rather than a PDF because handover means "this goes into your system": a
 * spreadsheet opens it, soaks up the columns, and can be printed or filed. The one
 * thing that must be right is the escaping - a block called `Home, north` or a note
 * with a quote in it must not shift every later column - so that is what the tests
 * here are about.
 *
 * Dates are ISO 8601 (`2026-09-14 15:32`) rather than the app's friendlier `14 Sep
 * 2026`, because a spreadsheet sorts ISO dates and has to be told what the other
 * kind mean.
 *
 * The method column says how the work was applied - boom or knapsack - and is blank
 * where nobody has recorded one, rather than claiming it was something.
 */
object HandoverCsv {

    val HEADERS = listOf(
        "Date",
        "Track",
        "Group",
        "Method",
        "Product",
        "Amount",
        "Unit",
        "Water (L)",
        "Distance (km)",
        "Area (ha)",
        "Operator",
        "Notes",
        "Recording"
    )

    private const val LINE_END = "\r\n"

    private val dateFormat = DateTimeFormatter.ofPattern("yyyy-MM-dd HH:mm", Locale.US)

    /** The whole record, headers included. Empty (headers only) when nothing was sprayed. */
    fun render(rows: List<HandoverRow>, zoneId: ZoneId = ZoneId.systemDefault()): String {
        val builder = StringBuilder()
        builder.append(HEADERS.joinToString(",")).append(LINE_END)
        rows.forEach { row ->
            builder.append(
                listOf(
                    field(date(row.sprayedAtEpochMs, zoneId)),
                    field(row.assetName),
                    field(row.groupName.orEmpty()),
                    field(MethodPhrase.of(SprayMethod.fromStorage(row.method))),
                    field(row.productName),
                    field(formatNumber(row.amount)),
                    field(row.unit),
                    field(row.waterLitres?.let(::formatNumber).orEmpty()),
                    field(row.distanceM?.let { formatNumber(it / 1000.0, decimals = 2) }.orEmpty()),
                    field(row.areaSqm?.let { formatNumber(it / 10_000.0, decimals = 2) }.orEmpty()),
                    field(row.operatorName.orEmpty()),
                    field(row.notes.orEmpty()),
                    field(row.recordingName.orEmpty())
                ).joinToString(",")
            ).append(LINE_END)
        }
        return builder.toString()
    }

    private fun date(epochMs: Long, zoneId: ZoneId): String =
        Instant.ofEpochMilli(epochMs).atZone(zoneId).format(dateFormat)

    /**
     * Numbers without units in them, because a cell that reads "2.35 km" is text to a
     * spreadsheet and cannot be added up.
     *
     * Amounts are given whole rather than forced to a number of decimals: a tenth of a
     * millilitre is a real entry, so rounding 1450.5 to 1451 would be losing something
     * the operator typed. Where decimals *are* asked for (distances in km, areas in
     * hectares) they are fixed, so every row lines up in the column.
     */
    private fun formatNumber(value: Double, decimals: Int? = null): String = when (decimals) {
        null -> {
            // Four decimals is finer than any measuring instrument, and keeps a computed
            // value from arriving as 0.30000000000000004.
            val rounded = Math.round(value * 10_000.0) / 10_000.0
            if (rounded == Math.floor(rounded) && !rounded.isInfinite()) {
                rounded.toLong().toString()
            } else {
                rounded.toString()
            }
        }

        else -> String.format(Locale.US, "%.${decimals}f", value)
    }

    /**
     * One CSV field.
     *
     * Quoted whenever it contains anything that would otherwise break the row - a
     * comma, a quote, a newline, or leading or trailing space that matters in a name.
     * Internal quotes are doubled, which is what the format asks for and, more to the
     * point, what every spreadsheet expects.
     */
    fun field(value: String): String {
        val needsQuotes = value.any { it == ',' || it == '\"' || it == '\n' || it == '\r' } ||
            value != value.trim()
        if (!needsQuotes) return value
        return "\"" + value.replace("\"", "\"\"") + "\""
    }
}
