package nz.mckenzie.sprayday.data

import nz.mckenzie.sprayday.backup.GitHubBackupTarget
import nz.mckenzie.sprayday.backup.RepoCheck
import nz.mckenzie.sprayday.domain.backup.BackupFormat
import nz.mckenzie.sprayday.domain.backup.BackupSummary
import nz.mckenzie.sprayday.domain.backup.BackupTarget
import nz.mckenzie.sprayday.domain.backup.OffsiteBackupRules
import nz.mckenzie.sprayday.domain.backup.StoredBackup

/** What an off-site backup just did. */
sealed interface OffsiteResult {
    /** The copy was written, and holds what this phone holds. */
    data class Written(val stored: StoredBackup, val summary: BackupSummary) : OffsiteResult

    /** This phone holds nothing, so nothing was written. */
    data object RefusedEmpty : OffsiteResult

    /** The destination would not take it. */
    data class Failed(val message: String) : OffsiteResult
}

/** An off-site copy, with what it holds, ready to be shown before anything is replaced. */
data class OffsitePreview(
    val stored: StoredBackup,
    val summary: BackupSummary,
    /** What is on this phone, for the comparison the operator is about to act on. */
    val here: BackupSummary,
    /** When the copy was written, read from inside the file rather than guessed from the clock. */
    val savedAtEpochMs: Long
)

/**
 * One off-site backup, start to finish, against whichever [BackupTarget] is configured.
 *
 * The guard at the top of [backUpNow] is the whole reason this is a class rather than three
 * lines in the screen: a phone that has just been wiped, or a fresh install, holds nothing,
 * and a backup that ran before the operator restored would replace the only remaining copy
 * of a season with an empty file. Nothing else here is allowed to make that decision, and
 * this one always answers no.
 *
 * Restoring reads the copy twice - once to show what it holds, once to replace everything
 * with it - rather than holding a season's worth of JSON in memory while the operator
 * decides. The second read is cheap next to the mistake of restoring something stale.
 */
class OffsiteBackup(
    private val controller: BackupController,
    private val repository: BackupRepository,
    private val target: BackupTarget
) {

    /** Writes this phone's backup to the destination, unless this phone holds nothing. */
    suspend fun backUpNow(): OffsiteResult {
        val here = repository.currentSummary()
        if (!OffsiteBackupRules.isSafeToWrite(here)) return OffsiteResult.RefusedEmpty
        return try {
            OffsiteResult.Written(stored = target.save(controller.exportText()), summary = here)
        } catch (failure: Exception) {
            OffsiteResult.Failed(failure.message ?: "The copy was not written.")
        }
    }

    /** What the destination holds, newest first as far as it can tell. */
    suspend fun list(): List<StoredBackup> = target.list()

    /** Fetches one copy and says what is in it, without touching what is on this phone. */
    suspend fun preview(stored: StoredBackup): OffsitePreview {
        val document = BackupFormat.decode(target.read(stored.reference))
        return OffsitePreview(
            stored = stored,
            summary = BackupFormat.summarise(document),
            here = repository.currentSummary(),
            savedAtEpochMs = document.exportedAtEpochMs
        )
    }

    /** Replaces everything on this phone with the copy, and with the switches it carries. */
    suspend fun restore(reference: String): BackupSummary = controller.restoreText(target.read(reference))

    /** For the screen, which says where the copy is going. */
    val destinationLabel: String get() = target.label

    /**
     * Asks the destination whether it will take the copy, and whether it is private.
     *
     * Null for a destination that cannot answer: a file can only be tested by writing to it,
     * and the question the operator actually needs answered - is this somewhere nobody else
     * can read? - only arises for a repository.
     */
    suspend fun check(): RepoCheck? = (target as? GitHubBackupTarget)?.check()
}
