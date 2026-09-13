package nz.mckenzie.sprayday.ui

import java.util.Locale

/**
 * Parses a millilitre amount typed by an operator.
 *
 * Accepts a comma decimal separator (people type "1,5" for one and a half) and
 * returns null for blank or non-positive input, so "0 mL" can never be recorded
 * as a spray line by accident.
 */
fun parseQuantityMl(text: String): Double? = parsePositiveAmount(text)

/** Any positive amount the operator types, e.g. litres of water in the tank. */
fun parsePositiveAmount(text: String): Double? {
    val cleaned = text.trim().replace(',', '.')
    if (cleaned.isEmpty()) return null
    return cleaned.toDoubleOrNull()?.takeIf { it > 0.0 }
}

/** "1450" / "120.5" - drops a pointless ".0" so amounts read cleanly. */
fun formatQuantityMl(ml: Double): String =
    if (ml == Math.floor(ml) && !ml.isInfinite()) {
        ml.toLong().toString()
    } else {
        String.format(Locale.US, "%.1f", ml)
    }

/** "1450 mL" / "145 L" once the amount is big enough to read better in litres. */
fun formatQuantityWithUnit(ml: Double): String =
    if (ml >= 1000.0) String.format(Locale.US, "%.2f L", ml / 1000.0) else "${formatQuantityMl(ml)} mL"
