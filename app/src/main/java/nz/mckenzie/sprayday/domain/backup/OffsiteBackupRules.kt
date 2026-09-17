package nz.mckenzie.sprayday.domain.backup

import java.time.Instant
import java.time.ZoneId
import java.time.format.DateTimeFormatter
import java.time.temporal.ChronoUnit
import java.util.Locale

/**
 * The decisions an off-site backup makes, in one place and without a network.
 *
 * The first of them is the one that matters. A phone that has been wiped, or a fresh
 * install, holds nothing - and a backup that runs before the operator has restored would
 * replace the only copy of a season with an empty file. Nothing here is allowed to do that.
 */
object OffsiteBackupRules {

    /**
     * False when this phone holds nothing, which means the off-site copy is the only one.
     *
     * There is no override for this and there should not be one: "my phone is empty, so
     * empty it shall be written" is not a thing anybody means.
     */
    fun isSafeToWrite(here: BackupSummary): Boolean = !here.isEmpty

    /** Why nothing was written, in words that name the reason rather than blame the app. */
    fun refusedBecauseEmpty(): String =
        "This phone holds nothing to back up, and the off-site copy may be the only one " +
            "left. Nothing was written, so the copy is untouched. Restore from it instead."

    /** What the two sides hold, for the line above the button that replaces everything. */
    fun comparison(offsite: BackupSummary, here: BackupSummary): String =
        "Off-site copy: ${offsite.describe()}. This phone: ${here.describe()}."

    /**
     * How old a copy is, in the terms a person uses about backups: days, then months.
     *
     * The time of day is deliberately not included: what the operator is deciding is
     * whether a fortnight's spraying is missing from it, not what hour it was written.
     */
    fun ageOf(
        savedAtEpochMs: Long,
        nowEpochMs: Long,
        zoneId: ZoneId = ZoneId.systemDefault()
    ): String {
        val saved = Instant.ofEpochMilli(savedAtEpochMs).atZone(zoneId)
        val now = Instant.ofEpochMilli(nowEpochMs).atZone(zoneId)
        val days = ChronoUnit.DAYS.between(saved.toLocalDate(), now.toLocalDate())
        val date = saved.format(DateTimeFormatter.ofPattern("d MMM yyyy", Locale.US))
        return when {
            days < 0L -> "saved $date"
            days == 0L -> "saved today"
            days == 1L -> "saved yesterday"
            days < 60L -> "saved $days days ago, on $date"
            else -> "saved $date"
        }
    }

    /**
     * The name this device's copy is written under.
     *
     * One file per device, so two phones backing up to the same place cannot overwrite each
     * other's copy - which also means the operator can see which phone a copy came from.
     */
    fun fileNameFor(deviceName: String): String = FILE_PREFIX + safe(deviceName) + FILE_SUFFIX

    /** True for the files this app writes, so a repository's other contents are left alone. */
    fun isBackupFileName(name: String): Boolean =
        name.startsWith(FILE_PREFIX) && name.endsWith(FILE_SUFFIX)

    /** "pixel-8" from "Pixel 8", and "device" from anything with nothing usable in it. */
    private fun safe(deviceName: String): String {
        val safe = deviceName.lowercase(Locale.US)
            .map { character -> if (character.isLetterOrDigit()) character else '-' }
            .joinToString("")
            .trim('-')
            .replace(Regex("-+"), "-")
            .take(40)
            .trim('-')
        return safe.ifBlank { "device" }
    }

    /** Backup files all start and end the same way, which is what keeps [isBackupFileName] honest. */
    const val FILE_PREFIX = "spray-day-"
    const val FILE_SUFFIX = ".json"
}
