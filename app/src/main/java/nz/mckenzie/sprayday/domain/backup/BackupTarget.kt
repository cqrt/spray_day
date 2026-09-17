package nz.mckenzie.sprayday.domain.backup

import kotlinx.serialization.Serializable

/**
 * Where a copy of the backup is kept, outside this phone.
 *
 * An interface for the same reason [nz.mckenzie.sprayday.offline.TileFetcher] is one: the
 * decisions made on top of it - whether a backup is safe to write at all, what a restore
 * would replace - are worth testing without a network and without a file picker, so they
 * are tested against a fake.
 *
 * Targets deal in text, never in [BackupDocument]: the file's shape is [BackupFormat]'s
 * business alone, so a new destination cannot quietly invent its own.
 */
interface BackupTarget {

    /** For the screen: "GitHub", or the file the operator chose. */
    val label: String

    /** Writes [text] as this device's copy, replacing the one it wrote last time. */
    suspend fun save(text: String): StoredBackup

    /** The copies this target holds, newest first - one per device, usually one. */
    suspend fun list(): List<StoredBackup>

    /** Reads one back, by the reference [list] or [save] handed out. */
    suspend fun read(reference: String): String
}

/**
 * One copy of the backup, as a destination holds it.
 *
 * [savedAtEpochMs] is null when the destination cannot say: GitHub's contents API reports a
 * file's name, size and hash but not when it was written. The date is not lost - it is
 * inside the file, which is decoded anyway before anything is replaced.
 */
data class StoredBackup(
    val reference: String,
    val name: String,
    val sizeBytes: Long,
    val savedAtEpochMs: Long? = null
) {
    /** "3.4 MB", for the line that says what a restore would fetch. */
    val sizeLabel: String
        get() = when {
            sizeBytes <= 0L -> "unknown size"
            sizeBytes < 1_000_000L -> "${sizeBytes / 1000} kB"
            else -> "%.1f MB".format(sizeBytes / 1_000_000.0)
        }
}

/**
 * The settings worth carrying to another phone.
 *
 * Not the LINZ key, and not the GitHub token. A backup file may be read by whoever finds
 * it, and both of those are credentials - the token especially, since the file it would be
 * written into lives in the very repository that token can write to.
 */
@Serializable
data class BackupSettingsRecord(
    val remindersEnabled: Boolean = true,
    val updateChecksEnabled: Boolean = true,
    val backUpAutomatically: Boolean = false,
    /** The destination's name, so a new phone can be pointed the same way. */
    val destination: String? = null
)

/** Where the off-site copy is kept, as the operator chose it. */
enum class BackupDestination {
    /** Nowhere: the file the operator exports by hand stays the only copy. */
    OFF,

    /** A file this app writes to, in a place the system file picker allows. */
    FILE,

    /** A repository, so the copy is off the property and keeps its history. */
    GITHUB;

    companion object {
        /** The stored name read back, with anything unrecognised landing on [OFF]. */
        fun fromName(name: String?): BackupDestination =
            entries.firstOrNull { it.name == name } ?: OFF
    }
}

/**
 * Reading and writing the switches a backup carries.
 *
 * Separate from the settings themselves so that [nz.mckenzie.sprayday.data.BackupController]
 * can carry them without knowing how or where they are stored - and so a test can hand it a
 * record rather than a device's worth of preferences.
 */
interface BackupSwitches {
    suspend fun read(): BackupSettingsRecord
    suspend fun apply(record: BackupSettingsRecord)
}
